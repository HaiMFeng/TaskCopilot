package io.github.haimfeng.taskcopilot.web.dto;

import java.time.Instant;

public record TaskLogResponse(
        Long id,
        Long taskId,
        String triggerSource,
        Instant startedAt,
        Instant finishedAt,
        Integer exitCode,
        String stdout,
        String stderr,
        String status
) {
}
