package io.github.haimfeng.taskcopilot.tasktype;

import io.github.haimfeng.taskcopilot.domain.Task;
import io.github.haimfeng.taskcopilot.service.CommandExecutor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 任务类型（功能类别）扩展点。
 * <p>
 * 这里的"任务类型"指任务"做什么"，例如运行指令、打开应用、发送请求。每种类型拥有
 * 自己专属的配置项（{@link #configSchema()}）与执行方式（{@link #execute}）。
 * 调度维度（每日定时）由日程表统一管理，与类型无关。
 * <p>
 * 新增一种功能类别只需：
 * <ol>
 *     <li>实现本接口；</li>
 *     <li>用 {@code @Component} 注册为 Spring Bean。</li>
 * </ol>
 * {@code TaskTypeRegistry} 会自动收集，前端表单会据 schema 动态渲染。
 */
public interface TaskTypeHandler {

    /** 类型唯一标识，例如 RUN_COMMAND */
    String code();

    /** 类型显示名称 */
    String displayName();

    /** 类型说明 */
    default String description() {
        return "";
    }

    /** 前端动态表单 schema（该类型专属的配置项） */
    List<FieldSchema> configSchema();

    /**
     * 校验配置合法性，非法时抛出 {@link IllegalArgumentException}。
     *
     * @param config 反序列化后的配置
     */
    void validate(Map<String, Object> config);

    /**
     * 人类可读的功能描述，用于列表 / 详情展示，例如 "运行指令：ping 127.0.0.1"。
     * 默认仅展示类型名。
     */
    default String summary(Map<String, Object> config) {
        return displayName();
    }

    /**
     * 执行本类型的任务。
     *
     * @param task     任务实体（含命令、工作目录、超时等通用字段）
     * @param config   已反序列化的任务配置
     * @param executor 通用命令执行器，类型可复用其执行 shell 命令
     * @return 执行结果；返回 {@link Optional#empty()} 表示由通用执行器兜底
     */
    default Optional<CommandExecutor.ExecutionResult> execute(
            Task task, Map<String, Object> config, CommandExecutor executor) {
        return Optional.empty();
    }
}
