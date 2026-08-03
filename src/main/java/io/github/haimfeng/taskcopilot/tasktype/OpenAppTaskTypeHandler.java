package io.github.haimfeng.taskcopilot.tasktype;

import io.github.haimfeng.taskcopilot.domain.Task;
import io.github.haimfeng.taskcopilot.service.CommandExecutor;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 打开应用：启动一个桌面程序，方便用户快速打开常用软件（浏览器、IDE、文件管理器等）。
 * <p>
 * 配置项：appPath（应用路径或命令，可预置常见应用）、customPath（自定义绝对路径）、
 * args（启动参数）。执行时按平台拼装启动命令（Windows 用 {@code start}，macOS 用
 * {@code open}，Linux 用 {@code xdg-open}），复用通用命令执行器。
 */
@Component
public class OpenAppTaskTypeHandler implements TaskTypeHandler {

    @Override
    public String code() {
        return "OPEN_APP";
    }

    @Override
    public String displayName() {
        return "打开应用";
    }

    @Override
    public String description() {
        return "一键启动常用桌面程序，省去记忆启动命令";
    }

    @Override
    public List<FieldSchema> configSchema() {
        return List.of(
                FieldSchema.appFile("appPath", "应用", "拖入 .exe 程序或 .lnk 快捷方式，也可手动填写完整路径"),
                FieldSchema.text("args", "启动参数", false, "可选，例如 --new-window"),
                FieldSchema.time("time", "执行时间", "08:30")
        );
    }

    @Override
    public void validate(Map<String, Object> config) {
        String appPath = str(config, "appPath");
        if (appPath.isBlank()) {
            throw new IllegalArgumentException("请拖入应用或填写应用路径");
        }
    }

    @Override
    public String summary(Map<String, Object> config) {
        return "打开应用：" + str(config, "appPath");
    }

    @Override
    public Optional<CommandExecutor.ExecutionResult> execute(
            Task task, Map<String, Object> config, CommandExecutor executor) {
        boolean windows = isWindows();
        String appPath = str(config, "appPath");
        String target = appPath;
        String args = str(config, "args");
        String quoted = '"' + target + '"';
        String command;
        if (windows) {
            command = "start \"\" " + quoted + (args.isBlank() ? "" : " " + args);
        } else if (isMac()) {
            command = "open " + quoted + (args.isBlank() ? "" : " --args " + args);
        } else {
            command = "xdg-open " + quoted;
        }
        Task launch = new Task();
        launch.setCommand(command);
        launch.setWorkingDir(task.getWorkingDir());
        launch.setTimeoutSeconds(task.getTimeoutSeconds());
        return Optional.of(executor.execute(launch));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static String str(Map<String, Object> config, String key) {
        Object v = config.get(key);
        return v == null ? "" : v.toString().trim();
    }
}
