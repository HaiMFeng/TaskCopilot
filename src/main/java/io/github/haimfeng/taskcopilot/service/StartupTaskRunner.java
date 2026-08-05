package io.github.haimfeng.taskcopilot.service;

import io.github.haimfeng.taskcopilot.domain.Schedule;
import io.github.haimfeng.taskcopilot.domain.Task;
import io.github.haimfeng.taskcopilot.domain.TriggerMode;
import io.github.haimfeng.taskcopilot.repository.ScheduleRepository;
import io.github.haimfeng.taskcopilot.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 「启动运行」任务的执行器。
 * <p>
 * 服务器启动就绪后，按任务列表中的顺序（sortOrder 升序）<b>逐个、串行</b>地运行所有
 * 启用中且归属当前 active 日程表的启动任务。串行是刻意的：启动任务常用于恢复运行环境
 * （如依次拉起依赖服务），顺序即语义，因此必须等前一个执行结束再执行下一个。
 */
@Service
public class StartupTaskRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupTaskRunner.class);

    private final TaskRepository taskRepository;
    private final ScheduleRepository scheduleRepository;
    private final TaskExecutionService executionService;

    public StartupTaskRunner(TaskRepository taskRepository,
                             ScheduleRepository scheduleRepository,
                             TaskExecutionService executionService) {
        this.taskRepository = taskRepository;
        this.scheduleRepository = scheduleRepository;
        this.executionService = executionService;
    }

    /**
     * 在后台线程中按顺序执行全部启动任务，不阻塞应用启动流程。
     */
    public void runAllAsync() {
        Thread.ofVirtual().name("tc-startup-runner").start(this::runAll);
    }

    /**
     * 按顺序同步执行全部启动任务。单个任务失败不会中断整体流程，
     * 失败任务由 {@link TaskExecutionService} 记录日志并自动停用。
     */
    public void runAll() {
        List<Task> tasks = taskRepository
                .findByTriggerModeAndEnabledTrueOrderBySortOrderAscIdAsc(TriggerMode.STARTUP)
                .stream()
                .filter(this::belongsToActiveSchedule)
                .toList();
        if (tasks.isEmpty()) {
            log.info("没有需要在启动时运行的任务");
            return;
        }
        log.info("开始按顺序执行 {} 个启动任务", tasks.size());
        int index = 0;
        for (Task task : tasks) {
            index++;
            log.info("({}/{}) 执行启动任务 [{}]", index, tasks.size(), task.getName());
            try {
                executionService.execute(task, TaskExecutionService.TRIGGER_STARTUP);
            } catch (Exception e) {
                // 保证队列继续推进：单个任务异常不应阻断其余启动任务
                log.error("启动任务 [{}] 执行异常，继续执行后续任务", task.getName(), e);
            }
        }
        log.info("启动任务全部执行完毕");
    }

    /** 仅执行归属当前运行中日程表的任务，语义与定时调度保持一致 */
    private boolean belongsToActiveSchedule(Task task) {
        if (task.getScheduleId() == null) {
            return false;
        }
        return scheduleRepository.findById(task.getScheduleId())
                .map(Schedule::isActive)
                .orElse(false);
    }
}
