package io.github.haimfeng.taskcopilot.web;

import io.github.haimfeng.taskcopilot.service.TerminalSessionManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/terminal")
public class TerminalController {

    private final TerminalSessionManager terminal;

    public TerminalController(TerminalSessionManager terminal) {
        this.terminal = terminal;
    }

    /** 当前终端状态与最新 seq（供前端初始化轮询游标）。 */
    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        TerminalSessionManager.State s = terminal.getState();
        m.put("running", s.running);
        m.put("shell", s.shell);
        // 返回当前缓冲最大 seq，便于前端设置初始 after
        m.put("latestSeq", latestSeq());
        return m;
    }

    /** 拉取输出：after 之后为增量，after<=0 为全量。同时返回终端运行状态供多端同步。 */
    @GetMapping("/output")
    public Map<String, Object> output(@RequestParam(defaultValue = "0") long after) {
        TerminalSessionManager.State st = terminal.getState();
        List<Map<String, Object>> chunks = new ArrayList<>();
        long max = 0;
        for (TerminalSessionManager.Chunk c : terminal.drain(after)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("seq", c.seq);
            item.put("text", c.text);
            chunks.add(item);
            if (c.seq > max) max = c.seq;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("running", st.running);
        m.put("shell", st.shell);
        m.put("chunks", chunks);
        m.put("latestSeq", max);
        return m;
    }

    /** 发送命令到终端（进程不存在则懒创建）。 */
    @PostMapping("/input")
    public Map<String, Object> input(@RequestBody Map<String, String> body) {
        String cmd = body == null ? "" : body.getOrDefault("command", "");
        String shell = body == null ? "CMD" : body.getOrDefault("shell", "CMD");
        if (!terminal.isRunning()) {
            terminal.start(shell);
        }
        terminal.input(cmd);
        return Map.of("ok", true);
    }

    /** 发送 Ctrl+C 中断。 */
    @PostMapping("/interrupt")
    public Map<String, Object> interrupt() {
        if (!terminal.isRunning()) return Map.of("ok", false, "message", "终端未运行");
        terminal.interrupt();
        return Map.of("ok", true);
    }

    /** 显式启动终端。 */
    @PostMapping("/start")
    public Map<String, Object> start(@RequestBody(required = false) Map<String, String> body) {
        String shell = body == null ? "CMD" : body.getOrDefault("shell", "CMD");
        if (!terminal.isRunning()) terminal.start(shell);
        return Map.of("ok", true, "running", terminal.isRunning());
    }

    /** 停止并销毁终端（清空历史）。 */
    @PostMapping("/stop")
    public Map<String, Object> stop() {
        terminal.stop();
        return Map.of("ok", true, "running", false);
    }

    private long latestSeq() {
        long max = 0;
        for (TerminalSessionManager.Chunk c : terminal.drain(0)) {
            if (c.seq > max) max = c.seq;
        }
        return max;
    }
}
