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
import java.util.Objects;
import java.util.List;
import java.util.LinkedHashMap;
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
     * 查询指定日程表下的任务。
     * 先按触发时间升序，时间相同的再按 sortOrder（用户手动拖拽的顺序）排列。
     */
    @Transactional(readOnly = true)
    public List<TaskResponse> listBySchedule(Long scheduleId) {
        return taskRepository.findByScheduleIdOrderBySortOrderAscIdAsc(scheduleId).stream()
                .sorted(java.util.Comparator
                        .comparingInt((Task t) -> triggerMinuteOf(t))
                        .thenComparingInt(Task::getSortOrder)
                        .thenComparing(Task::getId))
                .map(this::toResponse)
                .toList();
    }

    /**
     * 任务触发时间对应的当日分钟数，用于列表排序。
     * 时间统一存放在 config.time（HH:mm），解析失败时回退到 DailyTiming 的缺省值。
     */
    private int triggerMinuteOf(Task task) {
        int[] hm = io.github.haimfeng.taskcopilot.tasktype.DailyTiming
                .parseTime(configCodec.read(task.getConfigJson()));
        return hm[0] * 60 + hm[1];
    }

    @Transactional
    public TaskResponse create(TaskRequest request) {
        TaskTypeHandler handler = taskTypeRegistry.require(request.typeCode());
        Map<String, Object> config = request.config() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.config());
        // 顶层通用字段（command/workingDir/timeoutSeconds）对运行指令类型而言即配置项，
        // 合并进 config 使 handler 的校验/执行与前端 schema 保持一致。
        mergeTopLevelToConfig(request, config);
        handler.validate(config);
        assertRunCommandContent(request, config);

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
        Map<String, Object> config = request.config() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.config());
        mergeTopLevelToConfig(request, config);
        handler.validate(config);
        assertRunCommandContent(request, config);

        // 关键执行要素变更（命令/类型/工作目录/配置）时，旧的运行结果已与新任务不再相关，清空之。
        String newConfigJson = configCodec.write(config);
        boolean executionChanged = !Objects.equals(task.getCommand(), request.command())
                || !Objects.equals(task.getTypeCode(), request.typeCode())
                || !Objects.equals(task.getWorkingDir(), request.workingDir())
                || !Objects.equals(task.getConfigJson(), newConfigJson);
        if (executionChanged) {
            taskLogRepository.deleteByTaskId(id);
        }

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

    /**
     * 保存任务顺序。列表以触发时间为主序，因此这里只允许调整「同一触发时间」组内的相对顺序：
     * 传入的顺序若改变了各时间组之间的先后，则视为非法请求。
     */
    @Transactional
    public void reorder(List<Long> orderedIds) {
        List<Task> tasks = taskRepository.findAllById(orderedIds);
        Map<Long, Task> byId = new java.util.HashMap<>();
        tasks.forEach(t -> byId.put(t.getId(), t));

        // 校验：按传入顺序取出触发时间，必须是非递减的，否则说明跨时间拖拽
        int previousMinute = Integer.MIN_VALUE;
        for (Long id : orderedIds) {
            Task task = byId.get(id);
            if (task == null) {
                continue;
            }
            int minute = triggerMinuteOf(task);
            if (minute < previousMinute) {
                throw new IllegalArgumentException("只能调整同一执行时间任务之间的顺序");
            }
            previousMinute = minute;
        }

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

    /**
     * 将顶层通用字段（command/workingDir/timeoutSeconds）合并进 config。
     * 运行指令等类型以这些字段作为配置项，确保 handler 的校验、执行与前端 schema 使用同一份数据。
     */
    private void mergeTopLevelToConfig(TaskRequest request, Map<String, Object> config) {
        if (request.command() != null) {
            config.putIfAbsent("command", request.command());
        }
        if (request.workingDir() != null) {
            config.putIfAbsent("workingDir", request.workingDir());
        }
        if (request.timeoutSeconds() != null) {
            config.putIfAbsent("timeoutSeconds", request.timeoutSeconds());
        }
    }

    /** 运行指令类型必须提供命令内容（以 config.command 为真相源，与 handler.validate 一致） */
    private void assertRunCommandContent(TaskRequest request, Map<String, Object> config) {
        if ("RUN_COMMAND".equals(request.typeCode())) {
            Object cmd = config.get("command");
            if (!(cmd instanceof String s) || s.isBlank()) {
                throw new IllegalArgumentException("运行指令内容不能为空");
            }
        }
    }

    private void apply(Task task, TaskRequest request, Map<String, Object> config) {
        task.setName(blankToNull(request.name()));
        // 运行指令等类型以 config 内的 command/workingDir/timeoutSeconds 为真相源，
        // 顶层字段同步写入实体，保证列表展示与执行器读取一致。
        Object cfgCommand = config.get("command");
        // command 列在数据库中为 NOT NULL。运行指令类型由用户在 config 中提供；
        // 其它类型（打开应用/HTTP 请求）的命令由对应 handler 在执行时根据 config 动态生成，
        // 此处用一个占位非空值满足约束，不影响实际执行逻辑。
        String command = cfgCommand instanceof String s && !s.isBlank() ? s : request.command();
        task.setCommand(command != null && !command.isBlank() ? command : "noop");
        Object cfgWorkdir = config.get("workingDir");
        task.setWorkingDir(blankToNull(cfgWorkdir instanceof String s ? s : request.workingDir()));
        task.setTypeCode(request.typeCode());
        task.setConfigJson(configCodec.write(config));
        Object cfgTimeout = config.get("timeoutSeconds");
        task.setTimeoutSeconds(cfgTimeout instanceof Number n ? n.intValue() : request.timeoutSeconds());
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
