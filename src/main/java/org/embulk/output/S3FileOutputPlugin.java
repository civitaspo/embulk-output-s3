package org.embulk.output;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;

import org.embulk.config.TaskReport;
import org.embulk.config.Config;
import org.embulk.config.ConfigDefault;
import org.embulk.config.ConfigDiff;
import org.embulk.config.ConfigException;
import org.embulk.config.ConfigSource;
import org.embulk.config.Task;
import org.embulk.config.TaskSource;
import org.embulk.spi.Buffer;
import org.embulk.spi.Exec;
import org.embulk.spi.FileOutput;
import org.embulk.spi.FileOutputPlugin;
import org.embulk.spi.TransactionalFileOutput;
import org.slf4j.Logger;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.google.common.base.Optional;

public class S3FileOutputPlugin implements FileOutputPlugin {
    public interface PluginTask extends Task {
        @Config("path_prefix")
        public String getPathPrefix();

        @Config("file_ext")
        public String getFileNameExtension();

        @Config("sequence_format")
        @ConfigDefault("\".%03d.%02d\"")
        public String getSequenceFormat();

        @Config("bucket")
        public String getBucket();

        @Config("endpoint")
        public String getEndpoint();

        @Config("access_key_id")
        @ConfigDefault("null")
        public Optional<String> getAccessKeyId();

        @Config("secret_access_key")
        @ConfigDefault("null")
        public Optional<String> getSecretAccessKey();

        @Config("tmp_path_prefix")
        @ConfigDefault("\"embulk-output-s3-\"")
        public String getTempPathPrefix();

        @Config("file_buffer_chunk_limit")
        @ConfigDefault("0") // default: 0 means no limit.
        public long getTotalFileBufferChunkLimit();

        public long getFileBufferChunkLimit();
        public long setFileBufferChunkLimit(long _byte);
    }

    public static class S3FileOutput implements FileOutput,
            TransactionalFileOutput {
        private final Logger log = Exec.getLogger(S3FileOutputPlugin.class);

        private final String bucket;
        private final String pathPrefix;
        private final String sequenceFormat;
        private final String fileNameExtension;
        private final String tempPathPrefix;
        private final long fileBufferChunkLimit;

        private int taskIndex;
        private int fileIndex;
        private AmazonS3Client client;
        private OutputStream current;
        private Path tempFilePath;

        private static AmazonS3Client newS3Client(PluginTask task) {
            AmazonS3Client client = null;
            try {
                if (task.getAccessKeyId().isPresent()) {
                    BasicAWSCredentials basicAWSCredentials = new BasicAWSCredentials(
                        task.getAccessKeyId().get(), task.getSecretAccessKey().get());

                    ClientConfiguration config = new ClientConfiguration();
                    // TODO: Support more configurations.

                    client = new AmazonS3Client(basicAWSCredentials, config);
                } else {
                    if (System.getenv("AWS_ACCESS_KEY_ID") == null) {
                        client = new AmazonS3Client(new EnvironmentVariableCredentialsProvider());
                    } else { // IAM ROLE
                        client = new AmazonS3Client();
                    }
                }
                client.setEndpoint(task.getEndpoint());
                client.isRequesterPaysEnabled(task.getBucket()); // check s3 access.
            } catch (Exception e) {
                throw new RuntimeException("can't call S3 API. Please check your access_key_id / secret_access_key or s3_region configuration.", e);
            }

            return client;
        }

        public S3FileOutput(PluginTask task, int taskIndex) {
            this.taskIndex = taskIndex;
            this.client = newS3Client(task);
            this.bucket = task.getBucket();
            this.pathPrefix = task.getPathPrefix();
            this.sequenceFormat = task.getSequenceFormat();
            this.fileNameExtension = task.getFileNameExtension();
            this.tempPathPrefix = task.getTempPathPrefix();
            this.fileBufferChunkLimit = task.getFileBufferChunkLimit();
        }

        private static Path newTempFile(String prefix) throws IOException {
            return Files.createTempFile(prefix, null);
        }

        private long getTempFileSize()
                throws IOException
        {
            if (tempFilePath == null) {
                return 0;
            }
            return Files.size(tempFilePath);
        }

        private void deleteTempFile() {
            if (tempFilePath == null) {
                return;
            }

            try {
                Files.delete(tempFilePath);
                tempFilePath = null;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private String buildCurrentKey() {
            String sequence = String.format(sequenceFormat, taskIndex,
                    fileIndex);
            return pathPrefix + sequence + fileNameExtension;
        }

        private void putFile(Path from, String key) {
            PutObjectRequest request = new PutObjectRequest(bucket, key,
                    from.toFile());
            client.putObject(request);
        }

        private void closeCurrent() {
            if (current == null) {
                return;
            }

            try {
                putFile(tempFilePath, buildCurrentKey());
                fileIndex++;
            } finally {
                try {
                    current.close();
                    current = null;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } finally {
                    deleteTempFile();
                }
            }
        }

        @Override
        public void nextFile() {
            closeCurrent();

            try {
                tempFilePath = newTempFile(tempPathPrefix);

                log.info("Writing S3 file '{}'", buildCurrentKey());

                current = Files.newOutputStream(tempFilePath);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void add(Buffer buffer) {
            if (current == null) {
                throw new IllegalStateException(
                        "nextFile() must be called before poll()");
            }

            try {
                current.write(buffer.array(), buffer.offset(), buffer.limit());
                if (fileBufferChunkLimit > 0 && fileBufferChunkLimit < getTempFileSize()) {
                    nextFile();
                }
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            } finally {
                buffer.release();
            }
        }

        @Override
        public void finish() {
            closeCurrent();
        }

        @Override
        public void close() {
            closeCurrent();
        }

        @Override
        public void abort() {
            deleteTempFile();
        }

        @Override
        public TaskReport commit() {
            TaskReport report = Exec.newTaskReport();
            return report;
        }
    }

    private void validateSequenceFormat(PluginTask task) {
        try {
            @SuppressWarnings("unused")
            String dontCare = String.format(Locale.ENGLISH,
                    task.getSequenceFormat(), 0, 0);
        } catch (IllegalFormatException ex) {
            throw new ConfigException(
                    "Invalid sequence_format: parameter for file output plugin",
                    ex);
        }
    }

    @Override
    public ConfigDiff transaction(ConfigSource config, int taskCount,
            Control control) {
        PluginTask task = config.loadConfig(PluginTask.class);

        validateSequenceFormat(task);

        // setFileBufferChunkLimit
        task.setFileBufferChunkLimit(task.getTotalFileBufferChunkLimit() / taskCount);

        return resume(task.dump(), taskCount, control);
    }

    @Override
    public ConfigDiff resume(TaskSource taskSource, int taskCount,
            Control control) {
        control.run(taskSource);
        return Exec.newConfigDiff();
    }

    @Override
    public void cleanup(TaskSource taskSource, int taskCount,
            List<TaskReport> successTaskReports) {
    }

    @Override
    public TransactionalFileOutput open(TaskSource taskSource, int taskIndex) {
        PluginTask task = taskSource.loadTask(PluginTask.class);

        return new S3FileOutput(task, taskIndex);
    }
}
