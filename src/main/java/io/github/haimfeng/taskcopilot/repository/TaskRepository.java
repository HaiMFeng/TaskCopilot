package io.github.haimfeng.taskcopilot.repository;

import io.github.haimfeng.taskcopilot.domain.Task;
import io.github.haimfeng.taskcopilot.domain.TriggerMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findAllByOrderBySortOrderAscIdAsc();

    List<Task> findByEnabledTrueOrderBySortOrderAscIdAsc();

    List<Task> findByScheduleId(Long scheduleId);

    List<Task> findByScheduleIdOrderBySortOrderAscIdAsc(Long scheduleId);

    List<Task> findByScheduleIdAndEnabledTrueOrderBySortOrderAscIdAsc(Long scheduleId);

    long countByScheduleId(Long scheduleId);

    /** 按运行方式查询启用中的任务，用于启动任务的顺序执行 */
    List<Task> findByTriggerModeAndEnabledTrueOrderBySortOrderAscIdAsc(TriggerMode triggerMode);

    /** 旧版本数据（trigger_mode 为 null）的数量，用于判断是否需要迁移 */
    long countByTriggerModeIsNull();

    /** 将旧版本数据统一回填为定时运行 */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update Task t set t.triggerMode = :mode where t.triggerMode is null")
    int backfillTriggerMode(@Param("mode") TriggerMode mode);

    long countByEnabledTrue();

    long count();
}
