package com.example.dsh.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查。
 */
@RestController
public class HealthController {

    @GetMapping("/healthz")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }
}
