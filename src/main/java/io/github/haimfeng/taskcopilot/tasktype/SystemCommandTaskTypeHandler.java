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
 * 系统指令：关机、重启、休眠、锁屏。
 * <p>
 * 配置项：action（操作类型）、delaySeconds（延时秒数）。
 * 执行时调用 Windows 的 {@code shutdown} 命令或 {@code rundll32}。
 */
@Component
public class SystemCommandTaskTypeHandler implements TaskTypeHandler {

    @Override
    public String code() {
        return "SYSTEM_COMMAND";
    }

    @Override
    public String displayName() {
        return "系统指令";
    }

    @Override
    public String description() {
        return "定时关机、重启、休眠或锁屏";
    }

    @Override
    public List<FieldSchema> configSchema() {
        return List.of(
                FieldSchema.select("action", "操作",
                        List.of(
                                FieldSchema.option("shutdown", "关机"),
                                FieldSchema.option("restart", "重启"),
                                FieldSchema.option("hibernate", "休眠"),
                                FieldSchema.option("lock", "锁屏")
                        ),
                        "shutdown"),
                FieldSchema.number("delaySeconds", "延时（秒）", 0, 3600, 0),
                FieldSchema.time("time", "执行时间", "08:30")
        );
    }

    @Override
    public void validate(Map<String, Object> config) {
        String action = str(config, "action");
        if (!List.of("shutdown", "restart", "hibernate", "lock").contains(action)) {
            throw new IllegalArgumentException("请选择有效的系统操作");
        }
    }

    @Override
    public String summary(Map<String, Object> config) {
        String action = str(config, "action");
        int delay = intVal(config, "delaySeconds");
        String label = switch (action) {
            case "shutdown" -> "关机";
            case "restart" -> "重启";
            case "hibernate" -> "休眠";
            case "lock" -> "锁屏";
            default -> "未知操作";
        };
        return "系统指令：" + label + (delay > 0 ? "（延时 " + delay + " 秒）" : "");
    }

    @Override
    public Optional<CommandExecutor.ExecutionResult> execute(
            Task task, Map<String, Object> config, CommandExecutor executor) {
        String action = str(config, "action");
        int delay = intVal(config, "delaySeconds");

        String command = buildCommand(action, delay);

        Task launch = new Task();
        launch.setCommand(command);
        launch.setWorkingDir(task.getWorkingDir());
        int baseTimeout = task.getTimeoutSeconds() != null ? task.getTimeoutSeconds() : 0;
        launch.setTimeoutSeconds(Math.max(baseTimeout, delay + 30));
        CommandExecutor.ExecutionResult result = executor.execute(launch);

        // 补充友好提示
        String label = switch (action) {
            case "shutdown" -> "关机";
            case "restart" -> "重启";
            case "hibernate" -> "休眠";
            case "lock" -> "锁屏";
            default -> "操作";
        };
        if (result.status() == ExecutionStatus.SUCCESS) {
            String ok = "已发起系统指令：" + label
                    + (delay > 0 ? "（将在 " + delay + " 秒后执行）" : "")
                    + System.lineSeparator()
                    + "可通过命令行执行 shutdown /a 取消待执行的关机/重启计划。";
            String stdout = result.stdout().isBlank()
                    ? ok
                    : result.stdout() + System.lineSeparator() + ok;
            result = new CommandExecutor.ExecutionResult(
                    result.status(), result.exitCode(), stdout,
                    result.stderr(), result.startedAt(), result.finishedAt());
        }
        return Optional.of(result);
    }

    /**
     * 根据操作类型拼装 Windows 命令。
     */
    private String buildCommand(String action, int delay) {
        String delayArg = delay > 0 ? " /t " + delay : " /t 0";
        return switch (action) {
            case "shutdown" -> "shutdown /s" + delayArg;
            case "restart" -> "shutdown /r" + delayArg;
            case "hibernate" -> "shutdown /h";
            case "lock" -> "rundll32.exe user32.dll,LockWorkStation";
            default -> throw new IllegalArgumentException("不支持的系统操作：" + action);
        };
    }

    private static String str(Map<String, Object> config, String key) {
        Object v = config.get(key);
        return v == null ? "" : v.toString().trim();
    }

    private static int intVal(Map<String, Object> config, String key) {
        Object v = config.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v == null) return 0;
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
