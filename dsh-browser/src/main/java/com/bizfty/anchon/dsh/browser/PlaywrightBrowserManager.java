package com.bizfty.anchon.dsh.browser;

import com.bizfty.anchon.dsh.browser.properties.BrowserProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Playwright 浏览器生命周期管理器 — 单例浏览器实例 + 会话级 BrowserContext 隔离。
 * <p>
 * 对应 DSH browser 的 BrowserManager；每个 sessionId 拥有独立的
 * BrowserContext 和 Page，避免状态串扰。
 */
@Component
public class PlaywrightBrowserManager {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightBrowserManager.class);

    private final BrowserProperties properties;
    private final Map<String, PlaywrightSession> sessions = new ConcurrentHashMap<>();

    private Playwright playwright;
    private Browser browser;
    private volatile boolean initialized;

    public PlaywrightBrowserManager(BrowserProperties properties) {
        this.properties = properties;
    }

    public synchronized void ensureInitialized() {
        if (initialized) {
            return;
        }
        this.playwright = Playwright.create();
        BrowserType type = switch (properties.getBrowserType()) {
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            default -> playwright.chromium();
        };
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                .setHeadless(properties.isHeadless());
        this.browser = type.launch(launchOptions);
        this.initialized = true;
        log.info("Playwright browser [{}] launched, headless={}", properties.getBrowserType(), properties.isHeadless());
    }

    public PlaywrightSession getOrCreateSession(String sessionId) {
        ensureInitialized();
        PlaywrightSession session = sessions.get(sessionId);
        if (session != null && session.isActive()) {
            return session;
        }
        if (sessions.size() >= properties.getMaxSessions()) {
            evictLeastRecentlyUsed();
        }
        PlaywrightSession newSession = PlaywrightSession.create(browser, properties, sessionId);
        sessions.put(sessionId, newSession);
        return newSession;
    }

    /** 创建开启录屏的会话（context 创建时即启动录制，视频输出到 videoDir）。 */
    public PlaywrightSession createRecordingSession(String sessionId) {
        ensureInitialized();
        closeSession(sessionId); // 同名旧会话先关，避免视频混淆
        if (sessions.size() >= properties.getMaxSessions()) {
            evictLeastRecentlyUsed();
        }
        PlaywrightSession newSession = PlaywrightSession.create(browser, properties, sessionId, true);
        sessions.put(sessionId, newSession);
        log.info("Started recording browser session: {}", sessionId);
        return newSession;
    }

    /** 停止录屏并关闭会话，返回视频文件路径（无则 null）。 */
    public java.nio.file.Path stopRecordingSession(String sessionId) {
        PlaywrightSession session = sessions.get(sessionId);
        if (session == null || !session.isRecording()) {
            return null;
        }
        sessions.remove(sessionId);
        // 先记录视频路径再关闭（关闭后 path() 才可用）
        session.close();
        return session.videoPath();
    }

    /** 会话是否正在录屏。 */
    public boolean isRecording(String sessionId) {
        PlaywrightSession session = sessions.get(sessionId);
        return session != null && session.isRecording();
    }

    /** 录屏视频输出目录。 */
    public Path videoDir() {
        return Paths.get(properties.getVideoDir());
    }

    /** 会话视频文件路径（停止前可能为 null）。 */
    public Path videoPath(String sessionId) {
        PlaywrightSession session = sessions.get(sessionId);
        return session == null ? null : session.videoPath();
    }

    public void closeSession(String sessionId) {
        PlaywrightSession session = sessions.remove(sessionId);
        if (session != null) {
            session.close();
        }
    }

    public Page getPage(String sessionId) {
        return getOrCreateSession(sessionId).page();
    }

    private void evictLeastRecentlyUsed() {
        String oldest = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, PlaywrightSession> e : sessions.entrySet()) {
            long last = e.getValue().lastAccess();
            if (last < oldestTime) {
                oldestTime = last;
                oldest = e.getKey();
            }
        }
        if (oldest != null) {
            closeSession(oldest);
            log.info("Evicted LRU browser session: {}", oldest);
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        for (String sid : sessions.keySet()) {
            closeSession(sid);
        }
        if (browser != null) {
            browser.close();
            browser = null;
        }
        if (playwright != null) {
            playwright.close();
            playwright = null;
        }
        initialized = false;
        log.info("Playwright browser shutdown complete");
    }
}