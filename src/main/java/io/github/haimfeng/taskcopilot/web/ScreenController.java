package io.github.haimfeng.taskcopilot.web;

import io.github.haimfeng.taskcopilot.service.ScreenCaptureService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.awt.Rectangle;

@RestController
@RequestMapping("/api/screen")
public class ScreenController {

    private final ScreenCaptureService screen;

    public ScreenController(ScreenCaptureService screen) {
        this.screen = screen;
    }

    /**
     * 获取最新屏幕截图（JPEG）。
     * quality 取值 0.1~1.0（默认 0.5），前端「清晰度」下拉切换。
     */
    @GetMapping(produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> shot(@RequestParam(defaultValue = "0.5") double quality) {
        if (!screen.isAvailable()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(("当前环境无桌面会话，无法截图（请以 java -jar 带桌面会话运行）").getBytes());
        }
        screen.ensureRunning();
        byte[] data = screen.getJpeg(quality);
        if (data == null) {
            return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setCacheControl("no-store");
        headers.set("X-Last-Capture", String.valueOf(screen.getLastCaptureMs()));
        if (screen.getScreenRect() != null) {
            Rectangle r = screen.getScreenRect();
            headers.set("X-Screen-Size", r.width + "x" + r.height);
        }
        return ResponseEntity.ok().headers(headers).body(data);
    }
}
