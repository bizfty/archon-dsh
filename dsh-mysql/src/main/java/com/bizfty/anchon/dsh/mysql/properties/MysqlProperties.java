package com.bizfty.anchon.dsh.mysql.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MySQL 配置 — 对应 dsh.mysql.*。
 */
@ConfigurationProperties(prefix = "dsh.mysql")
public class MysqlProperties {

    private String url;
    private String username;
    private String password;
    private int queryTimeoutSeconds = 30;
    private int maxRows = 1000;
    private boolean readOnly = true;
    private String allowedDatabases;
    private int maxQueryLength = 65_536;
    private boolean piiDesensitize = true;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public String getAllowedDatabases() {
        return allowedDatabases;
    }

    public void setAllowedDatabases(String allowedDatabases) {
        this.allowedDatabases = allowedDatabases;
    }

    public int getMaxQueryLength() {
        return maxQueryLength;
    }

    public void setMaxQueryLength(int maxQueryLength) {
        this.maxQueryLength = maxQueryLength;
    }

    public boolean isPiiDesensitize() {
        return piiDesensitize;
    }

    public void setPiiDesensitize(boolean piiDesensitize) {
        this.piiDesensitize = piiDesensitize;
    }
}