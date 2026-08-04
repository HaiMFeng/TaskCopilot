package io.github.haimfeng.taskcopilot.tasktype;

import io.github.haimfeng.taskcopilot.domain.ExecutionStatus;
import io.github.haimfeng.taskcopilot.domain.Task;
import io.github.haimfeng.taskcopilot.service.CommandExecutor;

import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Instant;
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
        String appPath = cleanPath(str(config, "appPath"));
        String args = str(config, "args");
        File target = new File(appPath);

        // 启动前先校验目标文件是否存在。若文件/快捷方式已被删除或移动，
        // 直接返回失败并给出详细提示，避免进程干等直至超时（默认 60 秒）。
        if (!target.exists()) {
            String msg = "应用路径不存在或已被删除：" + appPath
                    + System.lineSeparator()
                    + "请确认该文件未被移动、重命名或删除后，重新填写正确的路径再执行。";
            return Optional.of(new CommandExecutor.ExecutionResult(
                    ExecutionStatus.FAILURE, -1, "", msg, Instant.now(), Instant.now()));
        }

        boolean windows = isWindows();
        String quoted = '"' + target.getAbsolutePath() + '"';
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
        CommandExecutor.ExecutionResult result = executor.execute(launch);

        // 打开应用类任务通常没有 stdout（GUI 程序异步启动），成功时补充友好提示，
        // 让用户明确知道已发起启动，而不是面对一片空白的输出。
        if (result.status() == ExecutionStatus.SUCCESS) {
            String ok = "已请求启动应用：" + target.getName()
                    + System.lineSeparator()
                    + "（已向系统发起启动请求；若对应程序未弹出窗口，请确认路径指向的是可执行程序或快捷方式）";
            String stdout = result.stdout().isBlank()
                    ? ok
                    : result.stdout() + System.lineSeparator() + ok;
            result = new CommandExecutor.ExecutionResult(
                    result.status(), result.exitCode(), stdout,
                    result.stderr(), result.startedAt(), result.finishedAt());
        }
        return Optional.of(result);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 清洗路径：去除首尾空白与包裹的成对引号（" 或 '）。
     * 与前端 cleanPath 逻辑保持一致，避免用户粘贴带引号的路径被判为不存在。
     */
    private static String cleanPath(String raw) {
        if (raw == null) return "";
        String p = raw.trim();
        p = p.replaceAll("^[\"']+", "").replaceAll("[\"']+$", "");
        return p.trim();
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static String str(Map<String, Object> config, String key) {
        Object v = config.get(key);
        return v == null ? "" : v.toString().trim();
    }
}
