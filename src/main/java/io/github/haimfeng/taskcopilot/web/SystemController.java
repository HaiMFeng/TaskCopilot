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
        info.put("scheduledCount", taskScheduler.scheduledCount());
        return info;
    }

    @GetMapping("/scheduler")
    public Map<String, Object> schedulerStatus() {
        return Map.of(
                "paused", taskScheduler.isGloballyPaused(),
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
}
