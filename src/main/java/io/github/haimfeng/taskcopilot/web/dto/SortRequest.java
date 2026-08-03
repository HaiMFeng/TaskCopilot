package io.github.haimfeng.taskcopilot.web.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 批量排序入参：按数组顺序重排 sort_order。
 */
public record SortRequest(
        @NotEmpty(message = "排序列表不能为空")
        List<Long> orderedIds
) {
}
