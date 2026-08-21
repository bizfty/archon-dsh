package com.example.dsh.jobs;

import com.example.dsh.core.model.SessionId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 后台任务服务（对应 DSH jobs/jobs：start/get/read/kill/wait + 所有者隔离）。
 * <p>
 * 每个任务 = 一个后台进程（bash -c），输出有字节上限；句柄按所有者（会话）隔离。
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final Map<SessionId, Map<String, JobHandle>> jobs = new ConcurrentHashMap<>();
    private final Map<SessionId, Map<String, Process>> processes = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final long maxOutputBytes;

    public JobService(@Value("${dsh.jobs.max-output-bytes:1048576}") long maxOutputBytes) {
        this.maxOutputBytes = maxOutputBytes;
    }

    /**
     * 启动后台任务。
     *
     * @param kind    任务类别（如 "bash"）
     * @param command 命令（bash -c 执行）
     * @param workdir 工作目录（可空）
     * @param owner   所有者会话（隔离边界）
     */
    public JobHandle start(String kind, String command, String workdir, SessionId owner) {
        return start(kind, command, workdir, owner, Map.of());
    }

    /**
     * 启动后台任务（带受管环境变量）。
     */
    public JobHandle start(String kind, String command, String workdir, SessionId owner,
                           Map<String, String> env) {
        String id = kind + "_" + Long.toHexString(seq.incrementAndGet());
        JobHandle handle = new JobHandle(id, owner, kind, command, JobStatus.RUNNING, null, "",
                Instant.now(), null);
        jobs.computeIfAbsent(owner, k -> new ConcurrentHashMap<>()).put(id, handle);
        Thread.startVirtualThread(() -> runProcess(owner, id, command, workdir, env));
        return handle;
    }

    private void runProcess(SessionId owner, String id, String command, String workdir,
                            Map<String, String> env) {
        JobHandle handle = jobs.get(owner).get(id);
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
        if (env != null) {
            pb.environment().putAll(env);
        }
        Path dir = workdir == null || workdir.isBlank() ? Paths.get("").toAbsolutePath() : Paths.get(workdir);
        if (Files.isDirectory(dir)) {
            pb.directory(dir.toFile());
        }
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            processes.computeIfAbsent(owner, k -> new ConcurrentHashMap<>()).put(id, process);
            int exitCode = process.waitFor();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (output.length() > maxOutputBytes) {
                output = output.substring(0, (int) maxOutputBytes) + "\n[输出截断]";
            }
            JobStatus status = exitCode == 0 ? JobStatus.DONE : JobStatus.FAILED;
            update(owner, id, handle.withStatus(status, exitCode, output));
        } catch (IOException e) {
            update(owner, id, handle.withStatus(JobStatus.FAILED, -1, "启动失败: " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            update(owner, id, handle.withStatus(JobStatus.KILLED, -1, "被中断"));
        } finally {
            Map<String, Process> map = processes.get(owner);
            if (map != null) {
                map.remove(id);
            }
        }
    }

    private void update(SessionId owner, String id, JobHandle updated) {
        Map<String, JobHandle> map = jobs.get(owner);
        if (map != null) {
            map.put(id, updated);
        }
    }

    /** 按所有者取任务（隔离：其他会话不可见）。 */
    public Optional<JobHandle> get(SessionId owner, String id) {
        Map<String, JobHandle> map = jobs.get(owner);
        return map == null ? Optional.empty() : Optional.ofNullable(map.get(id));
    }

    public List<JobHandle> list(SessionId owner) {
        Map<String, JobHandle> map = jobs.get(owner);
        return map == null ? List.of() : List.copyOf(map.values());
    }

    /** 终止任务（杀进程树；仅 RUNNING 有效）。 */
    public boolean kill(SessionId owner, String id) {
        Optional<JobHandle> found = get(owner, id);
        if (found.isEmpty()) {
            return false;
        }
        JobHandle handle = found.get();
        Map<String, Process> map = processes.get(owner);
        Process process = map == null ? null : map.get(id);
        if (process != null && process.isAlive()) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
        if (handle.status() == JobStatus.RUNNING) {
            update(owner, id, handle.withStatus(JobStatus.KILLED, null,
                    (handle.output() == null ? "" : handle.output()) + "\n[killed]"));
        }
        return true;
    }

    /**
     * 等待任务完成。
     *
     * @return 完成时的句柄；超时返回 empty
     */
    public Optional<JobHandle> waitFor(SessionId owner, String id, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Optional<JobHandle> handle = get(owner, id);
            if (handle.isPresent() && handle.get().status() != JobStatus.RUNNING) {
                return handle;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
