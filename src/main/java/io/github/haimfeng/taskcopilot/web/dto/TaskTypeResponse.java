package io.github.haimfeng.taskcopilot.web.dto;

import io.github.haimfeng.taskcopilot.tasktype.FieldSchema;

import java.util.List;

public record TaskTypeResponse(
        String code,
        String displayName,
        String description,
        List<FieldSchema> configSchema
) {
}
