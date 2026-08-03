package io.github.haimfeng.taskcopilot.service;

import io.github.haimfeng.taskcopilot.config.TaskCopilotProperties;
import io.github.haimfeng.taskcopilot.domain.ExecutionStatus;
import io.github.haimfeng.taskcopilot.domain.Task;
import io.github.haimfeng.taskcopilot.domain.TaskLog;
import io.github.haimfeng.taskcopilot.repository.TaskLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * 负责任务执行的并发控制与日志落库。
 */
@Service
public class TaskExecutionService {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionService.class);

    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    public static final String TRIGGER_MANUAL = "MANUAL";

    private final CommandExecutor commandExecutor;
    private final TaskLogRepository taskLogRepository;
    private final TaskCopilotProperties properties;
    private final Semaphore concurrencyLimiter;

    public TaskExecutionService(CommandExecutor commandExecutor,
                                TaskLogRepository taskLogRepository,
                                TaskCopilotProperties properties) {
        this.commandExecutor = commandExecutor;
        this.taskLogRepository = taskLogRepository;
        this.properties = properties;
        this.concurrencyLimiter = new Semaphore(Math.max(1, properties.getMaxConcurrentExecutions()));
    }

    /**
     * 同步执行任务并记录日志。
     */
    @Transactional
    public TaskLog execute(Task task, String triggerSource) {
        boolean acquired = concurrencyLimiter.tryAcquire();
        if (!acquired) {
            log.warn("并发已达上限，任务 {} 本次执行被跳过", task.getName());
            return saveLog(task, triggerSource, new CommandExecutor.ExecutionResult(
                    ExecutionStatus.FAILURE, -1, "",
                    "并发执行已达上限（%d），本次执行被跳过".formatted(properties.getMaxConcurrentExecutions()),
                    java.time.Instant.now(), java.time.Instant.now()));
        }
        try {
            log.info("开始执行任务 [{}] ({})", task.getName(), triggerSource);
            CommandExecutor.ExecutionResult result = commandExecutor.execute(task);
            log.info("任务 [{}] 执行结束，状态={}, 退出码={}", task.getName(), result.status(), result.exitCode());
            return saveLog(task, triggerSource, result);
        } finally {
            concurrencyLimiter.release();
        }
    }

    /**
     * 异步执行（虚拟线程），用于调度触发与手动触发的非阻塞场景。
     */
    public void executeAsync(Task task, String triggerSource) {
        Thread.ofVirtual().name("tc-exec-" + task.getId()).start(() -> {
            try {
                execute(task, triggerSource);
            } catch (Exception e) {
                log.error("任务 [{}] 执行异常", task.getName(), e);
            }
        });
    }

    private TaskLog saveLog(Task task, String triggerSource, CommandExecutor.ExecutionResult result) {
        TaskLog entity = new TaskLog();
        entity.setTaskId(task.getId());
        entity.setTriggerSource(triggerSource);
        entity.setStartedAt(result.startedAt());
        entity.setFinishedAt(result.finishedAt());
        entity.setExitCode(result.exitCode());
        entity.setStdout(result.stdout());
        entity.setStderr(result.stderr());
        entity.setStatus(result.status());
        TaskLog saved = taskLogRepository.save(entity);
        purgeOldLogs(task.getId());
        return saved;
    }

    /**
     * 按保留条数清理历史日志，避免嵌入式库无限增长。
     */
    private void purgeOldLogs(Long taskId) {
        int retention = properties.getLogRetentionPerTask();
        if (retention <= 0 || taskLogRepository.countByTaskId(taskId) <= retention) {
            return;
        }
        // 取第 retention 条（0-based）作为分界，删除更早的记录
        List<TaskLog> boundary = taskLogRepository.findByTaskIdOrderByStartedAtDesc(
                taskId, PageRequest.of(retention - 1, 1));
        if (!boundary.isEmpty()) {
            taskLogRepository.deleteByTaskIdAndIdLessThanEqual(taskId, boundary.getFirst().getId() - 1);
        }
    }
}
