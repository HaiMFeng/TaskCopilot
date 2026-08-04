package io.github.haimfeng.taskcopilot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局唯一的终端会话管理器。
 *
 * 设计要点（与「单一持久终端 + 轮询」方案一致）：
 * - 整个应用同时只存在一个 shell 进程（懒创建），不同端共享同一终端。
 * - 进程输出持续写入内存环形缓冲（按字节限长 64KB，单行 8KB 截断），不持久化。
 * - 每一条输出带单调递增 seq；前端以 after=seq 增量拉取，新连接拉全量。
 * - 用户关闭网页不销毁进程；仅显式 stop 才销毁进程并清空缓冲。
 * - 中断（Ctrl+C 等价）：Windows 下管道模式的 cmd/powershell 不会把 stdin 的 0x03
 *   当作中断信号，因此采用「杀掉当前进程树 + 重启同类型 shell（保留缓冲）」实现，
 *   等价于打断卡住命令并回到干净提示符。
 */
@Service
public class TerminalSessionManager {

    private static final Logger log = LoggerFactory.getLogger(TerminalSessionManager.class);

    /** 环形缓冲上限（字节）。 */
    private static final int MAX_BUFFER_BYTES = 64 * 1024;
    /** 单行最大字节数，超出截断，防止异常长行撑爆缓冲或前端渲染。 */
    private static final int MAX_LINE_BYTES = 8 * 1024;
    /** 输出文本编码（Windows 控制台为 GBK）。 */
    private static final Charset CHARSET = Charset.forName("GBK");

    /** 线程安全输出缓冲：每条记录含 seq 与文本片段。 */
    private final List<Chunk> buffer = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong(0);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean restarting = new AtomicBoolean(false);
    private final AtomicBoolean suppressEndNotice = new AtomicBoolean(false);

    private Process process;
    private OutputStream stdin;
    private Thread readerThread;
    private final Object lock = new Object();

    /** 一条带序号的输出片段。 */
    public static class Chunk {
        public final long seq;
        public final String text;
        Chunk(long seq, String text) {
            this.seq = seq;
            this.text = text;
        }
    }

    /** 终端当前状态。 */
    public static class State {
        public boolean running;
        public String shell;
        State(boolean running, String shell) {
            this.running = running;
            this.shell = shell;
        }
    }

    public State getState() {
        synchronized (lock) {
            boolean r = running.get() || restarting.get();
            return new State(r, process == null ? null : currentShell);
        }
    }

    private volatile String currentShell = "CMD";

    public boolean isRunning() {
        return running.get();
    }

    /**
     * 启动（懒创建）唯一 shell 进程。若已在运行则忽略。
     */
    public synchronized void start(String shell) {
        if (running.get()) return;
        spawn(shell);
    }

    /**
     * 实际创建 shell 进程（无 running 守卫，供 start 与中断重启复用）。
     */
    private void spawn(String shell) {
        currentShell = "PowerShell".equalsIgnoreCase(shell) ? "PowerShell" : "CMD";
        try {
            ProcessBuilder pb;
            if ("PowerShell".equals(currentShell)) {
                pb = new ProcessBuilder("powershell", "-NoExit", "-Command", "-");
            } else {
                pb = new ProcessBuilder("cmd", "/K");
            }
            pb.redirectErrorStream(true);
            process = pb.start();
            stdin = process.getOutputStream();
            running.set(true);
            log.info("终端进程已启动: {}", currentShell);

            readerThread = new Thread(this::pumpOutput, "terminal-output-pump");
            readerThread.setDaemon(true);
            readerThread.start();
        } catch (IOException e) {
            log.error("启动终端进程失败", e);
            running.set(false);
            process = null;
            throw new RuntimeException("启动终端失败: " + e.getMessage());
        }
    }

