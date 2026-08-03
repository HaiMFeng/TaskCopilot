package io.github.haimfeng.taskcopilot.tasktype;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 任务类型注册表，自动收集容器中所有 {@link TaskTypeHandler}。
 */
@Component
public class TaskTypeRegistry {

    private final Map<String, TaskTypeHandler> handlers = new LinkedHashMap<>();

    public TaskTypeRegistry(List<TaskTypeHandler> discovered) {
        discovered.stream()
                .sorted((a, b) -> a.code().compareTo(b.code()))
                .forEach(h -> handlers.put(h.code(), h));
    }

    public Optional<TaskTypeHandler> find(String code) {
        return Optional.ofNullable(handlers.get(code));
    }

    public TaskTypeHandler require(String code) {
        return find(code).orElseThrow(
                () -> new IllegalArgumentException("未知的任务类型: " + code));
    }

    public List<TaskTypeHandler> all() {
        return List.copyOf(handlers.values());
    }
}
