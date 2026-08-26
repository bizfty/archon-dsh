package com.bizfty.anchon.dsh.browser;

import com.bizfty.anchon.dsh.browser.properties.BrowserProperties;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Video;
import com.microsoft.playwright.options.ViewportSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 浏览器会话 — 每个会话拥有独立 BrowserContext + Page，支持 storageState 持久化与录屏。
 * <p>
 * 录屏：context 创建时经 {@code setRecordVideoDir} 开启（Playwright 录制须在创建时声明），
 * 会话关闭后视频文件落盘，经 {@link #videoPath()} 获取。
 */
public class PlaywrightSession {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightSession.class);

    private final String sessionId;
    private final BrowserContext context;
    private final Page page;
    private final boolean recording;
    private volatile long lastAccess;

    private PlaywrightSession(String sessionId, BrowserContext context, Page page, boolean recording) {
        this.sessionId = sessionId;
        this.context = context;
        this.page = page;
        this.recording = recording;
        this.lastAccess = System.currentTimeMillis();
    }

    /** 创建普通会话（不录屏）。 */
    public static PlaywrightSession create(Browser browser, BrowserProperties props, String sessionId) {
        return create(browser, props, sessionId, false);
    }

    /** 创建会话；recording=true 时开启录屏（视频输出到 props.getVideoDir()）。 */
    public static PlaywrightSession create(Browser browser, BrowserProperties props, String sessionId,
                                           boolean recording) {
        Browser.NewContextOptions ctxOpts = new Browser.NewContextOptions()
                .setViewportSize(new ViewportSize(props.getViewportWidth(), props.getViewportHeight()))
                .setIgnoreHTTPSErrors(props.isIgnoreHttpsErrors());
        if (recording) {
            Path videoDir = Paths.get(props.getVideoDir());
            ctxOpts.setRecordVideoDir(videoDir)
                    .setRecordVideoSize(props.getVideoWidth(), props.getVideoHeight());
        }
        if (props.getUserAgent() != null && !props.getUserAgent().isBlank()) {
            ctxOpts.setUserAgent(props.getUserAgent());
        }
        if (props.getLocale() != null && !props.getLocale().isBlank()) {
            ctxOpts.setLocale(props.getLocale());
        }
        if (props.getTimezoneId() != null && !props.getTimezoneId().isBlank()) {
            ctxOpts.setTimezoneId(props.getTimezoneId());
        }
        Path storageFile = Paths.get(props.getStorageStateDir(), sessionId + ".json");
        if (java.nio.file.Files.exists(storageFile)) {
            ctxOpts.setStorageStatePath(storageFile);
        }
        BrowserContext context = browser.newContext(ctxOpts);
        context.setDefaultNavigationTimeout(props.getNavigationTimeoutMs());
        context.setDefaultTimeout(props.getDefaultTimeoutMs());
        Page page = context.newPage();
        log.debug("Created browser session: {} (recording={})", sessionId, recording);
        return new PlaywrightSession(sessionId, context, page, recording);
    }

    public Page page() {
        this.lastAccess = System.currentTimeMillis();
        return page;
    }

    public boolean isRecording() {
        return recording;
    }

    /** 录制中的 Video 对象（context 关闭后 path() 才可用）。 */
    public Video video() {
        return page.video();
    }

    /** 视频文件路径（会话关闭后有效；未录屏或不可用返回 null）。 */
    public Path videoPath() {
        if (!recording) {
            return null;
        }
        try {
            Video video = page.video();
            return video == null ? null : video.path();
        } catch (Exception e) {
            log.debug("video path 不可用 session={}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    public boolean isActive() {
        try {
            page.url();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public long lastAccess() {
        return lastAccess;
    }

    public void close() {
        try {
            page.close();
        } catch (Exception e) {
            log.debug("Error closing page for session {}: {}", sessionId, e.getMessage());
        }
        try {
            context.close();
        } catch (Exception e) {
            log.debug("Error closing context for session {}: {}", sessionId, e.getMessage());
        }
    }
}
