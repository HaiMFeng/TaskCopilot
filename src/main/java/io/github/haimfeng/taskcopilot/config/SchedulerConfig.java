package io.github.haimfeng.taskcopilot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * 调度线程池与执行线程配置。
 */
@Configuration
@EnableConfigurationProperties(TaskCopilotProperties.class)
public class SchedulerConfig {

    /**
     * 调度线程池只负责触发，实际命令执行走虚拟线程，故池子保持很小以降低占用。
     */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler taskCopilotScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("tc-sched-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }
}
