package com.bizfty.anchon.dsh.github;

import com.bizfty.anchon.dsh.credentials.CredentialRef;
import com.bizfty.anchon.dsh.credentials.CredentialService;
import com.bizfty.anchon.dsh.github.properties.GithubProperties;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * GitHub API 客户端 — 基于 Kohsuke github-api 封装认证、超时与重试。
 */
@Component
public class GithubApiClient {

    private static final Logger log = LoggerFactory.getLogger(GithubApiClient.class);

    private final GithubProperties properties;
    private final org.springframework.beans.factory.ObjectProvider<CredentialService> credentialProvider;
    private volatile GitHub github;

    public GithubApiClient(GithubProperties properties,
                           org.springframework.beans.factory.ObjectProvider<CredentialService> credentialProvider) {
        this.properties = properties;
        this.credentialProvider = credentialProvider;
    }

    /**
     * 解析 token：明文 token（dsh.github.token）→ 凭据引用（dsh.github.credential-ref，
     * 经 CredentialService 每操作解析）→ null。
     */
    public String resolveToken() {
        if (properties.getToken() != null && !properties.getToken().isBlank()) {
            return properties.getToken();
        }
        String ref = properties.getCredentialRef();
        if (ref != null && !ref.isBlank()) {
            CredentialService credentials = credentialProvider.getIfAvailable();
            if (credentials != null) {
                try {
                    return credentials.resolve(CredentialRef.parse(ref)).orElse(null);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }

    public synchronized GitHub github() {
        if (github != null) {
            try {
                github.getRateLimit();
                return github;
            } catch (Exception e) {
                log.debug("GitHub connection stale, reconnecting: {}", e.getMessage());
            }
        }
        this.github = connect();
        return github;
    }

    private GitHub connect() {
        int attempts = properties.getRetryMaxAttempts() + 1;
        long backoff = properties.getRetryBackoffMs();
        IOException lastError = null;
        for (int i = 0; i < attempts; i++) {
            try {
                GitHubBuilder builder = new GitHubBuilder()
                        .withEndpoint(properties.getBaseUrl());
                String token = resolveToken();
                if (token != null && !token.isBlank()) {
                    builder.withOAuthToken(token);
                }
                GitHub gh = builder.build();
                gh.getRateLimit();
                log.info("GitHub client connected (endpoint={})", properties.getBaseUrl());
                return gh;
            } catch (IOException e) {
                lastError = e;
                log.warn("GitHub connect attempt {}/{} failed: {}", i + 1, attempts, e.getMessage());
                try {
                    Thread.sleep(backoff * (1L << i));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new RuntimeException("无法连接到 GitHub: " + (lastError == null ? "unknown" : lastError.getMessage()), lastError);
    }

    /**
     * 执行带重试的操作 — 供 service 层调用。
     */
    public <T> T execute(ThrowingSupplier<T> action) {
        int attempts = properties.getRetryMaxAttempts() + 1;
        long backoff = properties.getRetryBackoffMs();
        IOException lastError = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return action.get(github());
            } catch (IOException e) {
                lastError = e;
                if (!isRetryable(e) || i == attempts - 1) {
                    break;
                }
                try {
                    Thread.sleep(backoff * (1L << i));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw new RuntimeException("GitHub 操作失败: " + (lastError == null ? "unknown" : lastError.getMessage()), lastError);
    }

    private boolean isRetryable(IOException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage();
        return msg.contains("rate limit") || msg.contains("502") || msg.contains("503")
                || msg.contains("429") || msg.contains("Connection reset") || msg.contains("timeout");
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get(GitHub github) throws IOException;
    }
}