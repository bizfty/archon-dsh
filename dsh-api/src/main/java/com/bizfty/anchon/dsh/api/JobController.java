package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.core.model.SessionId;
import com.bizfty.anchon.dsh.jobs.JobHandle;
import com.bizfty.anchon.dsh.jobs.JobService;
import com.bizfty.anchon.dsh.jobs.JobStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 后台任务 API — 会话头部任务列表（对应 DSH api/gateway 的 session/jobs 帧
 * 与官方 web 的 background-job list）。
 * <p>
 * 每个任务归属一个会话（owner 隔离）；前端据此展示运行中任务数、任务明细并可 kill。
 */
@RestController
@RequestMapping("/api/sessions")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    /** 会话的后台任务列表（含运行中/已结束；运行中优先）。 */
    @GetMapping("/{sessionId}/jobs")
    public List<JobDto> listJobs(@PathVariable String sessionId) {
        SessionId owner = SessionId.of(sessionId);
        return jobService.list(owner).stream()
                .sorted((a, b) -> {
                    // 运行中在前，其余按创建时间倒序
                    boolean ra = a.status() == JobStatus.RUNNING;
                    boolean rb = b.status() == JobStatus.RUNNING;
                    if (ra != rb) return ra ? -1 : 1;
                    return b.createdAt().compareTo(a.createdAt());
                })
                .map(JobDto::from)
                .toList();
    }

    /** 终止指定后台任务（仅本会话可见）。 */
    @PostMapping("/{sessionId}/jobs/{jobId}/kill")
    public Map<String, Object> killJob(@PathVariable String sessionId, @PathVariable String jobId) {
        boolean killed = jobService.kill(SessionId.of(sessionId), jobId);
        return Map.of("ok", killed, "jobId", jobId);
    }

    /** 后台任务视图（供前端任务列表展示）。 */
    public record JobDto(
            String id,
            String kind,
            String command,
            String status,
            Integer exitCode,
            String output,
            long durationMs,
            String createdAt,
            String completedAt) {

        static JobDto from(JobHandle handle) {
            long durationMs = handle.completedAt() == null
                    ? Duration.between(handle.createdAt(), Instant.now()).toMillis()
                    : Duration.between(handle.createdAt(), handle.completedAt()).toMillis();
            return new JobDto(
                    handle.id(),
                    handle.kind(),
                    handle.command(),
                    handle.status().name().toLowerCase(),
                    handle.exitCode(),
                    Optional.ofNullable(handle.output()).orElse(""),
                    durationMs,
                    handle.createdAt().toString(),
                    handle.completedAt() == null ? null : handle.completedAt().toString());
        }
    }
}
