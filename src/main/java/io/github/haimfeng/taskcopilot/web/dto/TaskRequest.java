package io.github.haimfeng.taskcopilot.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 创建 / 更新任务的入参。
 */
public record TaskRequest(
        @NotBlank(message = "任务名称不能为空")
        @Size(max = 100, message = "任务名称不能超过 100 字")
        String name,

        @NotBlank(message = "命令不能为空")
        String command,

        @Size(max = 500)
        String workingDir,

        @NotBlank(message = "任务类型不能为空")
        String typeCode,

        /** 触发配置，由任务类型解析 */
        Map<String, Object> config,

        Boolean enabled,

        @Min(1) @Max(86400)
        Integer timeoutSeconds,

        @Size(max = 255)
        String remark,

        /** 所属日程表 id，可空表示未分组 */
        Long scheduleId
) {
}
