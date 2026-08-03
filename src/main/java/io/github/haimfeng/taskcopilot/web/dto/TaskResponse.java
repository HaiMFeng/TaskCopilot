package io.github.haimfeng.taskcopilot.web.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 任务列表 / 详情的出参。
 */
public record TaskResponse(
        Long id,
        String name,
        String command,
        String workingDir,
        String typeCode,
        String typeName,
        Map<String, Object> config,
        String triggerSummary,
        boolean enabled,
        int sortOrder,
        Integer timeoutSeconds,
        String remark,
        Instant lastExecutedAt,
        String lastStatus,
        Instant nextExecutionAt,
        Instant createdAt,
        Instant updatedAt
) {
}
