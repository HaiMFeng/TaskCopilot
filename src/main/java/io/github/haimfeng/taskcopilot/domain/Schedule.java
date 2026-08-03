package io.github.haimfeng.taskcopilot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 日程表（Schedule）。
 * <p>
 * 用于把任务按「场景 / 计划」分组。例如「工作日计划」「周末计划」。
 * 同一时间只能有一个日程表处于 {@code active} 状态并真正参与调度，
 * 其余日程表下的任务不会被自动触发（但仍可手动执行）。
 * <p>
 * 该实体刻意保持轻量，仅承载分组与激活语义；具体的触发逻辑仍由任务自身的
 * {@code typeCode} + {@code config} 决定，从而与任务类型扩展机制解耦。
 */
@Entity
@Table(name = "schedule", indexes = {
        @Index(name = "idx_schedule_active", columnList = "active")
})
@Getter
@Setter
public class Schedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 日程表名称，例如「工作日计划」 */
    @Column(nullable = false, length = 100)
    private String name;

    /** 备注 / 描述 */
    @Column(length = 255)
    private String remark;

    /** 是否为当前生效（运行）的日程表，全局唯一 */
    @Column(nullable = false)
    private boolean active = false;

    /** 排序序号，值越小越靠前（用于日程表列表展示） */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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
