package io.github.haimfeng.taskcopilot.web;

import io.github.haimfeng.taskcopilot.domain.AppConfig;
import io.github.haimfeng.taskcopilot.domain.Schedule;
import io.github.haimfeng.taskcopilot.domain.TaskLog;
import io.github.haimfeng.taskcopilot.web.dto.TaskResponse;
import io.github.haimfeng.taskcopilot.repository.AppConfigRepository;
import io.github.haimfeng.taskcopilot.repository.ScheduleRepository;
import io.github.haimfeng.taskcopilot.repository.TaskLogRepository;
import io.github.haimfeng.taskcopilot.repository.TaskRepository;
import io.github.haimfeng.taskcopilot.service.TaskScheduler;
import io.github.haimfeng.taskcopilot.service.TaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.time.Duration;
import java.util.*;

/**
 * 全局控制、系统信息与实时监控。
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final TaskScheduler taskScheduler;
    private final TaskRepository taskRepository;
    private final TaskService taskService;
    private final TaskLogRepository taskLogRepository;
    private final ScheduleRepository scheduleRepository;
    private final AppConfigRepository appConfigRepository;

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${server.address:0.0.0.0}")
    private String serverAddress;

    private final org.springframework.core.env.Environment env;

    // 后端（服务器）版本号：与前端 HTML/JS 版本号保持同一格式（日期.序号），在此硬编码。
    private static final String SERVER_VERSION = "20260805.20";

    // 网络速率缓存
    private static volatile long cachedNetRx = 0;
    private static volatile long cachedNetTx = 0;

    // 仪表盘显示名缓存（DB 中 key=displayName）
    private volatile String cachedDisplayName = null;

    private String getDisplayName() {
        if (cachedDisplayName != null) return cachedDisplayName;
        AppConfig cfg = appConfigRepository.findById("displayName").orElse(null);
        if (cfg != null && !cfg.getValue().isBlank()) {
            cachedDisplayName = cfg.getValue();
            return cachedDisplayName;
        }
        return env.getProperty("taskcopilot.display-name", "USER");
    }

    /**
     * 应用启动时为新用户预置默认显示名（displayName=USER）。
     * 保证该记录始终存在，使首次修改用户名走 UPDATE 而非 INSERT，
     * 从根本上避免唯一键冲突导致的 500。
     */
    @PostConstruct
    public void initDefaultConfig() {
        if (!appConfigRepository.existsById("displayName")) {
            appConfigRepository.save(new AppConfig("displayName",
                    env.getProperty("taskcopilot.display-name", "USER")));
        }
        cachedDisplayName = getDisplayName();
    }

    /**
     * 解析后端（服务器）版本号，直接返回硬编码值，避免依赖自动生成的 build-info。
     */
    private String resolveServerVersion() {
        return SERVER_VERSION;
    }

    static {
        Thread updater = new Thread(() -> {
            while (true) {
                try {
                    long[] rate = readNetworkRateRaw();
                    cachedNetRx = rate[0];
                    cachedNetTx = rate[1];
                } catch (Exception ignored) {}
                try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
            }
        }, "net-rate-updater");
        updater.setDaemon(true);
        updater.start();
    }

    public SystemController(TaskScheduler taskScheduler,
                            TaskRepository taskRepository,
                            TaskService taskService,
                            TaskLogRepository taskLogRepository,
                            ScheduleRepository scheduleRepository,
                            AppConfigRepository appConfigRepository,
                            org.springframework.core.env.Environment env) {
        this.taskScheduler = taskScheduler;
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.taskLogRepository = taskLogRepository;
        this.scheduleRepository = scheduleRepository;
        this.appConfigRepository = appConfigRepository;
        this.env = env;
    }

    /**
     * 系统信息 + 实时监控指标（CPU、内存、磁盘、JVM、调度器状态）。
     */
    @GetMapping("/info")
    public Map<String, Object> info() {
        Runtime runtime = Runtime.getRuntime();
        File root = new File(System.getProperty("user.dir"));
        com.sun.management.OperatingSystemMXBean osBean =
                (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

        Map<String, Object> info = new LinkedHashMap<>();

        // 系统概况
        info.put("osName", System.getProperty("os.name"));
        info.put("osArch", System.getProperty("os.arch"));
        info.put("javaVersion", System.getProperty("java.version"));
        info.put("availableProcessors", runtime.availableProcessors());
        info.put("hostname", safeHostname());
        info.put("displayName", getDisplayName());
        info.put("version", resolveServerVersion());
        info.put("serverPort", serverPort);
        info.put("serverAddress", serverAddress);
        info.put("uptimeSeconds", Duration.ofMillis(
                ManagementFactory.getRuntimeMXBean().getUptime()).toSeconds());

        // CPU
        double cpuLoad = osBean.getCpuLoad();
        double processCpuLoad = osBean.getProcessCpuLoad();
        info.put("cpuLoad", cpuLoad < 0 ? null : Math.round(cpuLoad * 1000.0) / 10.0);      // 整机 CPU%
        info.put("processCpuLoad", processCpuLoad < 0 ? null : Math.round(processCpuLoad * 1000.0) / 10.0);

        // 内存（物理）
        long totalPhysMem = osBean.getTotalMemorySize();
        long freePhysMem = osBean.getFreeMemorySize();
        long usedPhysMem = totalPhysMem - freePhysMem;
        info.put("totalPhysMemMb", totalPhysMem / 1024 / 1024);
        info.put("usedPhysMemMb", usedPhysMem / 1024 / 1024);
        info.put("freePhysMemMb", freePhysMem / 1024 / 1024);

        // JVM 内存
        info.put("jvmUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024);
        info.put("jvmMaxMb", runtime.maxMemory() / 1024 / 1024);

        // 磁盘
        info.put("diskFreeGb", root.getUsableSpace() / 1024 / 1024 / 1024);
        info.put("diskTotalGb", root.getTotalSpace() / 1024 / 1024 / 1024);

        // 网络速率：首次采样返回 0，后续通过后台线程异步更新
        info.put("netRxBytesPerSec", cachedNetRx);
        info.put("netTxBytesPerSec", cachedNetTx);

        // 调度器
        info.put("schedulerPaused", taskScheduler.isGloballyPaused());
        info.put("schedulerError", taskScheduler.isSchedulerError());
        info.put("scheduledCount", taskScheduler.scheduledCount());

        return info;
    }

    /**
     * 仪表盘聚合数据：日程表运行状态 + 任务统计摘要。
     */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        Map<String, Object> data = new LinkedHashMap<>();

        // 当前运行中日程表
        Schedule active = scheduleRepository.findFirstByActiveTrue().orElse(null);
        if (active != null) {
            Map<String, Object> activeSchedule = new LinkedHashMap<>();
            activeSchedule.put("id", active.getId());
            activeSchedule.put("name", active.getName());
            activeSchedule.put("taskCount", taskRepository.countByScheduleId(active.getId()));
            data.put("activeSchedule", activeSchedule);
        } else {
            data.put("activeSchedule", null);
        }

        // 任务统计
        long totalTasks = taskRepository.count();
        long enabledTasks = taskRepository.countByEnabledTrue();
        data.put("totalTasks", totalTasks);
        data.put("enabledTasks", enabledTasks);
        data.put("disabledTasks", totalTasks - enabledTasks);

        // 日程表总数
        data.put("totalSchedules", scheduleRepository.count());

        return data;
    }

    /**
     * 网络接口信息（IP 地址、MAC 地址等）。
     */
    @GetMapping("/network")
    public List<Map<String, Object>> networkInfo() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (nic.isLoopback() || !nic.isUp()) continue;

                // 收集 IPv4 和 IPv6 地址
                List<String> ipv4 = new ArrayList<>();
                List<String> ipv6 = new ArrayList<>();
                Enumeration<InetAddress> addrEnum = nic.getInetAddresses();
                while (addrEnum.hasMoreElements()) {
                    String addr = addrEnum.nextElement().getHostAddress();
                    if (addr.contains(":")) {
                        // 跳过链路本地和站点本地 IPv6（fe80:/fec0:/fdfd: 等）
                        String lower = addr.toLowerCase();
                        if (lower.startsWith("fe80:") || lower.startsWith("fec0:") || lower.startsWith("fdfd:")) {
                            ipv6.add(addr);
                        } else {
                            ipv6.add(addr);
                        }
                    } else {
                        ipv4.add(addr);
                    }
                }

                // 过滤：只保留有 IPv4 地址且非虚拟接口
                if (ipv4.isEmpty()) continue;
                String name = nic.getName().toLowerCase();
                String display = nic.getDisplayName().toLowerCase();
                // 排除 WAN Miniport、WFP 过滤器、QoS 调度器、Npcap 驱动等虚拟子层
                if (name.contains("iftype") || display.contains("wan miniport")
                        || display.contains("wfp") || display.contains("qos")
                        || display.contains("npcap") || display.contains("lightweight")
                        || display.contains("native mac layer")) {
                    continue;
                }

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", nic.getName());
                item.put("displayName", nic.getDisplayName());

                byte[] mac = nic.getHardwareAddress();
                if (mac != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < mac.length; i++) {
                        if (i > 0) sb.append(':');
                        sb.append(String.format("%02X", mac[i]));
                    }
                    item.put("mac", sb.toString());
                }

                List<String> allIps = new ArrayList<>();
                allIps.addAll(ipv4);
                allIps.addAll(ipv6);
                item.put("ips", allIps);
                item.put("mtu", nic.getMTU());

                list.add(item);
            }
        } catch (Exception e) {
            // 静默
        }
        return list;
    }

    // 网络配置缓存（变化极慢，30 秒刷新一次即可）
    private volatile Map<String, Object> cachedNetworkConfig = null;
    private volatile long networkConfigCacheTime = 0;

    /**
     * 本机网络配置：首选 IPv4、DNS 服务器、网关、链路速度。
     */
    @GetMapping("/network-config")
    public Map<String, Object> networkConfig() {
        long now = System.currentTimeMillis();
        if (cachedNetworkConfig != null && (now - networkConfigCacheTime) < 30_000) {
            return cachedNetworkConfig;
        }
        Map<String, Object> cfg = buildNetworkConfig();
        cachedNetworkConfig = cfg;
        networkConfigCacheTime = now;
        return cfg;
    }

    private Map<String, Object> buildNetworkConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("dns", new ArrayList<String>());
        config.put("gateway", "");
        config.put("linkSpeed", "");

        // 获取首选 IPv4
        try {
            String ip = InetAddress.getLocalHost().getHostAddress();
            if (ip.startsWith("127.")) {
                Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
                outer:
                while (nics.hasMoreElements()) {
                    NetworkInterface nic = nics.nextElement();
                    if (nic.isLoopback() || !nic.isUp()) continue;
                    Enumeration<InetAddress> addrs = nic.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        String a = addrs.nextElement().getHostAddress();
                        if (!a.contains(":") && !a.startsWith("127.")) {
                            ip = a;
                            break outer;
                        }
                    }
                }
            }
            config.put("localIp", ip);
        } catch (Exception e) {
            config.put("localIp", "unknown");
        }

        // 获取 DNS 和网关（通过 PowerShell，适配中文 Windows）
        try {
            List<String> dns = new ArrayList<>();
            String gateway = "";
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                // 获取 IPv4 DNS
                ProcessBuilder pb = new ProcessBuilder("powershell", "-Command",
                        "Get-DnsClientServerAddress -AddressFamily IPv4 | Where-Object { $_.ServerAddresses.Count -gt 0 } | ForEach-Object { $_.ServerAddresses -join ',' }");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), "GBK"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        for (String addr : line.split(",")) {
                            String a = addr.trim();
                            if (!a.isBlank() && a.contains(".")) {
                                dns.add(a);
                            }
                        }
                    }
                }
                p.waitFor();

                // 获取默认网关
                pb = new ProcessBuilder("powershell", "-Command",
                        "(Get-NetRoute -DestinationPrefix '0.0.0.0/0' | Select-Object -First 1).NextHop");
                pb.redirectErrorStream(true);
                p = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), "GBK"))) {
                    gateway = reader.readLine();
                    if (gateway != null) gateway = gateway.trim();
                    else gateway = "";
                }
                p.waitFor();

                // 获取链路速度
                pb = new ProcessBuilder("powershell", "-Command",
                        "(Get-NetAdapter | Where-Object { $_.Status -eq 'Up' } | Select-Object -First 1).LinkSpeed");
                pb.redirectErrorStream(true);
                p = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), "GBK"))) {
                    String speed = reader.readLine();
                    if (speed != null && !speed.trim().isEmpty()) {
                        try {
                            long bps = Long.parseLong(speed.trim());
                            config.put("linkSpeed", (bps / 1000000) + " Mbps");
                        } catch (NumberFormatException e) {
                            config.put("linkSpeed", speed.trim());
                        }
                    }
                }
                p.waitFor();
            } else {
                // Linux: /etc/resolv.conf
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        new java.io.FileInputStream("/etc/resolv.conf")))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("nameserver")) {
                            String[] parts = line.split("\\s+");
                            if (parts.length > 1) dns.add(parts[1]);
                        }
                    }
                }
            }
            config.put("dns", dns);
            config.put("gateway", gateway);
        } catch (Exception e) {
            // 静默
        }

        return config;
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
     * 获取最近一次导致调度器异常的任务详情及其失败日志输出，供前端错误弹窗展示。
     * 若无错误任务记录则返回空结构。
     */
    @GetMapping("/scheduler-error-detail")
    public Map<String, Object> schedulerErrorDetail() {
        long errorTaskId = taskScheduler.getErrorTaskId();
        Map<String, Object> result = new LinkedHashMap<>();
        if (errorTaskId < 0) {
            result.put("hasError", false);
            return result;
        }
        try {
            TaskResponse tr = taskService.get(errorTaskId);
            Map<String, Object> task = new LinkedHashMap<>();
            task.put("id", tr.id());
            task.put("name", tr.name());
            task.put("typeCode", tr.typeCode());
            task.put("typeName", tr.typeName());
            task.put("command", tr.command());
            task.put("workingDir", tr.workingDir());
            task.put("config", tr.config());
            task.put("lastStatus", tr.lastStatus());
            task.put("lastExitCode", tr.lastExitCode());
            task.put("lastStdout", tr.lastStdout());
            task.put("lastStderr", tr.lastStderr());
            task.put("remark", tr.remark());
            result.put("task", task);
            // 取该任务最近若干条日志，筛选失败/超时的最早一条作为错误输出
            Pageable page = PageRequest.of(0, 20);
            TaskLog log = taskLogRepository.findByTaskIdOrderByStartedAtDesc(errorTaskId, page)
                    .stream()
                    .filter(l -> !"SUCCESS".equals(l.getStatus()))
                    .findFirst()
                    .orElse(null);
            if (log != null) {
                Map<String, Object> logMap = new LinkedHashMap<>();
                logMap.put("status", log.getStatus());
                logMap.put("exitCode", log.getExitCode());
                logMap.put("stderr", log.getStderr());
                logMap.put("stdout", log.getStdout());
                logMap.put("startedAt", log.getStartedAt());
                result.put("log", logMap);
            }
        } catch (Exception e) {
            // 任务可能已被删除，忽略
        }
        result.put("hasError", true);
        return result;
    }

    /**
     * 手动清除调度器异常状态（任务错误修复后由用户确认）。
     */
    @PostMapping("/scheduler/reset-error")
    public Map<String, Object> resetSchedulerError() {
        taskScheduler.resetError();
        return schedulerStatus();
    }

    /**
     * 获取当前运行中的进程名列表（去重、按字母排序），供前端「选择进程」下拉使用。
     */
    @GetMapping("/processes")
    public List<Map<String, String>> runningProcesses() {
        List<Map<String, String>> list = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
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
        return list.stream()
                .distinct()
                .sorted(Comparator.comparing(m -> m.get("name").toLowerCase()))
                .toList();
    }

    /**
     * 更新仪表盘显示名（持久化到数据库 app_config 表）。
     */
    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/display-name")
    public Map<String, String> updateDisplayName(@org.springframework.web.bind.annotation.RequestBody Map<String, String> body) {
        String name = body.getOrDefault("name", "").trim();
        if (name.isEmpty()) name = "USER";
        AppConfig cfg = appConfigRepository.findById("displayName").orElse(null);
        if (cfg == null) {
            cfg = new AppConfig("displayName", name);
        } else {
            cfg.setValue(name);
        }
        appConfigRepository.save(cfg);
        cachedDisplayName = name;
        return Map.of("name", name);
    }

    /**
     * 校验应用路径是否合法：存在且为文件，并返回扩展名。
     */
    @PostMapping("/check-path")
    public Map<String, Object> checkPath(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
        String path = body == null ? null : String.valueOf(body.getOrDefault("path", "")).trim();
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

    /**
     * 删除所有业务数据：任务执行日志、任务、日程表，并重置仪表盘用户名。
     * 操作不可恢复，需由前端二次确认后调用。
     */
    @org.springframework.transaction.annotation.Transactional
    @PostMapping("/clear-data")
    public Map<String, Object> clearAllData() {
        taskScheduler.pauseAll();                       // 先停止所有调度
        taskLogRepository.deleteAll();
        taskRepository.deleteAll();
        scheduleRepository.deleteAll();
        appConfigRepository.deleteAll();               // 清空所有配置（含 displayName）
        // 重新预置默认用户名，与服务器启动时 @PostConstruct initDefaultConfig 行为保持一致
        if (!appConfigRepository.existsById("displayName")) {
            appConfigRepository.save(new AppConfig("displayName",
                    env.getProperty("taskcopilot.display-name", "USER")));
        }
        cachedDisplayName = null;                      // 清空缓存，使下一行从 DB 重新读取默认值
        cachedDisplayName = getDisplayName();          // 同步缓存，避免首次修改 500
        taskScheduler.resumeAll();                     // 重新加载（此时无任务，自动调度为空）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        return result;
    }

    /* ---------- 私有辅助 ---------- */

    private static String safeHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * 读取网络速率（Bytes/sec）。
     * Windows 通过 typeperf 获取瞬时速率，Linux 通过 /proc/net/dev 差值计算。
     */
    private static long[] readNetworkRateRaw() {
        long[] result = new long[]{0L, 0L};
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                readNetworkRateWindows(result);
            } else {
                readNetworkRateLinux(result);
            }
        } catch (Exception e) {
            // 静默
        }
        return result;
    }

    private static void readNetworkRateWindows(long[] out) throws Exception {
        // 通过 PowerShell Get-NetAdapterStatistics 获取累计字节数，两次采样差值计算速率
        String cmd = "(Get-NetAdapterStatistics | Where-Object { $_.ReceivedBytes -gt 0 } | Measure-Object -Property ReceivedBytes,SentBytes -Sum | ForEach-Object { '' + $_.Sum }) -join ','";
        ProcessBuilder pb = new ProcessBuilder("powershell", "-Command", cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), "GBK"))) {
            String line = reader.readLine();
            if (line != null) {
                String[] parts = line.trim().split(",");
                if (parts.length >= 2) {
                    long rx = parseLongSafe(parts[0]);
                    long tx = parseLongSafe(parts[1]);
                    // 累计值 → 差值计算速率
                    long now = System.currentTimeMillis();
                    if (lastNetWinRx >= 0 && lastNetWinTime > 0) {
                        double dt = (now - lastNetWinTime) / 1000.0;
                        if (dt > 0) {
                            out[0] = (long) ((rx - lastNetWinRx) / dt);
                            out[1] = (long) ((tx - lastNetWinTx) / dt);
                        }
                    }
                    lastNetWinRx = rx;
                    lastNetWinTx = tx;
                    lastNetWinTime = now;
                }
            }
        }
        p.waitFor();
    }

    private static long lastNetWinRx = -1, lastNetWinTx = -1, lastNetWinTime = 0;

    // Linux 网络速率缓存（用于差值计算）
    private static long lastNetRx = -1, lastNetTx = -1, lastNetTime = 0;

    private static void readNetworkRateLinux(long[] out) throws Exception {
        long now = System.currentTimeMillis();
        long rx = 0, tx = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream("/proc/net/dev")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("lo:") || !line.contains(":")) continue;
                String[] parts = line.split("\\s+");
                if (parts.length > 9) {
                    rx += Long.parseLong(parts[1]);
                    tx += Long.parseLong(parts[9]);
                }
            }
        }
        if (lastNetRx >= 0 && lastNetTime > 0) {
            double dt = (now - lastNetTime) / 1000.0;
            if (dt > 0) {
                out[0] = (long) ((rx - lastNetRx) / dt);
                out[1] = (long) ((tx - lastNetTx) / dt);
            }
        }
        lastNetRx = rx;
        lastNetTx = tx;
        lastNetTime = now;
    }

    private static long parseLongSafe(String s) {
        if (s == null) return 0;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
