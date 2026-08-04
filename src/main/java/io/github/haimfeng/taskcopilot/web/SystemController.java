package io.github.haimfeng.taskcopilot.web;

import io.github.haimfeng.taskcopilot.service.TaskScheduler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局控制与系统信息。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final TaskScheduler taskScheduler;

    public SystemController(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Runtime runtime = Runtime.getRuntime();
        File root = new File(System.getProperty("user.dir"));

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("osName", System.getProperty("os.name"));
        info.put("osArch", System.getProperty("os.arch"));
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("availableProcessors", runtime.availableProcessors());
        info.put("jvmUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
        info.put("jvmMaxMb", runtime.maxMemory() / 1024 / 1024);
        info.put("diskFreeGb", root.getUsableSpace() / 1024 / 1024 / 1024);
        info.put("diskTotalGb", root.getTotalSpace() / 1024 / 1024 / 1024);
        info.put("uptimeSeconds", Duration.ofMillis(
                ManagementFactory.getRuntimeMXBean().getUptime()).toSeconds());
        info.put("schedulerPaused", taskScheduler.isGloballyPaused());
        info.put("schedulerError", taskScheduler.isSchedulerError());
        info.put("scheduledCount", taskScheduler.scheduledCount());
        return info;
    }

    @GetMapping("/scheduler")
    public Map<String, Object> schedulerStatus() {
        return Map.of(
                "paused", taskScheduler.isGloballyPaused(),
                "error", taskScheduler.isSchedulerError(),
                "scheduledCount", taskScheduler.scheduledCount());
    }

    @PostMapping("/scheduler/pause")
    public Map<String, Object> pause() {
        taskScheduler.pauseAll();
        return schedulerStatus();
    }

    @PostMapping("/scheduler/resume")
    public Map<String, Object> resume() {
        taskScheduler.resumeAll();
        return schedulerStatus();
    }

    /**
     * 获取当前运行中的进程名列表（去重、按字母排序），供前端「选择进程」下拉使用。
     */
    @GetMapping("/processes")
    public java.util.List<Map<String, String>> runningProcesses() {
        java.util.List<Map<String, String>> list = new java.util.ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // CSV 格式: "进程名.exe","PID","会话名","会话#","内存使用"
                    String[] parts = line.replace("\"", "").split(",");
                    if (parts.length > 0) {
                        String name = parts[0].trim();
                        if (!name.isBlank()) {
                            list.add(Map.of("name", name));
                        }
                    }
                }
            }
            p.waitFor();
        } catch (Exception e) {
            // 获取进程列表失败不是致命错误，返回空列表即可
        }
        // 按进程名去重排序
        return list.stream()
                .distinct()
                .sorted(java.util.Comparator.comparing(m -> m.get("name").toLowerCase()))
                .toList();
    }

    /**
     * 校验应用路径是否合法：存在且为文件，并返回扩展名。
     * 前端在用户拖入/手动填写 .exe/.lnk 路径时用于实时校验。
     */
    @PostMapping("/check-path")
    public Map<String, Object> checkPath(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        String path = body == null ? null : String.valueOf(body.getOrDefault("path", "")).trim();
        // 去除用户可能粘贴带入的包裹引号（与执行端清洗逻辑保持一致）
        path = path.replaceAll("^[\"']+", "").replaceAll("[\"']+$", "").trim();
        Map<String, Object> result = new LinkedHashMap<>();
        if (path == null || path.isBlank()) {
            result.put("exists", false);
            result.put("isFile", false);
            result.put("extension", "");
            result.put("ok", false);
            return result;
        }
        File file = new File(path);
        boolean exists = file.exists();
        boolean isFile = file.isFile();
        String name = file.getName();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        result.put("exists", exists);
        result.put("isFile", isFile);
        result.put("extension", ext);
        result.put("ok", exists && isFile);
        return result;
    }
}
