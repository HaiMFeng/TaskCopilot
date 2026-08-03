package io.github.haimfeng.taskcopilot.web.dto;

import io.github.haimfeng.taskcopilot.domain.Schedule;

import java.time.Instant;

/**
 * 日程表视图对象，附带其下任务数量与是否运行中的派生信息。
 */
public class ScheduleResponse {

    private Long id;
    private String name;
    private String remark;
    private boolean active;
    private int sortOrder;
    private int taskCount;
    private Instant createdAt;
    private Instant updatedAt;

    public static ScheduleResponse from(Schedule schedule, int taskCount) {
        ScheduleResponse r = new ScheduleResponse();
        r.id = schedule.getId();
        r.name = schedule.getName();
        r.remark = schedule.getRemark();
        r.active = schedule.isActive();
        r.sortOrder = schedule.getSortOrder();
        r.taskCount = taskCount;
        r.createdAt = schedule.getCreatedAt();
        r.updatedAt = schedule.getUpdatedAt();
        return r;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public int getTaskCount() {
        return taskCount;
    }

    public void setTaskCount(int taskCount) {
        this.taskCount = taskCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
