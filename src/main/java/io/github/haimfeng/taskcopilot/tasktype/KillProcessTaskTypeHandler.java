package io.github.haimfeng.taskcopilot.tasktype;

import io.github.haimfeng.taskcopilot.domain.ExecutionStatus;
import io.github.haimfeng.taskcopilot.domain.Task;
import io.github.haimfeng.taskcopilot.service.CommandExecutor;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 结束进程：通过进程名（如 notepad.exe）终止指定进程。
 * <p>
 * 配置项：processName（进程名）、matchMode（精确/模糊）、killMode（正常终止/强制终止）。
 * 执行时调用 Windows 的 {@code taskkill} 命令。
 */
@Component
public class KillProcessTaskTypeHandler implements TaskTypeHandler {

    @Override
    public String code() {
        return "KILL_PROCESS";
    }

    @Override
    public String displayName() {
        return "结束进程";
    }

    @Override
    public String description() {
        return "按进程名关闭指定程序，支持强制终止";
    }

    @Override
    public List<FieldSchema> configSchema() {
        return List.of(
                FieldSchema.process("processName", "进程名", "进程名，如 notepad.exe"),
                FieldSchema.select("matchMode", "匹配方式",
                        List.of(FieldSchema.option("exact", "精确匹配"), FieldSchema.option("wildcard", "模糊匹配（通配符）")),
                        "exact"),
                FieldSchema.select("killMode", "终止方式",
                        List.of(FieldSchema.option("normal", "正常终止"), FieldSchema.option("force", "强制终止（/F）")),
                        "normal"),
                FieldSchema.time("time", "执行时间", "08:30")
        );
    }

    @Override
    public void validate(Map<String, Object> config) {
        String processName = str(config, "processName");
        if (processName.isBlank()) {
            throw new IllegalArgumentException("请输入要结束的进程名");
        }
    }

    @Override
    public String summary(Map<String, Object> config) {
        return "结束进程：" + str(config, "processName");
    }

    @Override
    public Optional<CommandExecutor.ExecutionResult> execute(
            Task task, Map<String, Object> config, CommandExecutor executor) {
        String processName = str(config, "processName");
        String matchMode = str(config, "matchMode");
        String killMode = str(config, "killMode");

        boolean wildcard = "wildcard".equals(matchMode);
        boolean force = "force".equals(killMode);

        StringBuilder cmd = new StringBuilder("taskkill");
        if (force) {
            cmd.append(" /F");
        }
        cmd.append(" /IM ");
        // 模糊匹配时给进程名加上 * 通配符
        if (wildcard && !processName.startsWith("*") && !processName.endsWith("*")) {
            cmd.append('"').append('*').append(processName).append('*').append('"');
        } else {
            cmd.append('"').append(processName).append('"');
        }

        Task launch = new Task();
        launch.setCommand(cmd.toString());
        launch.setWorkingDir(task.getWorkingDir());
        launch.setTimeoutSeconds(task.getTimeoutSeconds());
        CommandExecutor.ExecutionResult result = executor.execute(launch);

        // taskkill 成功时 exitCode=0，补充友好提示
        if (result.status() == ExecutionStatus.SUCCESS) {
            String ok = "已请求终止进程：" + processName
                    + (force ? "（强制模式）" : "")
                    + System.lineSeparator()
                    + "taskkill 命令已执行；若进程仍在运行，请确认进程名正确或尝试强制终止。";
            String stdout = result.stdout().isBlank()
                    ? ok
                    : result.stdout() + System.lineSeparator() + ok;
            result = new CommandExecutor.ExecutionResult(
                    result.status(), result.exitCode(), stdout,
                    result.stderr(), result.startedAt(), result.finishedAt());
        }
        // taskkill 没找到进程时 exitCode=128，同样给出明确提示
        if (result.exitCode() == 128) {
            String err = result.stderr().isBlank() ? "" : result.stderr().stripTrailing() + System.lineSeparator();
            err += "未找到匹配的进程：" + processName;
            result = new CommandExecutor.ExecutionResult(
                    ExecutionStatus.FAILURE, result.exitCode(), result.stdout(),
                    err, result.startedAt(), result.finishedAt());
        }
        return Optional.of(result);
    }

    private static String str(Map<String, Object> config, String key) {
        Object v = config.get(key);
        return v == null ? "" : v.toString().trim();
    }
}
