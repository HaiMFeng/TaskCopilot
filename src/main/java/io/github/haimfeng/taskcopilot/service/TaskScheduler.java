package io.github.haimfeng.taskcopilot.service;

import io.github.haimfeng.taskcopilot.domain.Schedule;
import io.github.haimfeng.taskcopilot.domain.Task;
import io.github.haimfeng.taskcopilot.repository.ScheduleRepository;
import io.github.haimfeng.taskcopilot.repository.TaskRepository;
import io.github.haimfeng.taskcopilot.tasktype.TaskTypeHandler;
import io.github.haimfeng.taskcopilot.tasktype.TaskTypeRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 任务调度中枢：维护每个任务的下一次触发，并支持全局暂停 / 恢复。
 * <p>
 * 采用"一次性调度 + 执行后重排"的模式，
 * 这样任何 {@link TaskTypeHandler} 只要能算出下一次时间即可接入，无需关心周期语义。
 */
@Service
public class TaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskScheduler.class);

    private final ThreadPoolTaskScheduler scheduler;
    private final TaskRepository taskRepository;
    private final ScheduleRepository scheduleRepository;
    private final TaskTypeRegistry taskTypeRegistry;
    private final TaskConfigCodec configCodec;
    private final TaskExecutionService executionService;

    /** taskId -> 已注册的调度句柄 */
    private final Map<Long, ScheduledFuture<?>> scheduled = new ConcurrentHashMap<>();
    /** taskId -> 下一次触发时间，用于前端展示 */
    private final Map<Long, Instant> nextExecutions = new ConcurrentHashMap<>();

    private final AtomicBoolean globallyPaused = new AtomicBoolean(false);

    public TaskScheduler(ThreadPoolTaskScheduler taskCopilotScheduler,
                         TaskRepository taskRepository,
                         ScheduleRepository scheduleRepository,
                         TaskTypeRegistry taskTypeRegistry,
                         TaskConfigCodec configCodec,
                         TaskExecutionService executionService) {
        this.scheduler = taskCopilotScheduler;
        this.taskRepository = taskRepository;
        this.scheduleRepository = scheduleRepository;
        this.taskTypeRegistry = taskTypeRegistry;
        this.configCodec = configCodec;
        this.executionService = executionService;
    }

    /**
     * 重新加载全部「启用中且属于 active 日程表」的任务（启动时、全局恢复时调用）。
     */
    public synchronized void reloadAll() {
        cancelAll();
        if (globallyPaused.get()) {
            log.info("调度处于全局暂停状态，跳过任务注册");
            return;
        }
        taskRepository.findByEnabledTrueOrderBySortOrderAscIdAsc().stream()
                .filter(this::belongsToActiveSchedule)
                .forEach(this::schedule);
        log.info("已注册 {} 个定时任务", scheduled.size());
    }

    /**
     * 注册或刷新单个任务的调度。若任务不属于当前运行的日程表，则不会自动触发。
     */
    public synchronized void schedule(Task task) {
        unschedule(task.getId());
        if (!task.isEnabled() || globallyPaused.get() || !belongsToActiveSchedule(task)) {
            return;
        }
        TaskTypeHandler handler = taskTypeRegistry.find(task.getTypeCode()).orElse(null);
        if (handler == null) {
            log.warn("任务 [{}] 的类型 {} 未注册，跳过调度", task.getName(), task.getTypeCode());
            return;
        }
        Map<String, Object> config = configCodec.read(task.getConfigJson());
        Optional<Instant> next = handler.nextExecution(config, Instant.now());
        if (next.isEmpty()) {
            log.info("任务 [{}] 无后续触发时间，不再调度", task.getName());
            nextExecutions.remove(task.getId());
            return;
        }
        Instant runAt = next.get();
        Long taskId = task.getId();
        ScheduledFuture<?> future = scheduler.schedule(() -> fire(taskId), runAt);
        scheduled.put(taskId, future);
        nextExecutions.put(taskId, runAt);
        log.debug("任务 [{}] 下次执行时间 {}", task.getName(), runAt);
    }

    /**
     * 判断任务是否归属当前生效的日程表。
     * <ul>
     *     <li>未归属任何日程表（scheduleId 为 null）的任务，视作不参与自动调度；</li>
     *     <li>归属的日程表处于 active，则参与自动调度。</li>
     * </ul>
     */
    private boolean belongsToActiveSchedule(Task task) {
        if (task.getScheduleId() == null) {
            return false;
        }
        return scheduleRepository.findById(task.getScheduleId())
                .map(Schedule::isActive)
                .orElse(false);
    }

    /**
     * 触发执行，并在执行后重新排期。
     */
    private void fire(Long taskId) {
        scheduled.remove(taskId);
        nextExecutions.remove(taskId);
        Task task = taskRepository.findById(taskId).orElse(null);
        if (task == null || !task.isEnabled() || globallyPaused.get() || !belongsToActiveSchedule(task)) {
            return;
        }
        executionService.executeAsync(task, TaskExecutionService.TRIGGER_SCHEDULED);
        schedule(task);
    }

    public synchronized void unschedule(Long taskId) {
        ScheduledFuture<?> future = scheduled.remove(taskId);
        if (future != null) {
            future.cancel(false);
        }
        nextExecutions.remove(taskId);
    }

    private void cancelAll() {
        scheduled.values().forEach(f -> f.cancel(false));
        scheduled.clear();
        nextExecutions.clear();
    }

    /** 全局暂停所有调度（不影响手动执行） */
    public synchronized void pauseAll() {
        globallyPaused.set(true);
        cancelAll();
        log.info("已全局暂停所有定时任务");
    }

    /** 恢复全局调度 */
    public synchronized void resumeAll() {
        globallyPaused.set(false);
        reloadAll();
        log.info("已恢复所有定时任务");
    }

    public boolean isGloballyPaused() {
        return globallyPaused.get();
    }

    public Instant nextExecutionOf(Long taskId) {
        return nextExecutions.get(taskId);
    }

    public int scheduledCount() {
        return scheduled.size();
    }

    @PreDestroy
    void shutdown() {
        cancelAll();
    }
}
