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
     * <p>
     * 排序规则：启动运行任务整体置顶（组内完全由用户拖拽的 sortOrder 决定），
     * 定时运行任务在下（先按触发时间升序，时间相同的再按 sortOrder）。
     */
    @Transactional(readOnly = true)
    public List<TaskResponse> listBySchedule(Long scheduleId) {
        return taskRepository.findByScheduleIdOrderBySortOrderAscIdAsc(scheduleId).stream()
                .sorted(taskListComparator())
                .map(this::toResponse)
                .toList();
    }

    /**
     * 任务列表统一排序器：启动任务在前且仅按手动顺序，定时任务在后且以触发时间为主序。
     */
    private java.util.Comparator<Task> taskListComparator() {
        return java.util.Comparator
                .comparingInt((Task t) -> t.isStartupTask() ? 0 : 1)
                .thenComparingInt(this::groupMinuteOf)
                .thenComparingInt(Task::getSortOrder)
                .thenComparing(Task::getId);
    }

    /**
     * 分组内的主排序键。
     * <ul>
     *     <li>启动任务不参与时间排序，统一返回 0，完全由 sortOrder 决定次序；</li>
     *     <li>定时任务返回触发时间对应的当日分钟数。</li>
     * </ul>
     */
    private int groupMinuteOf(Task task) {
        return task.isStartupTask() ? 0 : triggerMinuteOf(task);
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
     * 保存任务顺序。列表分为「启动运行」「定时运行」两个区段，拖拽受以下约束：
     * <ul>
     *     <li>启动任务恒在定时任务之前，二者不能互换区段；</li>
     *     <li>启动任务组内可任意排序；</li>
     *     <li>定时任务以触发时间为主序，只能调整同一触发时间内的相对顺序。</li>
     * </ul>
     */
    @Transactional
    public void reorder(List<Long> orderedIds) {
        List<Task> tasks = taskRepository.findAllById(orderedIds);
        Map<Long, Task> byId = new java.util.HashMap<>();
        tasks.forEach(t -> byId.put(t.getId(), t));

        // 校验：按传入顺序，分组序号与组内时间键都必须非递减，否则说明发生了跨区段/跨时间拖拽
        int previousGroup = Integer.MIN_VALUE;
        int previousMinute = Integer.MIN_VALUE;
        for (Long id : orderedIds) {
            Task task = byId.get(id);
            if (task == null) {
                continue;
            }
            int group = task.isStartupTask() ? 0 : 1;
            if (group < previousGroup) {
                throw new IllegalArgumentException("启动运行任务与定时运行任务之间不能调整顺序");
            }
            // 进入新分组时重置时间基准，避免用上一组的时间做比较
            if (group > previousGroup) {
                previousGroup = group;
                previousMinute = Integer.MIN_VALUE;
            }
            int minute = groupMinuteOf(task);
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
        // 运行方式：未传或非法值一律回退为定时运行，兼容旧客户端
        task.setTriggerMode(io.github.haimfeng.taskcopilot.domain.TriggerMode.from(request.triggerMode()));
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
                triggerSummaryOf(task, handler, config),
                task.resolveTriggerMode().name(),
                task.resolveTriggerMode().displayName(),
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

    /**
     * 触发方式摘要。启动任务不按时间触发，直接给出固定文案；
     * 定时任务仍由任务类型 handler 生成（如 "每日 08:30"）。
     */
    private String triggerSummaryOf(Task task, Optional<TaskTypeHandler> handler, Map<String, Object> config) {
        if (task.isStartupTask()) {
            return "服务器启动后运行";
        }
        return handler.map(h -> h.summary(config)).orElse("未知类型");
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
