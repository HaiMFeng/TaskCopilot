package io.github.haimfeng.taskcopilot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.awt.AWTException;
import java.awt.HeadlessException;
import java.awt.GraphicsConfiguration;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageOutputStream;

/**
 * 主机屏幕截图服务（仅用于「屏幕查看」页，非系统监控指标）。
 *
 * 设计要点：
 * - 使用 JDK 标准库 java.awt.Robot 截取主屏，零额外依赖。
 * - 后台 daemon 线程按固定间隔（1s）截原图并缓存，请求时按 quality 动态编码 JPEG 返回，
 *   避免「切换清晰度」触发重新截图，对「小主机」友好。
 * - 按需启停：前端进入屏幕页发起首次请求时自动 start；持续无请求超过空闲阈值后自动 stop，
 *   不占用后台资源。
 * - 无桌面环境（Headless）降级：所有方法返回可用标记，不抛异常崩溃。
 */
@Service
public class ScreenCaptureService {

    private static final Logger log = LoggerFactory.getLogger(ScreenCaptureService.class);

    /** 截图间隔（毫秒）。 */
    private static final long CAPTURE_INTERVAL_MS = 1000;
    /** 无请求空闲超过该时长（毫秒）则自动停止截图线程。 */
    private static final long IDLE_STOP_MS = 5000;

    private final AtomicBoolean available = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<BufferedImage> latest = new AtomicReference<>();
    private final AtomicLong lastCaptureMs = new AtomicLong(0);
    private final AtomicLong lastRequestMs = new AtomicLong(0);

    private Robot robot;
    private Rectangle screenRect;        // 逻辑尺寸（用于界面展示参考）
    private Rectangle captureRect;       // 物理像素捕获区域（含 DPI 缩放还原）
    private Thread captureThread;
    private final Object lock = new Object();

    public ScreenCaptureService() {
        // Spring Boot 默认开启 headless 模式（spring.main.headless=true），
        // 会导致 java.awt.Robot 初始化失败。这里尝试强制关闭 headless 再初始化。
        if (GraphicsEnvironment.isHeadless()) {
            log.info("检测到 headless 模式已启用，尝试强制关闭以支持屏幕截图...");
            System.setProperty("java.awt.headless", "false");
        }
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice gd = ge.getDefaultScreenDevice();
            GraphicsConfiguration gc = gd.getDefaultConfiguration();
            screenRect = gc.getBounds();
            // 还原 DPI 缩放，得到物理像素捕获区域（如 2560x1600 @150% 缩放）
            var tx = gc.getDefaultTransform();
            double sx = tx.getScaleX() <= 0 ? 1.0 : tx.getScaleX();
            double sy = tx.getScaleY() <= 0 ? 1.0 : tx.getScaleY();
            captureRect = new Rectangle(0, 0,
                    (int) Math.round(screenRect.width * sx),
                    (int) Math.round(screenRect.height * sy));
            robot = new Robot(gd);
            available.set(true);
            log.info("屏幕截图服务可用，逻辑尺寸 {}x{}，物理尺寸 {}x{}（缩放 {}x{}）",
                    screenRect.width, screenRect.height,
                    captureRect.width, captureRect.height, sx, sy);
        } catch (AWTException | HeadlessException e) {
            available.set(false);
            log.warn("屏幕截图服务不可用（无桌面环境）：{}。请确认以带桌面会话方式运行（java -jar 或 IDEA 前台运行），且未设置 -Djava.awt.headless=true", e.getMessage());
        }
    }

    /** 是否处于可用（有桌面会话）状态。 */
    public boolean isAvailable() {
        return available.get();
    }

    /** 当前主屏物理尺寸。 */
    public Rectangle getScreenRect() {
        return captureRect;
    }

    /**
     * 确保截图线程运行（幂等）。前端发起首次请求时调用。
     * 同时刷新「最后请求时间」，阻止空闲自动停止。
     */
    public void ensureRunning() {
        lastRequestMs.set(System.currentTimeMillis());
        if (!available.get() || running.get()) return;
        synchronized (lock) {
            if (running.get()) return;
            running.set(true);
            captureThread = new Thread(this::captureLoop, "screen-capture");
            captureThread.setDaemon(true);
            captureThread.start();
        }
    }

    private void captureLoop() {
        while (running.get()) {
            long now = System.currentTimeMillis();
            // 空闲超时自动停止
            if (now - lastRequestMs.get() > IDLE_STOP_MS) {
                synchronized (lock) {
                    running.set(false);
                }
                log.info("屏幕截图空闲超时，已自动停止");
                break;
            }
            try {
                // Robot 在 HiDPI 下坐标系为逻辑像素，截逻辑 bounds 才能铺满无黑边；
                // 物理分辨率在 getJpeg 中通过放大绘制还原。
                BufferedImage img = robot.createScreenCapture(screenRect);
                latest.set(img);
                lastCaptureMs.set(now);
            } catch (Exception e) {
                if (running.get()) log.warn("屏幕截图失败", e);
            }
            try {
                Thread.sleep(CAPTURE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 按给定质量（0.1~1.0）返回最新截图的 JPEG 字节。无可用帧时返回 null。
     * 截图源为逻辑分辨率，这里放大绘制到物理尺寸（DPI 缩放还原），保证画面铺满无黑边。
     */
    public byte[] getJpeg(double quality) {
        if (!available.get()) return null;
        BufferedImage src = latest.get();
        if (src == null) return null;
        BufferedImage out = upscaleToPhysical(src);
        float q = (float) Math.max(0.1, Math.min(1.0, quality));
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            var params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(q);
            }
            writer.setOutput(ios);
            writer.write(null, new IIOImage(out, null, null), params);
            writer.dispose();
            return baos.toByteArray();
        } catch (IOException e) {
            log.warn("屏幕截图编码失败", e);
            return null;
        }
    }

    /** 将逻辑分辨率截图放大绘制到物理像素尺寸，避免黑边与变形。 */
    private BufferedImage upscaleToPhysical(BufferedImage src) {
        int pw = captureRect.width;
        int ph = captureRect.height;
        if (src.getWidth() == pw && src.getHeight() == ph) return src;
        BufferedImage scaled = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, pw, ph, null);
        g.dispose();
        return scaled;
    }

    /** 最近一次截图时间戳（毫秒）。 */
    public long getLastCaptureMs() {
        return lastCaptureMs.get();
    }

    @PreDestroy
    public void destroy() {
        synchronized (lock) {
            running.set(false);
        }
        if (captureThread != null) {
            captureThread.interrupt();
        }
        latest.set(null);
    }
}
