package io.github.haimfeng.taskcopilot.service;

import io.github.haimfeng.taskcopilot.config.TaskCopilotProperties;
import io.github.haimfeng.taskcopilot.domain.ExecutionStatus;
import io.github.haimfeng.taskcopilot.domain.Task;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 通过 {@link ProcessBuilder} 执行命令，负责超时控制与输出采集。
 */
@Component
public class CommandExecutor {

    private final TaskCopilotProperties properties;

    public CommandExecutor(TaskCopilotProperties properties) {
        this.properties = properties;
    }

    /**
     * 同步执行任务命令。
     */
    public ExecutionResult execute(Task task) {
        Instant startedAt = Instant.now();
        int timeout = task.getTimeoutSeconds() != null && task.getTimeoutSeconds() > 0
                ? task.getTimeoutSeconds()
                : properties.getDefaultTimeoutSeconds();

        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(shellCommand(task.getCommand()));
            if (task.getWorkingDir() != null && !task.getWorkingDir().isBlank()) {
                builder.directory(new File(task.getWorkingDir()));
            }
            process = builder.start();

            StreamCollector out = new StreamCollector(process.getInputStream());
            StreamCollector err = new StreamCollector(process.getErrorStream());
            Thread outThread = Thread.ofVirtual().start(out);
            Thread errThread = Thread.ofVirtual().start(err);

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                outThread.join(1000);
                errThread.join(1000);
                return new ExecutionResult(ExecutionStatus.TIMEOUT, -1,
                        truncate(out.text()),
                        truncate(appendLine(err.text(), "任务执行超时（%d 秒），进程已被终止".formatted(timeout))),
                        startedAt, Instant.now());
            }

            outThread.join(2000);
            errThread.join(2000);
            int exitCode = process.exitValue();
            return new ExecutionResult(
                    exitCode == 0 ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILURE,
                    exitCode,
                    truncate(out.text()),
                    truncate(err.text()),
                    startedAt, Instant.now());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new ExecutionResult(ExecutionStatus.FAILURE, -1, "",
                    "执行线程被中断: " + e.getMessage(), startedAt, Instant.now());
        } catch (IOException | RuntimeException e) {
            return new ExecutionResult(ExecutionStatus.FAILURE, -1, "",
                    "启动进程失败: " + e.getMessage(), startedAt, Instant.now());
        }
    }

    /**
     * 用系统 shell 包装命令，以便支持管道、重定向等写法。
     */
    private List<String> shellCommand(String command) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows
                ? List.of("cmd.exe", "/c", command)
                : List.of("/bin/sh", "-c", command);
    }

    private String truncate(String text) {
        int max = properties.getMaxOutputChars();
        if (text == null || max <= 0 || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n...（输出已截断）";
    }

    private String appendLine(String text, String line) {
        if (text == null || text.isBlank()) {
            return line;
        }
        return text + System.lineSeparator() + line;
    }

    /**
     * 单次执行结果。
     */
    public record ExecutionResult(
            ExecutionStatus status,
            int exitCode,
            String stdout,
            String stderr,
            Instant startedAt,
            Instant finishedAt
    ) {
    }

    /**
     * 异步读取进程输出，避免缓冲区写满造成阻塞。
     */
    private static final class StreamCollector implements Runnable {
        private final InputStream stream;
        private final StringBuilder buffer = new StringBuilder();

        private StreamCollector(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (buffer) {
                        buffer.append(line).append(System.lineSeparator());
                    }
                }
            } catch (IOException ignored) {
                // 进程被强杀时流关闭，忽略
            }
        }

        private String text() {
            synchronized (buffer) {
                return buffer.toString();
            }
        }
    }
}
