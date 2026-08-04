package io.github.haimfeng.taskcopilot.repository;

import io.github.haimfeng.taskcopilot.domain.Task;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findAllByOrderBySortOrderAscIdAsc();

    List<Task> findByEnabledTrueOrderBySortOrderAscIdAsc();

    List<Task> findByScheduleId(Long scheduleId);

    List<Task> findByScheduleIdOrderBySortOrderAscIdAsc(Long scheduleId);

    List<Task> findByScheduleIdAndEnabledTrueOrderBySortOrderAscIdAsc(Long scheduleId);

    long countByScheduleId(Long scheduleId);

    long countByEnabledTrue();

    long count();
}
