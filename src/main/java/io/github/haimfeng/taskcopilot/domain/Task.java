package io.github.haimfeng.taskcopilot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 任务定义。
 * <p>
 * 任务的触发方式由 {@code typeCode} + {@code configJson} 共同描述，
 * 具体解释权交给对应的 {@code TaskTypeHandler}，从而支持任务类型的横向扩展。
 */
@Entity
@Table(name = "task", indexes = {
        @Index(name = "idx_task_enabled", columnList = "enabled"),
        @Index(name = "idx_task_sort_order", columnList = "sort_order")
})
@Getter
@Setter
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务名称 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 要执行的命令或脚本 */
    @Lob
    @Column(nullable = false)
    private String command;

    /** 命令的工作目录，为空时使用应用进程当前目录 */
    @Column(name = "working_dir", length = 500)
    private String workingDir;

    /** 任务类型标识，例如 DAILY */
    @Column(name = "type_code", nullable = false, length = 32)
    private String typeCode;

    /** 触发配置（JSON），由对应任务类型解析 */
    @Lob
    @Column(name = "config_json")
    private String configJson;

    /** 是否启用 */
    @Column(nullable = false)
    private boolean enabled = true;

    /** 排序序号，值越小越靠前 */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    /** 超时秒数，为空时使用全局默认值 */
    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds;

    /** 备注 */
    @Column(length = 255)
    private String remark;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 所属日程表 id，任务通过日程表参与分组管理 */
    @Column(name = "schedule_id")
    private Long scheduleId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }
}
