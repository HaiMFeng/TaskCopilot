package io.github.haimfeng.taskcopilot.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用可调参数。
 */
@ConfigurationProperties(prefix = "taskcopilot")
@Getter
@Setter
public class TaskCopilotProperties {

    /** 默认任务超时（秒） */
    private int defaultTimeoutSeconds = 60;

    /** 并发执行上限 */
    private int maxConcurrentExecutions = 5;

    /** 单条日志保留的输出最大字符数 */
    private int maxOutputChars = 20_000;

    /** 每个任务保留的日志条数，超出后自动清理；<=0 表示不清理 */
    private int logRetentionPerTask = 200;

    /** 启动时是否自动注册已启用任务 */
    private boolean autoStart = true;
}
