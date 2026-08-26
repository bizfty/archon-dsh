package com.bizfty.anchon.dsh.jobs;

/**
 * 后台任务状态（对应 DSH jobs 的状态面）。
 */
public enum JobStatus {
    /** 运行中。 */
    RUNNING,
    /** 成功完成（exit 0）。 */
    DONE,
    /** 失败（非零退出码）。 */
    FAILED,
    /** 被 kill。 */
    KILLED
}
