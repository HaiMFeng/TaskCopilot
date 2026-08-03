package io.github.haimfeng.taskcopilot.web;

import io.github.haimfeng.taskcopilot.tasktype.TaskTypeHandler;
import io.github.haimfeng.taskcopilot.tasktype.TaskTypeRegistry;
import io.github.haimfeng.taskcopilot.web.dto.TaskTypeResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 暴露任务类型及其表单 schema，供前端动态渲染。
 */
@RestController
@RequestMapping("/api/task-types")
public class TaskTypeController {

    private final TaskTypeRegistry registry;

    public TaskTypeController(TaskTypeRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public List<TaskTypeResponse> list() {
        return registry.all().stream()
                .map(this::toResponse)
                .toList();
    }

    private TaskTypeResponse toResponse(TaskTypeHandler handler) {
        return new TaskTypeResponse(
                handler.code(),
                handler.displayName(),
                handler.description(),
                handler.configSchema());
    }
}
