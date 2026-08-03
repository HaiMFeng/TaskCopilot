package io.github.haimfeng.taskcopilot.service;

import io.github.haimfeng.taskcopilot.domain.ExecutionStatus;
import io.github.haimfeng.taskcopilot.domain.Task;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 发送 HTTP 请求并采集结果，供"发送请求"任务类型使用。使用 JDK 标准 HttpClient，
 * 无需额外依赖。
 */
@Component
public class HttpRequestExecutor {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public CommandExecutor.ExecutionResult execute(Task task, Map<String, Object> config) {
        Instant startedAt = Instant.now();
        int timeout = task.getTimeoutSeconds() != null && task.getTimeoutSeconds() > 0
                ? task.getTimeoutSeconds() : 30;
        try {
            String url = str(config, "url");
            String method = str(config, "method").toUpperCase();
            String body = str(config, "body");
            Map<String, String> headers = parseHeaders(str(config, "headers"));

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout));
            headers.forEach(builder::header);
            if (!body.isBlank() && !"GET".equals(method)) {
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> response = client.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            boolean success = status >= 200 && status < 300;
            String out = "HTTP 请求完成\nURL: " + url + "\n方法: " + method
                    + "\n状态: " + status + "\n响应体:\n" + (response.body() == null ? "" : response.body());
            return new CommandExecutor.ExecutionResult(
                    success ? ExecutionStatus.SUCCESS : ExecutionStatus.FAILURE,
                    status, out, "", startedAt, Instant.now());
        } catch (Exception e) {
            return new CommandExecutor.ExecutionResult(ExecutionStatus.FAILURE, -1, "",
                    "请求失败: " + e.getMessage(), startedAt, Instant.now());
        }
    }

    private Map<String, String> parseHeaders(String raw) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return headers;
        }
        for (String line : raw.split("\n")) {
            int idx = line.indexOf(':');
            if (idx > 0) {
                headers.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
            }
        }
        return headers;
    }

    private static String str(Map<String, Object> config, String key) {
        Object v = config.get(key);
        return v == null ? "" : v.toString().trim();
    }
}
