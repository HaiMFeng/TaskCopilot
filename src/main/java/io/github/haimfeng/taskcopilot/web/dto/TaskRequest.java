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

        /** 命令内容。运行指令类型必填，其它类型可为空（由配置项决定实际行为） */
        String command,

        @Size(max = 500)
        String workingDir,

        @NotBlank(message = "任务类型不能为空")
        String typeCode,

        /** 触发配置，由任务类型解析 */
        Map<String, Object> config,

        /**
         * 运行方式：SCHEDULED（定时运行）/ STARTUP（启动运行）。
         * 旧客户端不传该字段时按 SCHEDULED 处理，保持向上兼容。
         */
        String triggerMode,

        Boolean enabled,

        @Min(1) @Max(86400)
        Integer timeoutSeconds,

        @Size(max = 255)
        String remark,

        /** 所属日程表 id，可空表示未分组 */
        Long scheduleId
) {
}
