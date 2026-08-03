package io.github.haimfeng.taskcopilot.tasktype;

import io.github.haimfeng.taskcopilot.domain.Task;
import io.github.haimfeng.taskcopilot.service.CommandExecutor;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 运行指令：执行一段 shell 命令（支持 |、>、&& 等）。
 * <p>
 * 配置项直接映射到任务的通用字段：command（命令）、workingDir（工作目录）、
 * timeoutSeconds（超时）；time 为每日定时触发时间。
 */
@Component
public class RunCommandTaskTypeHandler implements TaskTypeHandler {

    @Override
    public String code() {
        return "RUN_COMMAND";
    }

    @Override
    public String displayName() {
        return "运行指令";
    }

    @Override
    public String description() {
        return "执行一段命令或脚本，支持管道、重定向等写法";
    }

    @Override
    public List<FieldSchema> configSchema() {
        return List.of(
                FieldSchema.textarea("command", "命令", "例如：ping 127.0.0.1 或 python main.py"),
                FieldSchema.text("workingDir", "工作目录", false, "可选，留空使用默认目录"),
                FieldSchema.number("timeoutSeconds", "超时（秒）", 1, 86400, 60),
                FieldSchema.time("time", "执行时间", "08:30")
        );
    }

    @Override
    public void validate(Map<String, Object> config) {
        Object command = config.get("command");
        if (!(command instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("运行指令内容不能为空");
        }
    }

    @Override
    public String summary(Map<String, Object> config) {
        String c = config.getOrDefault("command", "").toString();
        if (c.length() > 40) {
            c = c.substring(0, 40) + "…";
        }
        return "运行指令：" + c;
    }

    @Override
    public Optional<CommandExecutor.ExecutionResult> execute(
            Task task, Map<String, Object> config, CommandExecutor executor) {
        return Optional.of(executor.execute(task));
    }
}