    private void pumpOutput() {
        Process localProc = process;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(localProc.getInputStream(), CHARSET))) {
            StringBuilder line = new StringBuilder();
            int ch;
            while ((ch = reader.read()) != -1) {
                line.append((char) ch);
                // 以换行切分，便于单行长度限制；同时避免缓冲无限增长
                if (ch == '\n' || line.length() >= MAX_LINE_BYTES) {
                    String piece = line.toString();
                    if (piece.length() > MAX_LINE_BYTES) {
                        piece = piece.substring(0, MAX_LINE_BYTES);
                    }
                    appendToBuffer(piece);
                    line.setLength(0);
                }
            }
        } catch (IOException e) {
            if (running.get() && !restarting.get()) log.warn("终端输出读取异常", e);
        } finally {
            // 进程结束（被 stop 或中断重启时）
            synchronized (lock) {
                // 重启过程中 keep running=true，避免其他端轮询误判为「已停止」
                if (!restarting.get()) running.set(false);
                try { if (stdin != null) stdin.close(); } catch (IOException ignored) {}
                stdin = null;
            }
            if (!suppressEndNotice.get()) {
                appendToBuffer("\r\n[终端进程已结束]\r\n");
            }
        }
    }

    private void appendToBuffer(String text) {
        long s = seq.incrementAndGet();
        buffer.add(new Chunk(s, text));
        // 按字节限长回收最旧记录
        int total = 0;
        while (!buffer.isEmpty()) {
            Chunk first = buffer.get(0);
            int len = first.text.getBytes(CHARSET).length;
            if (total + len <= MAX_BUFFER_BYTES) break;
            buffer.remove(0);
            total += len;
        }
    }

    /**
     * 拉取 after 之后的增量输出；after <= 0 时返回全量缓冲。
     */
    public List<Chunk> drain(long after) {
        List<Chunk> result = new ArrayList<>();
        for (Chunk c : buffer) {
            if (c.seq > after) result.add(c);
        }
        return result;
    }

    /**
     * 写入命令到 shell 标准输入。
     */
    public void input(String command) {
        if (!running.get() || stdin == null) return;
        try {
            stdin.write((command + "\r\n").getBytes(CHARSET));
            stdin.flush();
        } catch (IOException e) {
            log.warn("终端输入写入失败", e);
        }
    }

    /**
     * 中断当前命令：Windows 管道模式下 0x03 不触发 Ctrl+C，
     * 故杀掉当前进程树并重启一个同类型 shell（保留历史缓冲）。
     */
    public synchronized void interrupt() {
        if (!running.get() || process == null) return;
        String shell = currentShell;
        restarting.set(true);
        suppressEndNotice.set(true);
        long pid = process.pid();
        try {
            new ProcessBuilder("taskkill", "/T", "/F", "/PID", String.valueOf(pid))
                    .redirectErrorStream(true).start().waitFor();
        } catch (Exception ignored) {}
        if (readerThread != null) {
            try { readerThread.join(2000); } catch (InterruptedException ignored) {}
        }
        process = null;
        stdin = null;
        suppressEndNotice.set(false);
        // 重启同类型 shell（spawn 不受 running 守卫拦截），running 始终为 true，缓冲保留
        spawn(shell);
        restarting.set(false);
    }

    /**
     * 停止并销毁唯一终端：递归杀进程树（含孙进程），清空缓冲。
     */
    public synchronized void stop() {
        if (!running.get() && process == null) {
            buffer.clear();
            return;
        }
        suppressEndNotice.set(true);
        if (process != null) {
            long pid = process.pid();
            // 递归杀进程树（Windows 下 cmd/powershell 的子进程不会被 destroyForcibly 连带清理）
            try {
                new ProcessBuilder("taskkill", "/T", "/F", "/PID", String.valueOf(pid))
                        .redirectErrorStream(true).start().waitFor();
            } catch (Exception ignored) {}
            try { process.destroyForcibly(); } catch (Exception ignored) {}
        }
        // 等待读取线程退出
        if (readerThread != null) {
            try { readerThread.join(2000); } catch (InterruptedException ignored) {}
        }
        process = null;
        stdin = null;
        running.set(false);
        suppressEndNotice.set(false);
        buffer.clear();
        seq.set(0);
        log.info("终端已停止并清空");
    }

    @PreDestroy
    public void destroy() {
        stop();
    }
}
