package io.github.haimfeng.taskcopilot.service;

import io.github.haimfeng.taskcopilot.domain.Task;
import io.github.haimfeng.taskcopilot.domain.TaskLog;
import io.github.haimfeng.taskcopilot.repository.TaskLogRepository;
import io.github.haimfeng.taskcopilot.repository.TaskRepository;
import io.github.haimfeng.taskcopilot.tasktype.TaskTypeHandler;
import io.github.haimfeng.taskcopilot.tasktype.TaskTypeRegistry;
import io.github.haimfeng.taskcopilot.web.dto.TaskLogResponse;
import io.github.haimfeng.taskcopilot.web.dto.TaskRequest;
import io.github.haimfeng.taskcopilot.web.dto.TaskResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 任务业务逻辑：CRUD、排序、手动执行、日志查询。
 */
@Service
public class TaskService {

    private final TaskRepository taskRepository;
    private final TaskLogRepository taskLogRepository;
    private final TaskTypeRegistry taskTypeRegistry;
    private final TaskConfigCodec configCodec;
    private final TaskScheduler taskScheduler;
    private final TaskExecutionService executionService;

    public TaskService(TaskRepository taskRepository,
                       TaskLogRepository taskLogRepository,
                       TaskTypeRegistry taskTypeRegistry,
                       TaskConfigCodec configCodec,
                       TaskScheduler taskScheduler,
                       TaskExecutionService executionService) {
        this.taskRepository = taskRepository;
        this.taskLogRepository = taskLogRepository;
        this.taskTypeRegistry = taskTypeRegistry;
        this.configCodec = configCodec;
        this.taskScheduler = taskScheduler;
        this.executionService = executionService;
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> list(String keyword, Boolean enabled, String typeCode) {
        String kw = keyword == null ? null : keyword.trim().toLowerCase(Locale.ROOT);
        return taskRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .filter(t -> enabled == null || t.isEnabled() == enabled)
                .filter(t -> typeCode == null || typeCode.isBlank() || typeCode.equals(t.getTypeCode()))
                .filter(t -> kw == null || kw.isEmpty()
                        || t.getName().toLowerCase(Locale.ROOT).contains(kw)
                        || (t.getCommand() != null && t.getCommand().toLowerCase(Locale.ROOT).contains(kw))
                        || (t.getRemark() != null && t.getRemark().toLowerCase(Locale.ROOT).contains(kw)))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public TaskResponse get(Long id) {
        return toResponse(requireTask(id));
    }

    /**
     * 查询指定日程表下的任务（按 sortOrder 升序）。
     */
    @Transactional(readOnly = true)
    public List<TaskResponse> listBySchedule(Long scheduleId) {
        return taskRepository.findByScheduleIdOrderBySortOrderAscIdAsc(scheduleId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public TaskResponse create(TaskRequest request) {
        TaskTypeHandler handler = taskTypeRegistry.require(request.typeCode());
        Map<String, Object> config = request.config() == null ? Map.of() : request.config();
        handler.validate(config);

        Task task = new Task();
        apply(task, request, config);
        task.setSortOrder(nextSortOrder());
        Task saved = taskRepository.save(task);
        taskScheduler.schedule(saved);
        return toResponse(saved);
    }

    @Transactional
    public TaskResponse update(Long id, TaskRequest request) {
        Task task = requireTask(id);
        TaskTypeHandler handler = taskTypeRegistry.require(request.typeCode());
        Map<String, Object> config = request.config() == null ? Map.of() : request.config();
        handler.validate(config);

        apply(task, request, config);
        Task saved = taskRepository.save(task);
        taskScheduler.schedule(saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        Task task = requireTask(id);
        taskScheduler.unschedule(id);
        taskLogRepository.deleteByTaskId(id);
        taskRepository.delete(task);
    }

    @Transactional
    public TaskResponse toggle(Long id, Boolean enabled) {
        Task task = requireTask(id);
        task.setEnabled(enabled != null ? enabled : !task.isEnabled());
        Task saved = taskRepository.save(task);
        if (saved.isEnabled()) {
            taskScheduler.schedule(saved);
        } else {
            taskScheduler.unschedule(saved.getId());
        }
        return toResponse(saved);
    }

    @Transactional
    public void reorder(List<Long> orderedIds) {
        List<Task> tasks = taskRepository.findAllById(orderedIds);
        Map<Long, Task> byId = new java.util.HashMap<>();
        tasks.forEach(t -> byId.put(t.getId(), t));
        int order = 0;
        List<Task> updated = new ArrayList<>();
        for (Long id : orderedIds) {
            Task task = byId.get(id);
            if (task != null) {
                task.setSortOrder(order++);
                updated.add(task);
            }
        }
        taskRepository.saveAll(updated);
    }

    /**
     * 手动执行一次（同步等待结果，便于前端立即看到输出）。
     */
    @Transactional
    public TaskLogResponse executeNow(Long id) {
        Task task = requireTask(id);
        TaskLog log = executionService.execute(task, TaskExecutionService.TRIGGER_MANUAL);
        return toLogResponse(log);
    }

    @Transactional(readOnly = true)
    public List<TaskLogResponse> logs(Long taskId, int limit) {
        requireTask(taskId);
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        return taskLogRepository.findByTaskIdOrderByStartedAtDesc(taskId, PageRequest.of(0, safeLimit))
                .stream().map(this::toLogResponse).toList();
    }

    private Task requireTask(Long id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + id));
    }

    private void apply(Task task, TaskRequest request, Map<String, Object> config) {
        task.setName(request.name().trim());
        task.setCommand(request.command());
        task.setWorkingDir(blankToNull(request.workingDir()));
        task.setTypeCode(request.typeCode());
        task.setConfigJson(configCodec.write(config));
        task.setTimeoutSeconds(request.timeoutSeconds());
        task.setRemark(blankToNull(request.remark()));
        task.setScheduleId(request.scheduleId());
        if (request.enabled() != null) {
            task.setEnabled(request.enabled());
        }
    }

    private int nextSortOrder() {
        return taskRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .mapToInt(Task::getSortOrder).max().orElse(-1) + 1;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private TaskResponse toResponse(Task task) {
        Map<String, Object> config = configCodec.read(task.getConfigJson());
        Optional<TaskTypeHandler> handler = taskTypeRegistry.find(task.getTypeCode());
        Optional<TaskLog> lastLog = taskLogRepository.findFirstByTaskIdOrderByStartedAtDesc(task.getId());
        Instant next = taskScheduler.nextExecutionOf(task.getId());

        return new TaskResponse(
                task.getId(),
                task.getName(),
                task.getCommand(),
                task.getWorkingDir(),
                task.getTypeCode(),
                handler.map(TaskTypeHandler::displayName).orElse(task.getTypeCode()),
                config,
                handler.map(h -> h.summary(config)).orElse("未知类型"),
                task.isEnabled(),
                task.getSortOrder(),
                task.getTimeoutSeconds(),
                task.getRemark(),
                lastLog.map(TaskLog::getStartedAt).orElse(null),
                lastLog.map(l -> l.getStatus().name()).orElse(null),
                lastLog.map(TaskLog::getExitCode).orElse(null),
                lastLog.map(TaskLog::getStdout).orElse(null),
                lastLog.map(TaskLog::getStderr).orElse(null),
                next,
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    private TaskLogResponse toLogResponse(TaskLog log) {
        return new TaskLogResponse(
                log.getId(),
                log.getTaskId(),
                log.getTriggerSource(),
                log.getStartedAt(),
                log.getFinishedAt(),
                log.getExitCode(),
                log.getStdout(),
                log.getStderr(),
                log.getStatus().name()
        );
    }
}
