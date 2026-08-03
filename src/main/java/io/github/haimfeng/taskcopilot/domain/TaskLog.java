package io.github.haimfeng.taskcopilot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 任务执行日志。
 */
@Entity
@Table(name = "task_log", indexes = {
        @Index(name = "idx_task_log_task_id", columnList = "task_id"),
        @Index(name = "idx_task_log_started_at", columnList = "started_at")
})
@Getter
@Setter
public class TaskLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联任务 ID（弱关联，任务删除后日志可按需清理） */
    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 触发来源：SCHEDULED / MANUAL */
    @Column(name = "trigger_source", nullable = false, length = 20)
    private String triggerSource;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    /** 进程退出码，超时为 -1 */
    @Column(name = "exit_code")
    private Integer exitCode;

    @Lob
    @Column(name = "stdout")
    private String stdout;

    @Lob
    @Column(name = "stderr")
    private String stderr;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExecutionStatus status;
}
