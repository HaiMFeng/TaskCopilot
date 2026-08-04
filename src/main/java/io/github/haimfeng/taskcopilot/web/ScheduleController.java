package io.github.haimfeng.taskcopilot.web;

import io.github.haimfeng.taskcopilot.service.ScheduleService;
import io.github.haimfeng.taskcopilot.web.dto.ScheduleRequest;
import io.github.haimfeng.taskcopilot.web.dto.ScheduleResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping
    public List<ScheduleResponse> list() {
        return scheduleService.list();
    }

    @GetMapping("/current")
    public ScheduleResponse current() {
        return scheduleService.current();
    }

    @GetMapping("/{id}")
    public ScheduleResponse get(@PathVariable Long id) {
        return scheduleService.get(id);
    }

    @PostMapping
    public ResponseEntity<ScheduleResponse> create(@Valid @RequestBody ScheduleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(scheduleService.create(req));
    }

    @PutMapping("/{id}")
    public ScheduleResponse update(@PathVariable Long id, @Valid @RequestBody ScheduleRequest req) {
        return scheduleService.update(id, req);
    }

    @PostMapping("/{id}/activate")
    public ScheduleResponse activate(@PathVariable Long id) {
        return scheduleService.activate(id);
    }

    @PostMapping("/deactivate")
    public ResponseEntity<Void> deactivate() {
        scheduleService.deactivate();
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        scheduleService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
