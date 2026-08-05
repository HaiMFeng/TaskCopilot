package io.github.haimfeng.taskcopilot.tasktype;

import io.github.haimfeng.taskcopilot.domain.ExecutionStatus;
import io.github.haimfeng.taskcopilot.domain.Task;
import io.github.haimfeng.taskcopilot.service.CommandExecutor;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 应用保活：守护某个常驻程序，确保它在运行；若进程不在，则自动启动它。
 * <p>
 * 复用「打开应用」的应用路径校验与启动命令拼装逻辑（appFile 字段、start/open/xdg-open），
 * 以及「结束进程」的进程查询思路（process 字段、基于 tasklist 的进程存在性判断）。
 * <p>
 * 执行流程：
 * <ol>
 *     <li>先查询进程名是否已在运行——已在运行则输出相关信息、不做任何动作、返回成功；</li>
 *     <li>未运行则打开应用（同打开应用逻辑），并等待「启动检测时间」；</li>
 *     <li>再次查询：已检测到则记录成功，仍未检测到则报错（返回失败）。</li>
 * </ol>
 * <p>
 * 注意：本任务设计为每日定时运行以持续守护。若启动后仍未检测到进程，将按需求返回失败（报错），
 * 此时框架会根据通用策略处理；若希望任务长期保活，请保证应用路径正确且该程序会注册为指定进程名。
 */
@Component
public class KeepAliveTaskTypeHandler implements TaskTypeHandler {

    @Override
    public String code() {
        return "KEEP_ALIVE";
    }

    @Override
    public String displayName() {
        return "应用保活";
    }

    @Override
    public String description() {
        return "守护指定程序：进程未运行则自动启动，常用于维持常驻应用";
    }

    @Override
    public List<FieldSchema> configSchema() {
        return List.of(
                FieldSchema.process("processName", "进程名",
                        "要守护的进程名，如 notepad.exe，可点击「选择进程」从运行列表选取"),
                FieldSchema.appFile("appPath", "应用",
                        "进程未运行时要启动的程序或快捷方式（.exe / .lnk）"),
                FieldSchema.text("args", "启动参数", false, "可选，例如 --minimized"),
                FieldSchema.number("maxWaitSeconds", "最大启动延时（秒）", 3, 120, 10),
                FieldSchema.time("time", "执行时间", "08:30")
        );
    }

    @Override
    public void validate(Map<String, Object> config) {
        String processName = str(config, "processName");
        if (processName.isBlank()) {
            throw new IllegalArgumentException("请填写要守护的进程名");
        }
        String appPath = cleanPath(str(config, "appPath"));
        if (appPath.isBlank()) {
            throw new IllegalArgumentException("请填写要启动的应用路径");
        }
        File target = new File(appPath);
        if (!target.exists() || !target.isFile()) {
            throw new IllegalArgumentException("应用路径不存在或不是文件：" + appPath);
        }
    }

    @Override
    public String summary(Map<String, Object> config) {
        return "应用保活：" + str(config, "processName");
    }

