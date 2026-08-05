package io.github.haimfeng.taskcopilot.service;

import io.github.haimfeng.taskcopilot.config.TaskCopilotProperties;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 应用就绪后自动恢复已启用任务的调度。
 */
@Component
public class SchedulerBootstrap {

    private final TaskScheduler taskScheduler;
    private final ScheduleService scheduleService;
    private final StartupTaskRunner startupTaskRunner;
    private final TaskCopilotProperties properties;

    public SchedulerBootstrap(TaskScheduler taskScheduler,
                              ScheduleService scheduleService,
                              StartupTaskRunner startupTaskRunner,
                              TaskCopilotProperties properties) {
        this.taskScheduler = taskScheduler;
        this.scheduleService = scheduleService;
        this.startupTaskRunner = startupTaskRunner;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (properties.isAutoStart()) {
            // 确保存在 active 日程表，否则 reloadAll 不会注册任何任务
            scheduleService.currentOrDefault();
            taskScheduler.reloadAll();
            // 定时任务注册完成后，再按顺序跑一遍「启动运行」任务（异步，避免阻塞启动）
            startupTaskRunner.runAllAsync();
        }
    }
}
