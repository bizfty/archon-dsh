package com.bizfty.anchon.dsh.browser.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Playwright 浏览器配置 — 对应 dsh.browser.*。
 */
@ConfigurationProperties(prefix = "dsh.browser")
public class BrowserProperties {

    private String browserType = "chromium";
    private boolean headless = true;
    private int viewportWidth = 1280;
    private int viewportHeight = 720;
    private int navigationTimeoutMs = 30_000;
    private int defaultTimeoutMs = 5_000;
    private String userAgent;
    private boolean ignoreHttpsErrors = true;
    private String locale;
    private String timezoneId;
    private String storageStateDir = "/tmp/dsh-browser-storage";
    private int maxSessions = 50;
    /** 录屏视频输出目录（Playwright recordVideoDir）。 */
    private String videoDir = "./data/browser-videos";
    /** 录屏分辨率宽。 */
    private int videoWidth = 1280;
    /** 录屏分辨率高。 */
    private int videoHeight = 720;

    public String getBrowserType() {
        return browserType;
    }

    public void setBrowserType(String browserType) {
        this.browserType = browserType;
    }

    public boolean isHeadless() {
        return headless;
    }

    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    public int getViewportWidth() {
        return viewportWidth;
    }

    public void setViewportWidth(int viewportWidth) {
        this.viewportWidth = viewportWidth;
    }

    public int getViewportHeight() {
        return viewportHeight;
    }

    public void setViewportHeight(int viewportHeight) {
        this.viewportHeight = viewportHeight;
    }

    public int getNavigationTimeoutMs() {
        return navigationTimeoutMs;
    }

    public void setNavigationTimeoutMs(int navigationTimeoutMs) {
        this.navigationTimeoutMs = navigationTimeoutMs;
    }

    public int getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(int defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public boolean isIgnoreHttpsErrors() {
        return ignoreHttpsErrors;
    }

    public void setIgnoreHttpsErrors(boolean ignoreHttpsErrors) {
        this.ignoreHttpsErrors = ignoreHttpsErrors;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getTimezoneId() {
        return timezoneId;
    }

    public void setTimezoneId(String timezoneId) {
        this.timezoneId = timezoneId;
    }

    public String getStorageStateDir() {
        return storageStateDir;
    }

    public void setStorageStateDir(String storageStateDir) {
        this.storageStateDir = storageStateDir;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = maxSessions;
    }

    public String getVideoDir() {
        return videoDir;
    }

    public void setVideoDir(String videoDir) {
        this.videoDir = videoDir;
    }

    public int getVideoWidth() {
        return videoWidth;
    }

    public void setVideoWidth(int videoWidth) {
        this.videoWidth = videoWidth;
    }

    public int getVideoHeight() {
        return videoHeight;
    }

    public void setVideoHeight(int videoHeight) {
        this.videoHeight = videoHeight;
    }
}