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
        /** 运行方式：SCHEDULED / STARTUP */
        String triggerMode,
        /** 运行方式的中文名，便于前端直接展示 */
        String triggerModeName,
        boolean enabled,
        int sortOrder,
        Integer timeoutSeconds,
        String remark,
        Instant lastExecutedAt,
        String lastStatus,
        Integer lastExitCode,
        String lastStdout,
        String lastStderr,
        Instant nextExecutionAt,
        Instant createdAt,
        Instant updatedAt
) {
}
