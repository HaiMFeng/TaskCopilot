package io.github.haimfeng.taskcopilot.service;

import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务触发配置的 JSON 序列化 / 反序列化。
 */
@Component
public class TaskConfigCodec {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public TaskConfigCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> read(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception e) {
            throw new IllegalArgumentException("任务配置解析失败: " + e.getMessage(), e);
        }
    }

    public String write(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config == null ? Map.of() : config);
        } catch (Exception e) {
            throw new IllegalArgumentException("任务配置序列化失败: " + e.getMessage(), e);
        }
    }
}
