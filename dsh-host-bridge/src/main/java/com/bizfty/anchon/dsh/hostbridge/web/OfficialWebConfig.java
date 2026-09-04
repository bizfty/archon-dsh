package com.bizfty.anchon.dsh.hostbridge.web;

import com.bizfty.anchon.dsh.hostbridge.auth.BrowserSessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;
import java.security.SecureRandom;

/**
 * 官方 client 伺服装配：launch/cookie 会话服务 + 静态产物（dist + /plugins bundles）清单。
 * 默认产物根取仓库 external/deepseek 相对路径（user.dir=archon 仓库根时直接可用）；
 * 部署可用 dsh.hostbridge.webroot / dsh.hostbridge.pluginsRoot / dsh.hostbridge.secret 覆盖。
 */
@Configuration
@Profile("hostbridge")
public class OfficialWebConfig {

    @Bean
    public BrowserSessionService hostBridgeSessions(
            @Value("${dsh.hostbridge.secret:}") String secret) {
        byte[] key;
        if (secret != null && !secret.isBlank()) {
            key = secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } else {
            key = new byte[32];
            new SecureRandom().nextBytes(key);
        }
        return new BrowserSessionService(key);
    }

    @Bean
    public OfficialWebAssets officialWebAssets(
            @Value("${dsh.hostbridge.pluginsRoot:${user.dir}/external/deepseek/packages}") String pluginsRoot) {
        return new OfficialWebAssets(Path.of(pluginsRoot));
    }

    @Bean
    public Path hostBridgeWebRoot(
            @Value("${dsh.hostbridge.webroot:${user.dir}/external/deepseek/apps/web/dist}") String webRoot) {
        return Path.of(webRoot);
    }
}
