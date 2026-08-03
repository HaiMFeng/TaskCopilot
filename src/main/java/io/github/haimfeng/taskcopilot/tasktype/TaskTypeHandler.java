package io.github.haimfeng.taskcopilot.tasktype;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 任务类型扩展点。
 * <p>
 * 新增一种触发方式（一次性、固定间隔、Cron、依赖触发等）只需：
 * <ol>
 *     <li>实现本接口；</li>
 *     <li>用 {@code @Component} 注册为 Spring Bean。</li>
 * </ol>
 * {@code TaskTypeRegistry} 会自动收集，调度器与前端表单均无需修改。
 */
public interface TaskTypeHandler {

    /** 类型唯一标识，例如 DAILY */
    String code();

    /** 类型显示名称 */
    String displayName();

    /** 类型说明 */
    default String description() {
        return "";
    }

    /** 前端表单 schema */
    List<FieldSchema> configSchema();

    /**
     * 校验配置合法性，非法时抛出 {@link IllegalArgumentException}。
     *
     * @param config 反序列化后的配置
     */
    void validate(Map<String, Object> config);

    /**
     * 计算给定基准时间之后的下一次触发时间。
     *
     * @param config 触发配置
     * @param from   基准时间
     * @return 下次触发时间；返回空表示不再触发（例如一次性任务已执行）
     */
    Optional<Instant> nextExecution(Map<String, Object> config, Instant from);

    /** 人类可读的触发描述，用于列表展示，例如 "每日 08:30" */
    default String summary(Map<String, Object> config) {
        return displayName();
    }
}
