package io.github.haimfeng.taskcopilot.web.dto;

import io.github.haimfeng.taskcopilot.tasktype.FieldSchema;

import java.util.List;

/**
 * 任务类型出参。字段命名与前端 {@code app.js} 保持一致：
 * {@code typeCode} / {@code typeDisplayName} / {@code description} / {@code configSchema}。
 */
public record TaskTypeResponse(
        String typeCode,
        String typeDisplayName,
        String description,
        List<FieldSchema> configSchema
) {
}
