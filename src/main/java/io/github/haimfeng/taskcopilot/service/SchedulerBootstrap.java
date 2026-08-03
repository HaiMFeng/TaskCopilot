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
    private final TaskCopilotProperties properties;

    public SchedulerBootstrap(TaskScheduler taskScheduler, TaskCopilotProperties properties) {
        this.taskScheduler = taskScheduler;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (properties.isAutoStart()) {
            taskScheduler.reloadAll();
        }
    }
}
