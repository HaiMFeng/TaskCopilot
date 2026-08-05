package io.github.haimfeng.taskcopilot.domain;

/**
 * 任务的运行方式。
 * <p>
 * 一个任务要么在服务器启动后立即运行一次（{@link #STARTUP}），
 * 要么按每日定时时间反复触发（{@link #SCHEDULED}）。
 * 旧版本数据不含该字段，读取时一律按 {@link #SCHEDULED} 处理以保持向上兼容。
 */
public enum TriggerMode {

    /** 每日定时运行，执行时间取自 config.time */
    SCHEDULED("定时运行"),

    /** 服务器启动后按列表顺序依次运行一次 */
    STARTUP("启动运行");

    private final String displayName;

    TriggerMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** 宽松解析：null / 空串 / 未知值均回退为 {@link #SCHEDULED} */
    public static TriggerMode from(String raw) {
        if (raw == null || raw.isBlank()) {
            return SCHEDULED;
        }
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return SCHEDULED;
        }
    }
}
