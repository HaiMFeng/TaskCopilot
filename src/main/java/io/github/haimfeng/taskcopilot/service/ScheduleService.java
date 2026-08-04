package io.github.haimfeng.taskcopilot.service;

import io.github.haimfeng.taskcopilot.domain.Schedule;
import io.github.haimfeng.taskcopilot.domain.Task;
import io.github.haimfeng.taskcopilot.repository.ScheduleRepository;
import io.github.haimfeng.taskcopilot.repository.TaskLogRepository;
import io.github.haimfeng.taskcopilot.repository.TaskRepository;
import io.github.haimfeng.taskcopilot.service.TaskScheduler;
import io.github.haimfeng.taskcopilot.web.dto.ScheduleRequest;
import io.github.haimfeng.taskcopilot.web.dto.ScheduleResponse;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 日程表（Schedule）的增删改查与「互斥激活」管理。
 *
 * 业务约束：同一时间只能有一个日程表处于 active（运行中）。
 * 当把某个日程表设为 active 时，其余日程表会被自动置为非 active。
 */
@Service
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final TaskRepository taskRepository;
    private final TaskLogRepository taskLogRepository;
    private final TaskScheduler taskScheduler;

    public ScheduleService(ScheduleRepository scheduleRepository,
                           TaskRepository taskRepository,
                           TaskLogRepository taskLogRepository,
                           TaskScheduler taskScheduler) {
        this.scheduleRepository = scheduleRepository;
        this.taskRepository = taskRepository;
        this.taskLogRepository = taskLogRepository;
        this.taskScheduler = taskScheduler;
    }

    /**
     * 返回所有日程表（按 sortOrder 升序），附任务数。
     */
    @Transactional(readOnly = true)
    public List<ScheduleResponse> list() {
        return scheduleRepository.findAllByOrderBySortOrderAscIdAsc().stream()
                .map(s -> ScheduleResponse.from(s, (int) taskRepository.countByScheduleId(s.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ScheduleResponse get(Long id) {
        return ScheduleResponse.from(requireSchedule(id),
                (int) taskRepository.countByScheduleId(id));
    }

    @Transactional
    public ScheduleResponse create(ScheduleRequest req) {
        Schedule s = new Schedule();
        s.setName(req.getName());
        s.setRemark(req.getRemark());
        s.setSortOrder(req.getSortOrder() == null ? nextSortOrder() : req.getSortOrder());
        boolean active = Boolean.TRUE.equals(req.getActive());
        if (active) {
            deactivateAll();
        }
        s.setActive(active);
        Schedule saved = scheduleRepository.save(s);
        if (active) {
            taskScheduler.reloadAll();
        }
        return ScheduleResponse.from(saved, 0);
    }

    @Transactional
    public ScheduleResponse update(Long id, ScheduleRequest req) {
        Schedule s = requireSchedule(id);
        if (req.getName() != null) {
            s.setName(req.getName());
        }
        if (req.getRemark() != null) {
            s.setRemark(req.getRemark());
        }
        if (req.getSortOrder() != null) {
            s.setSortOrder(req.getSortOrder());
        }
        boolean becameActive = false;
        if (req.getActive() != null) {
            if (req.getActive() && !s.isActive()) {
                deactivateAll();
                becameActive = true;
            }
            s.setActive(req.getActive());
        }
        ScheduleResponse resp = ScheduleResponse.from(scheduleRepository.save(s),
                (int) taskRepository.countByScheduleId(id));
        if (becameActive) {
            taskScheduler.reloadAll();
        }
        return resp;
    }

    /**
     * 删除日程表，并级联删除其下的所有任务（含调度注册与执行日志）。
     */
    @Transactional
    public void delete(Long id) {
        Schedule s = requireSchedule(id);
        // 级联删除该日程表下的任务：先解除调度并清理日志，再删除任务本身
        List<Task> tasks = taskRepository.findByScheduleId(id);
        for (Task t : tasks) {
            taskScheduler.unschedule(t.getId());
            taskLogRepository.deleteByTaskId(t.getId());
        }
        if (!tasks.isEmpty()) {
            taskRepository.deleteAll(tasks);
        }
        scheduleRepository.delete(s);
        // 若删除的是运行中的日程表，需要重新排程以清除残留
        if (s.isActive()) {
            taskScheduler.reloadAll();
        }
    }

    /**
     * 设置某个日程表为唯一运行中的日程表。
     */
    @Transactional
    public ScheduleResponse activate(Long id) {
        requireSchedule(id);
        deactivateAll();
        Schedule s = requireSchedule(id);
        s.setActive(true);
        ScheduleResponse resp = ScheduleResponse.from(scheduleRepository.save(s),
                (int) taskRepository.countByScheduleId(id));
        // 运行中日程表发生变化，需要重新排程
        taskScheduler.reloadAll();
        return resp;
    }

    /**
     * 停用所有日程表（不启用任何日程表）。
     */
    @Transactional
    public void deactivate() {
        deactivateAll();
        taskScheduler.reloadAll();
    }

    /**
     * 返回当前生效的日程表；若不存在则确保存在一个默认日程表。
     */
    @Transactional
    public ScheduleResponse currentOrDefault() {
        return scheduleRepository.findFirstByActiveTrue()
                .map(s -> ScheduleResponse.from(s, (int) taskRepository.countByScheduleId(s.getId())))
                .orElseGet(() -> {
                    List<Schedule> all = scheduleRepository.findAllByOrderBySortOrderAscIdAsc();
                    if (all.isEmpty()) {
                        Schedule def = new Schedule();
                        def.setName("默认日程表");
                        def.setActive(true);
                        def.setSortOrder(0);
                        Schedule saved = scheduleRepository.save(def);
                        return ScheduleResponse.from(saved, 0);
                    }
                    Schedule first = all.getFirst();
                    first.setActive(true);
                    return ScheduleResponse.from(scheduleRepository.save(first),
                            (int) taskRepository.countByScheduleId(first.getId()));
                });
    }

    private Schedule requireSchedule(Long id) {
        return scheduleRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("日程表不存在: " + id));
    }

    private void deactivateAll() {
        scheduleRepository.findByActiveTrue().forEach(s -> s.setActive(false));
    }

    private int nextSortOrder() {
        return scheduleRepository.findAllByOrderBySortOrderAscIdAsc().size();
    }
}
