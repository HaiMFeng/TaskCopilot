package io.github.haimfeng.taskcopilot.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建 / 更新日程表的请求体。
 */
public class ScheduleRequest {

    @NotBlank(message = "日程表名称不能为空")
    @Size(max = 100, message = "名称不能超过 100 个字符")
    private String name;

    @Size(max = 255, message = "备注不能超过 255 个字符")
    private String remark;

    private Boolean active;

    private Integer sortOrder;

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

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
