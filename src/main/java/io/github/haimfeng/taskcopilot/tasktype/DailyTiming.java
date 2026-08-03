package io.github.haimfeng.taskcopilot.tasktype;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * 每日定时的时间解析工具。
 * <p>
 * 日程表中的所有任务都按"每日定时"语义调度，执行时间来自配置项 {@code time}
 * （格式 {@code HH:mm} 或 {@code HH:mm:ss}）。本工具与任务的功能类别（运行指令 /
 * 打开应用 / 发送请求）无关，由调度器统一调用。
 */
public final class DailyTiming {

    public static final String TIME_FIELD = "time";

    private DailyTiming() {
    }

    /** 解析配置里的执行时间，缺省为 08:30 */
    public static int[] parseTime(Map<String, Object> config) {
        if (config == null) {
            return new int[]{8, 30};
        }
        Object raw = config.get(TIME_FIELD);
        if (raw instanceof String text && !text.isBlank()) {
            String t = text.trim();
            try {
                String[] parts = t.split(":");
                int h = Integer.parseInt(parts[0]);
                int m = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                return new int[]{h, m};
            } catch (NumberFormatException ignored) {
                // 继续走兼容解析
            }
        }
        int hour = intValue(config, "hour", 8);
        int minute = intValue(config, "minute", 30);
        return new int[]{hour, minute};
    }

    /** 计算给定基准时间之后的下一次每日触发时间 */
    public static Optional<Instant> nextExecution(Map<String, Object> config, Instant from) {
        int[] hm = parseTime(config);
        int hour = hm[0];
        int minute = hm[1];
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime base = from.atZone(zone);
        ZonedDateTime candidate = ZonedDateTime.of(
                LocalDate.from(base), LocalTime.of(hour, minute), zone);
        if (!candidate.toInstant().isAfter(from)) {
            candidate = candidate.plusDays(1);
        }
        return Optional.of(candidate.toInstant());
    }

    /** 人类可读描述，例如 "每日 08:30" */
    public static String summary(Map<String, Object> config) {
        int[] hm = parseTime(config);
        return "每日 %02d:%02d".formatted(hm[0], hm[1]);
    }

    private static int intValue(Map<String, Object> config, String key, int fallback) {
        Object value = config.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
