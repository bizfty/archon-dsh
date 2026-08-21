package com.example.dsh.jobs;

import com.example.dsh.core.model.SessionId;

import java.time.Instant;

/**
 * 后台任务句柄（对应 DSH jobs 的 Job 句柄：status/output/exitCode）。
 * <p>
 * 不可变；状态更新通过 {@link JobService} 以新实例替换。
 */
public record JobHandle(
        String id,
        SessionId owner,
        String kind,
        String command,
        JobStatus status,
        Integer exitCode,
        String output,
        Instant createdAt,
        Instant completedAt) {

    public JobHandle withStatus(JobStatus status, Integer exitCode, String output) {
        return new JobHandle(id, owner, kind, command, status, exitCode, output,
                createdAt, completedAt != null ? completedAt : Instant.now());
    }
}
