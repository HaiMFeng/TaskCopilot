package io.github.haimfeng.taskcopilot.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;

import java.io.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 终端处理器：为每个连接启动一个本地 Shell 进程，
 * 将终端输入写入进程 stdin，进程 stdout/stderr 回传终端。
 */
@Component
public class TerminalWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TerminalWebSocketHandler.class);
    private final Map<WebSocketSession, Process> processes = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket 终端连接: {}", session.getId());
        // 防止同一会话残留旧进程（如前端重连时旧连接尚未完全关闭）
        Process old = processes.remove(session);
        if (old != null) {
            try { old.destroyForcibly(); } catch (Exception ignored) {}
        }
        String shell;
        if (session.getUri() != null && session.getUri().getQuery() != null
                && session.getUri().getQuery().toLowerCase().contains("powershell")) {
            shell = "powershell";
        } else {
            shell = "cmd";
        }
        try {
            ProcessBuilder pb;
            if ("powershell".equals(shell)) {
                pb = new ProcessBuilder("powershell", "-NoLogo", "-NoExit");
            } else {
                pb = new ProcessBuilder("cmd", "/k");
            }
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            processes.put(session, proc);
            log.info("终端进程已启动: {} (PID={})", shell, proc.pid());

            // 发送欢迎消息
            session.sendMessage(new TextMessage("\u001b[32m终端已连接 (" + shell + ")\u001b[0m\r\n"));

            // stdout → WebSocket
            Thread t = new Thread(() -> {
                try (InputStream in = proc.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        if (session.isOpen()) {
                            session.sendMessage(new TextMessage(new String(buf, 0, n, "GBK")));
                        }
                    }
                } catch (Exception e) {
                    log.debug("终端输出流关闭: {}", e.getMessage());
                }
            }, "term-out-" + session.getId());
            t.setDaemon(true);
            t.start();

            // 进程退出时关闭
            Thread w = new Thread(() -> {
                try { proc.waitFor(); } catch (InterruptedException ignored) {}
                processes.remove(session);
                try { if (session.isOpen()) session.close(); } catch (Exception ignored) {}
            }, "term-wait-" + session.getId());
            w.setDaemon(true);
            w.start();

        } catch (Exception e) {
            log.error("启动终端失败", e);
            try { session.close(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) {
        Process proc = processes.get(session);
        if (proc == null) { log.warn("终端输入时进程不存在"); return; }
        try {
            String payload = (String) message.getPayload();
            log.debug("收到终端输入: {}", payload.replace("\r", "\\r").replace("\n", "\\n"));
            OutputStream stdin = proc.getOutputStream();
            stdin.write(payload.getBytes("GBK"));
            stdin.flush();
        } catch (Exception e) {
            log.warn("写入终端失败: {}", e.getMessage());
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        close(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        close(session);
    }

    @Override
    public boolean supportsPartialMessages() {
        return false;
    }

    private void close(WebSocketSession session) {
        Process proc = processes.remove(session);
        if (proc != null) {
            proc.destroyForcibly();
        }
        try { if (session.isOpen()) session.close(); } catch (Exception ignored) {}
    }
}
