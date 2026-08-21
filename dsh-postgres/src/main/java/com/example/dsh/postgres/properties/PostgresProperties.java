package com.example.dsh.postgres.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PostgreSQL 配置 — 对应 dsh.postgres.*。
 */
@ConfigurationProperties(prefix = "dsh.postgres")
public class PostgresProperties {

    private String url;
    private String username;
    private String password;
    private int queryTimeoutSeconds = 30;
    private int maxRows = 1000;
    private boolean readOnly = true;
    private String allowedSchemas;
    private int maxQueryLength = 65_536;
    private boolean indexAdvisorEnabled = true;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public int getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) { this.queryTimeoutSeconds = queryTimeoutSeconds; }
    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public String getAllowedSchemas() { return allowedSchemas; }
    public void setAllowedSchemas(String allowedSchemas) { this.allowedSchemas = allowedSchemas; }
    public int getMaxQueryLength() { return maxQueryLength; }
    public void setMaxQueryLength(int maxQueryLength) { this.maxQueryLength = maxQueryLength; }
    public boolean isIndexAdvisorEnabled() { return indexAdvisorEnabled; }
    public void setIndexAdvisorEnabled(boolean indexAdvisorEnabled) { this.indexAdvisorEnabled = indexAdvisorEnabled; }
}