package io.github.haimfeng.taskcopilot.domain;

/**
 * 任务单次执行的结果状态。
 */
public enum ExecutionStatus {
    /** 执行成功（退出码为 0） */
    SUCCESS,
    /** 执行失败（退出码非 0 或发生异常） */
    FAILURE,
    /** 执行超时被强制终止 */
    TIMEOUT
}
