package com.bizfty.anchon.dsh;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 应用版本信息。
 * version 在打包时由 Maven 资源过滤注入（来自 pom.xml 的 project.version）；
 * buildTimestamp 由 build-helper-maven-plugin 在 initialize 阶段生成。
 */
@Component
public class AppVersion {

    private final String version;
    private final String buildTimestamp;
    private final String startedAt;

    public AppVersion(
            @Value("${info.app.version:0.0.0}") String version,
            @Value("${info.app.build.timestamp:}") String buildTimestamp) {
        this.version = version;
        this.buildTimestamp = buildTimestamp;
        this.startedAt = Instant.now().toString();
    }

    /** 工程版本，如 0.0.1-SNAPSHOT。 */
    public String getVersion() {
        return version;
    }

    /** 打包时间戳，格式 yyyyMMdd-HHmmss，东八区。 */
    public String getBuildTimestamp() {
        return buildTimestamp;
    }

    /** 应用启动时间（运行时生成）。 */
    public String getStartedAt() {
        return startedAt;
    }

    /** 展示用版本串：version@timestamp。 */
    public String getDisplayVersion() {
        if (buildTimestamp == null || buildTimestamp.isBlank()) {
            return version;
        }
        return version + "@" + buildTimestamp;
    }
}