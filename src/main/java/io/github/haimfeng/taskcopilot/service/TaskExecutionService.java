package io.github.haimfeng.taskcopilot.service;

import io.github.haimfeng.taskcopilot.config.TaskCopilotProperties;
import io.github.haimfeng.taskcopilot.domain.ExecutionStatus;
import io.github.haimfeng.taskcopilot.domain.Task;
import io.github.haimfeng.taskcopilot.domain.TaskLog;
import io.github.haimfeng.taskcopilot.repository.TaskLogRepository;
import io.github.haimfeng.taskcopilot.repository.TaskRepository;
import io.github.haimfeng.taskcopilot.tasktype.TaskTypeHandler;
import io.github.haimfeng.taskcopilot.tasktype.TaskTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
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
    private final TaskRepository taskRepository;
    private final TaskCopilotProperties properties;
    private final TaskTypeRegistry taskTypeRegistry;
    private final TaskConfigCodec configCodec;
    private final Semaphore concurrencyLimiter;
    private final io.github.haimfeng.taskcopilot.service.TaskScheduler taskScheduler;

    public TaskExecutionService(CommandExecutor commandExecutor,
                                TaskLogRepository taskLogRepository,
                                TaskRepository taskRepository,
                                TaskCopilotProperties properties,
                                TaskTypeRegistry taskTypeRegistry,
                                TaskConfigCodec configCodec,
                                @org.springframework.context.annotation.Lazy
                                io.github.haimfeng.taskcopilot.service.TaskScheduler taskScheduler) {
        this.commandExecutor = commandExecutor;
        this.taskLogRepository = taskLogRepository;
        this.taskRepository = taskRepository;
        this.properties = properties;
        this.taskTypeRegistry = taskTypeRegistry;
        this.configCodec = configCodec;
        this.concurrencyLimiter = new Semaphore(Math.max(1, properties.getMaxConcurrentExecutions()));
        this.taskScheduler = taskScheduler;
    }

    /**
     * 同步执行任务并记录日志。按任务类型分派到对应执行逻辑。
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
            CommandExecutor.ExecutionResult result = runByType(task);
            log.info("任务 [{}] 执行结束，状态={}, 退出码={}", task.getName(), result.status(), result.exitCode());
            if (result.status() == ExecutionStatus.FAILURE) {
                taskScheduler.markError(task.getId());
            }
            // 执行失败或超时的任务自动停用，避免反复触发同一个坏任务
            if (result.status() != ExecutionStatus.SUCCESS) {
                result = disableTaskAfterFailure(task, result);
            }
            return saveLog(task, triggerSource, result);
        } finally {
            concurrencyLimiter.release();
        }
    }

    /**
     * 任务执行失败（或超时）后自动关闭其启用开关，并解除调度注册。
     * 同时在 stderr 末尾追加说明，便于用户在结果区直接看到停用原因。
     *
     * @return 追加了停用说明的执行结果；停用失败时原样返回
     */
    private CommandExecutor.ExecutionResult disableTaskAfterFailure(
            Task task, CommandExecutor.ExecutionResult result) {
        try {
            Task fresh = taskRepository.findById(task.getId()).orElse(null);
            if (fresh == null || !fresh.isEnabled()) {
                // 任务已被删除或本就处于停用状态，无需重复处理
                return result;
            }
            fresh.setEnabled(false);
            taskRepository.save(fresh);
            task.setEnabled(false);
            taskScheduler.unschedule(task.getId());
            log.warn("任务 [{}] 执行{}，已自动关闭任务开关", task.getName(),
                    result.status() == ExecutionStatus.TIMEOUT ? "超时" : "失败");

            String note = "任务执行%s，已自动关闭任务开关，请修复后手动重新启用。"
                    .formatted(result.status() == ExecutionStatus.TIMEOUT ? "超时" : "失败");
            String stderr = result.stderr() == null || result.stderr().isBlank()
                    ? note
                    : result.stderr().stripTrailing() + System.lineSeparator() + note;
            return new CommandExecutor.ExecutionResult(
                    result.status(), result.exitCode(), result.stdout(), stderr,
                    result.startedAt(), result.finishedAt());
        } catch (Exception e) {
            // 自动停用属于附加行为，失败不应影响主流程与日志落库
            log.error("任务 [{}] 自动停用失败", task.getName(), e);
            return result;
        }
    }

    /** 依据任务类型选择执行方式；无对应 handler 时兜底为命令执行 */
    private CommandExecutor.ExecutionResult runByType(Task task) {
        Map<String, Object> config = configCodec.read(task.getConfigJson());
        TaskTypeHandler handler = taskTypeRegistry.find(task.getTypeCode()).orElse(null);
        if (handler != null) {
            return handler.execute(task, config, commandExecutor)
                    .orElseGet(() -> commandExecutor.execute(task));
        }
        return commandExecutor.execute(task);
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
