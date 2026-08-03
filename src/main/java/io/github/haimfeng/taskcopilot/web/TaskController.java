package io.github.haimfeng.taskcopilot.web;

import io.github.haimfeng.taskcopilot.service.TaskService;
import io.github.haimfeng.taskcopilot.web.dto.SortRequest;
import io.github.haimfeng.taskcopilot.web.dto.TaskLogResponse;
import io.github.haimfeng.taskcopilot.web.dto.TaskRequest;
import io.github.haimfeng.taskcopilot.web.dto.TaskResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    public List<TaskResponse> list(@RequestParam(required = false) String keyword,
                                   @RequestParam(required = false) Boolean enabled,
                                   @RequestParam(required = false) String typeCode,
                                   @RequestParam(required = false) Long scheduleId) {
        if (scheduleId != null) {
            return taskService.listBySchedule(scheduleId);
        }
        return taskService.list(keyword, enabled, typeCode);
    }

    @GetMapping("/{id}")
    public TaskResponse get(@PathVariable Long id) {
        return taskService.get(id);
    }

    @PostMapping
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody TaskRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(taskService.create(request));
    }

    @PutMapping("/{id}")
    public TaskResponse update(@PathVariable Long id, @Valid @RequestBody TaskRequest request) {
        return taskService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/toggle")
    public TaskResponse toggle(@PathVariable Long id,
                               @RequestParam(required = false) Boolean enabled) {
        return taskService.toggle(id, enabled);
    }

    @PostMapping("/{id}/execute")
    public TaskLogResponse execute(@PathVariable Long id) {
        return taskService.executeNow(id);
    }

    @PutMapping("/sort")
    public ResponseEntity<Void> sort(@Valid @RequestBody SortRequest request) {
        taskService.reorder(request.orderedIds());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/logs")
    public List<TaskLogResponse> logs(@PathVariable Long id,
                                      @RequestParam(defaultValue = "50") int limit) {
        return taskService.logs(id, limit);
    }
}
