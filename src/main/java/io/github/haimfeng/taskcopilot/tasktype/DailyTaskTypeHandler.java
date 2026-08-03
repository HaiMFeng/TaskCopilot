package io.github.haimfeng.taskcopilot.tasktype;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 每日定时任务：{@code {"hour":8,"minute":30}}。
 */
@Component
public class DailyTaskTypeHandler implements TaskTypeHandler {

    public static final String CODE = "DAILY";

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String displayName() {
        return "每日定时";
    }

    @Override
    public String description() {
        return "每天在指定的时:分执行一次";
    }

    @Override
    public List<FieldSchema> configSchema() {
        return List.of(
                FieldSchema.time("time", "执行时间", "08:30")
        );
    }

    @Override
    public void validate(Map<String, Object> config) {
        int[] hm = parseTime(config);
        if (hm[0] < 0 || hm[0] > 23) {
            throw new IllegalArgumentException("小时必须在 0-23 之间");
        }
        if (hm[1] < 0 || hm[1] > 59) {
            throw new IllegalArgumentException("分钟必须在 0-59 之间");
        }
    }

    @Override
    public Optional<Instant> nextExecution(Map<String, Object> config, Instant from) {
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

    @Override
    public String summary(Map<String, Object> config) {
        int[] hm = parseTime(config);
        return "每日 %02d:%02d".formatted(hm[0], hm[1]);
    }

    /**
     * 解析执行时间。优先读取 {@code time} 字段（"HH:mm" 或 "HH:mm:ss"），
     * 兼容旧版 {@code hour}/{@code minute} 两个独立字段。
     */
    private static int[] parseTime(Map<String, Object> config) {
        if (config == null) {
            return new int[]{8, 30};
        }
        Object raw = config.get("time");
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

    private static int intValue(Map<String, Object> config, String key, int fallback) {
        if (config == null) {
            return fallback;
        }
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
