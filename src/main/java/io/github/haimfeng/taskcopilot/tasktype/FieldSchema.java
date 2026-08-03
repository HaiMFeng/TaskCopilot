package io.github.haimfeng.taskcopilot.tasktype;

import java.util.List;
import java.util.Map;

/**
 * 前端动态表单的字段描述。
 * <p>
 * 后端通过 {@code /api/task-types} 下发，前端据此渲染触发配置表单，
 * 新增任务类型时前端无需改动。
 *
 * @param name     字段名（对应 configJson 的 key）
 * @param label    显示名称
 * @param type     字段类型：number / text / select / time / boolean
 * @param required 是否必填
 * @param defaultValue 默认值，可为 null
 * @param min      数值最小值，可为 null
 * @param max      数值最大值，可为 null
 * @param options  下拉选项，key=value、label=显示文本
 * @param help     字段提示
 */
public record FieldSchema(
        String name,
        String label,
        String type,
        boolean required,
        Object defaultValue,
        Integer min,
        Integer max,
        List<Map<String, Object>> options,
        String help
) {

    public static FieldSchema number(String name, String label, int min, int max, Object defaultValue) {
        return new FieldSchema(name, label, "number", true, defaultValue, min, max, null, null);
    }

    public static FieldSchema text(String name, String label, boolean required, String help) {
        return new FieldSchema(name, label, "text", required, null, null, null, null, help);
    }

    public static FieldSchema select(String name, String label, List<Map<String, Object>> options, Object defaultValue) {
        return new FieldSchema(name, label, "select", true, defaultValue, null, null, options, null);
    }

    public static FieldSchema time(String name, String label, String defaultValue) {
        return new FieldSchema(name, label, "time", true, defaultValue, null, null, null, null);
    }

    public static FieldSchema textarea(String name, String label, String help) {
        return new FieldSchema(name, label, "textarea", false, null, null, null, null, help);
    }

    /** 构造下拉选项 */
    public static Map<String, Object> option(String value, String label) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("value", value);
        m.put("label", label);
        return m;
    }
}
