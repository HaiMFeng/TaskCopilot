package io.github.haimfeng.taskcopilot.tasktype;

import io.github.haimfeng.taskcopilot.domain.Task;
import io.github.haimfeng.taskcopilot.service.CommandExecutor;
import io.github.haimfeng.taskcopilot.service.HttpRequestExecutor;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 发送请求：向指定 URL 发起 HTTP 请求（GET/POST 等），结果以状态码与响应体呈现，
 * 便于监控接口健康、定时探活、触发 Webhook 等场景。
 */
@Component
public class HttpRequestTaskTypeHandler implements TaskTypeHandler {

    private final HttpRequestExecutor httpRequestExecutor;

    public HttpRequestTaskTypeHandler(HttpRequestExecutor httpRequestExecutor) {
        this.httpRequestExecutor = httpRequestExecutor;
    }

    @Override
    public String code() {
        return "HTTP_REQUEST";
    }

    @Override
    public String displayName() {
        return "发送请求";
    }

    @Override
    public String description() {
        return "定时调用接口、探活或触发 Webhook，无需编写 curl 命令";
    }

    @Override
    public List<FieldSchema> configSchema() {
        return List.of(
                FieldSchema.text("url", "请求地址", true, "例如：https://api.example.com/health"),
                FieldSchema.select("method", "方法", List.of(
                        FieldSchema.option("GET", "GET"),
                        FieldSchema.option("POST", "POST"),
                        FieldSchema.option("PUT", "PUT"),
                        FieldSchema.option("DELETE", "DELETE")
                ), "GET"),
                FieldSchema.textarea("headers", "请求头", "每行一个，例如：Authorization: Bearer xxx"),
                FieldSchema.textarea("body", "请求体", "POST/PUT 时填写，JSON 格式"),
                FieldSchema.time("time", "执行时间", "08:30")
        );
    }

    @Override
    public void validate(Map<String, Object> config) {
        Object url = config.get("url");
        if (!(url instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("请求地址不能为空");
        }
        if (!text.startsWith("http://") && !text.startsWith("https://")) {
            throw new IllegalArgumentException("请求地址需以 http:// 或 https:// 开头");
        }
    }

    @Override
    public String summary(Map<String, Object> config) {
        return "发送请求：" + str(config, "method") + " " + str(config, "url");
    }

    @Override
    public Optional<CommandExecutor.ExecutionResult> execute(
            Task task, Map<String, Object> config, CommandExecutor executor) {
        return Optional.of(httpRequestExecutor.execute(task, config));
    }

    private static String str(Map<String, Object> config, String key) {
        Object v = config.get(key);
        return v == null ? "" : v.toString().trim();
    }
}
