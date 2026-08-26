package com.bizfty.anchon.dsh.credentials;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 环境变量提供者 — 从系统环境解析密钥。
 */
@Component
public class EnvCredentialProvider implements CredentialProvider {

    @Override
    public String name() {
        return "env";
    }

    @Override
    public Optional<String> resolve(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }
}
