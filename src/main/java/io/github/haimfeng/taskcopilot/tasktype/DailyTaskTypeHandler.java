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
                FieldSchema.number("hour", "小时", 0, 23, 8),
                FieldSchema.number("minute", "分钟", 0, 59, 0)
        );
    }

    @Override
    public void validate(Map<String, Object> config) {
        int hour = intValue(config, "hour", -1);
        int minute = intValue(config, "minute", -1);
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException("小时必须在 0-23 之间");
        }
        if (minute < 0 || minute > 59) {
            throw new IllegalArgumentException("分钟必须在 0-59 之间");
        }
    }

    @Override
    public Optional<Instant> nextExecution(Map<String, Object> config, Instant from) {
        int hour = intValue(config, "hour", 0);
        int minute = intValue(config, "minute", 0);
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
        return "每日 %02d:%02d".formatted(intValue(config, "hour", 0), intValue(config, "minute", 0));
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
