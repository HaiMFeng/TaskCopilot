package io.github.haimfeng.taskcopilot.repository;

import io.github.haimfeng.taskcopilot.domain.TaskLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TaskLogRepository extends JpaRepository<TaskLog, Long> {

    List<TaskLog> findByTaskIdOrderByStartedAtDesc(Long taskId, Pageable pageable);

    Optional<TaskLog> findFirstByTaskIdOrderByStartedAtDesc(Long taskId);

    long countByTaskId(Long taskId);

    void deleteByTaskId(Long taskId);

    /**
     * 删除指定任务中 id 小于等于阈值的历史日志（用于按条数保留）。
     */
    @Modifying
    @Query("delete from TaskLog l where l.taskId = :taskId and l.id <= :maxId")
    int deleteByTaskIdAndIdLessThanEqual(@Param("taskId") Long taskId, @Param("maxId") Long maxId);
}
