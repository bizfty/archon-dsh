package com.bizfty.anchon.dsh.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查，返回状态与版本信息。
 */
@RestController
public class HealthController {

    @Value("${info.app.version:0.0.0}")
    private String version;

    @Value("${info.app.build.timestamp:}")
    private String buildTimestamp;

    @GetMapping("/healthz")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("version", version);
        result.put("buildTimestamp", buildTimestamp);
        result.put("timestamp", Instant.now().toString());
        return result;
    }
}