package com.bizfty.anchon.dsh;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 向 /actuator/info 暴露运行时动态信息（启动时间、JVM 内存、线程数等）。
 */
@Component
public class RuntimeInfoContributor implements InfoContributor {

    private final AppVersion appVersion;

    public RuntimeInfoContributor(AppVersion appVersion) {
        this.appVersion = appVersion;
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> runtime = new LinkedHashMap<>();

        runtime.put("startedAt", appVersion.getStartedAt());
        runtime.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0);

        Runtime runtimeEnv = Runtime.getRuntime();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("maxMemoryMB", runtimeEnv.maxMemory() / (1024 * 1024));
        memory.put("totalMemoryMB", runtimeEnv.totalMemory() / (1024 * 1024));
        memory.put("freeMemoryMB", runtimeEnv.freeMemory() / (1024 * 1024));
        memory.put("usedMemoryMB",
                (runtimeEnv.totalMemory() - runtimeEnv.freeMemory()) / (1024 * 1024));
        runtime.put("memory", memory);

        MemoryMXBean memoryMX = ManagementFactory.getMemoryMXBean();
        Map<String, Object> heap = new LinkedHashMap<>();
        heap.put("committedMB", memoryMX.getHeapMemoryUsage().getCommitted() / (1024 * 1024));
        heap.put("usedMB", memoryMX.getHeapMemoryUsage().getUsed() / (1024 * 1024));
        heap.put("maxMB", memoryMX.getHeapMemoryUsage().getMax() / (1024 * 1024));
        runtime.put("heap", heap);

        Map<String, Object> threads = new LinkedHashMap<>();
        threads.put("current", Thread.activeCount());
        threads.put("peak", ManagementFactory.getThreadMXBean().getPeakThreadCount());
        threads.put("daemon", ManagementFactory.getThreadMXBean().getDaemonThreadCount());
        runtime.put("threads", threads);

        runtime.put("availableProcessors", runtimeEnv.availableProcessors());
        runtime.put("javaVersion", System.getProperty("java.version"));
        runtime.put("javaVendor", System.getProperty("java.vendor"));
        runtime.put("vmName", ManagementFactory.getRuntimeMXBean().getVmName());
        runtime.put("startTime",
                Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime()).toString());

        Map<String, Object> os = new LinkedHashMap<>();
        os.put("name", System.getProperty("os.name"));
        os.put("arch", System.getProperty("os.arch"));
        os.put("version", System.getProperty("os.version"));
        runtime.put("os", os);

        builder.withDetail("runtime", runtime);
    }
}