    @Override
    public Optional<CommandExecutor.ExecutionResult> execute(
            Task task, Map<String, Object> config, CommandExecutor executor) {
        String processName = str(config, "processName");
        String appPath = cleanPath(str(config, "appPath"));
        String args = str(config, "args");
        int maxWaitSeconds = parseMaxWaitSeconds(config);
        Instant startedAt = Instant.now();

        // 1. 先查询进程是否已在运行
        if (isProcessRunning(processName)) {
            String msg = "进程已在运行，无需操作：" + processName
                    + System.lineSeparator()
                    + "应用保活检查通过，本次未执行任何启动动作。";
            return Optional.of(new CommandExecutor.ExecutionResult(
                    ExecutionStatus.SUCCESS, 0, msg, "", startedAt, Instant.now()));
        }

        // 2. 未运行：打开应用（复用打开应用的路径校验与启动命令逻辑）
        File target = new File(appPath);
        if (!target.exists() || !target.isFile()) {
            String msg = "应用路径不存在或不是文件，无法启动：" + appPath
                    + System.lineSeparator()
                    + "请确认该文件未被移动、重命名或删除后，重新填写正确路径再执行。";
            return Optional.of(new CommandExecutor.ExecutionResult(
                    ExecutionStatus.FAILURE, -1, "", msg, startedAt, Instant.now()));
        }
        String command = buildLaunchCommand(target.getAbsolutePath(), args);
        Task launch = new Task();
        launch.setCommand(command);
        launch.setWorkingDir(task.getWorkingDir());
        launch.setTimeoutSeconds(task.getTimeoutSeconds());
        CommandExecutor.ExecutionResult launchResult = executor.execute(launch);

        // 3. 在最大启动延时内，以约 1 次/秒的频率轮询检测进程是否启动
        boolean started = waitUntilProcessRunning(processName, maxWaitSeconds);

        if (started) {
            String msg = "进程未运行，已自动启动并检测成功：" + processName
                    + System.lineSeparator()
                    + "启动目标：" + target.getName();
            String stdout = launchResult.stdout().isBlank()
                    ? msg
                    : launchResult.stdout() + System.lineSeparator() + msg;
            return Optional.of(new CommandExecutor.ExecutionResult(
                    ExecutionStatus.SUCCESS, launchResult.exitCode(),
                    stdout, launchResult.stderr(), startedAt, Instant.now()));
        }

        // 超过最大启动延时仍未检测到进程：报错
        String detail = "已尝试启动应用，但在 " + maxWaitSeconds + " 秒内仍未检测到进程：" + processName
                + System.lineSeparator()
                + "请确认：应用路径正确、该程序会注册为「" + processName + "」进程、且其能正常启动。";
        String stderr = launchResult.stderr().isBlank()
                ? detail
                : launchResult.stderr() + System.lineSeparator() + detail;
        return Optional.of(new CommandExecutor.ExecutionResult(
                ExecutionStatus.FAILURE, launchResult.exitCode(),
                launchResult.stdout(), stderr, startedAt, Instant.now()));
    }

    /** 拼装与「打开应用」一致的跨平台启动命令 */
    private static String buildLaunchCommand(String absPath, String args) {
        String quoted = '"' + absPath + '"';
        if (isWindows()) {
            return "start \"\" " + quoted + (args.isBlank() ? "" : " " + args);
        } else if (isMac()) {
            return "open " + quoted + (args.isBlank() ? "" : " --args " + args);
        }
        return "xdg-open " + quoted;
    }

    /** 查询指定进程名是否正在运行（Windows 用 tasklist，其它平台用 ProcessHandle） */
    private static boolean isProcessRunning(String processName) {
        String name = processName.toLowerCase(Locale.ROOT).trim();
        if (name.isEmpty()) {
            return false;
        }
        if (isWindows()) {
            try {
                ProcessBuilder pb = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), Charset.forName("GBK")))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.replace("\"", "").split(",");
                        if (parts.length > 0 && parts[0].trim().toLowerCase(Locale.ROOT).equals(name)) {
                            return true;
                        }
                    }
                    p.waitFor();
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        }
        return java.lang.ProcessHandle.allProcesses()
                .anyMatch(ph -> ph.info().command()
                        .map(c -> c.toLowerCase(Locale.ROOT).contains(name))
                        .orElse(false));
    }

    private static int parseMaxWaitSeconds(Map<String, Object> config) {
        Object v = config.get("maxWaitSeconds");
        int n = 10;
        if (v != null) {
            try {
                n = Integer.parseInt(v.toString());
            } catch (NumberFormatException ignored) {
                // 解析失败则用默认 10 秒
            }
        }
        if (n < 3) n = 3;
        if (n > 120) n = 120;
        return n;
    }

    /** 在最大启动延时内，以约 1 次/秒的频率轮询检测进程是否启动；检测到立即返回 true，超时返回 false */
    private static boolean waitUntilProcessRunning(String processName, int maxWaitSeconds) {
        long deadline = System.currentTimeMillis() + (long) maxWaitSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (isProcessRunning(processName)) {
                return true;
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 临界时刻再确认一次，避免刚在 sleep 期间启动而漏检
        return isProcessRunning(processName);
    }

    /** 清洗路径：去除首尾空白与包裹的成对引号，与打开应用逻辑保持一致 */
    private static String cleanPath(String raw) {
        if (raw == null) return "";
        String p = raw.trim();
        p = p.replaceAll("^[\"']+", "").replaceAll("[\"']+$", "");
        return p.trim();
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
