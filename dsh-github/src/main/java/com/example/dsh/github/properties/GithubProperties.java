package com.example.dsh.github.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * GitHub 集成配置 — 对应 dsh.github.*。
 */
@ConfigurationProperties(prefix = "dsh.github")
public class GithubProperties {

    private String token;
    /** 凭据引用（provider:key，经 CredentialService 解析；优先于 token 明文）。 */
    private String credentialRef;
    private String baseUrl = "https://api.github.com";
    private int connectTimeoutMs = 5_000;
    private int readTimeoutMs = 30_000;
    private int retryMaxAttempts = 2;
    private long retryBackoffMs = 500;
    private String defaultOwner;
    private boolean autoPaginate = true;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getCredentialRef() {
        return credentialRef;
    }

    public void setCredentialRef(String credentialRef) {
        this.credentialRef = credentialRef;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public void setRetryMaxAttempts(int retryMaxAttempts) {
        this.retryMaxAttempts = retryMaxAttempts;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = retryBackoffMs;
    }

    public String getDefaultOwner() {
        return defaultOwner;
    }

    public void setDefaultOwner(String defaultOwner) {
        this.defaultOwner = defaultOwner;
    }

    public boolean isAutoPaginate() {
        return autoPaginate;
    }

    public void setAutoPaginate(boolean autoPaginate) {
        this.autoPaginate = autoPaginate;
    }
}