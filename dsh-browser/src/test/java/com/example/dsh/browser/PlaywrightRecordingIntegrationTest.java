package com.example.dsh.browser;

import com.example.dsh.browser.properties.BrowserProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 录屏集成测试 — 真实启动 Playwright，录制页面交互，验证 webm 视频落盘。
 * <p>
 * 需要本机已安装 Playwright 浏览器（chromium）。环境无浏览器时跳过。
 */
class PlaywrightRecordingIntegrationTest {

    private static final boolean BROWSER_AVAILABLE =
            Files.exists(Paths.get("/home/john/.cache/ms-playwright"));

    @TempDir
    Path tempDir;

    @Test
    void recordVideoAndStopProducesWebmFile() throws Exception {
        if (!BROWSER_AVAILABLE) {
            return; // 无浏览器环境跳过
        }
        BrowserProperties props = new BrowserProperties();
        props.setHeadless(true);
        props.setVideoDir(tempDir.resolve("videos").toString());

        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(props);
        try {
            String sessionId = "it-rec-" + System.currentTimeMillis();
            PlaywrightSession session = manager.createRecordingSession(sessionId);
            assertThat(session.isRecording()).isTrue();

            // 真实页面操作：导航 + 点击 + 输入，制造录制内容
            session.page().navigate("data:text/html,<title>rec</title><button onclick=\"document.title='clicked'\">Go</button>");
            session.page().locator("button").click();
            session.page().waitForTimeout(500);
            assertThat(session.page().title()).isEqualTo("clicked");

            // 停止录屏 → 关闭会话 → 视频路径可用
            Path video = manager.stopRecordingSession(sessionId);
            assertThat(video).isNotNull();
            assertThat(Files.exists(video)).as("video file should exist: %s", video).isTrue();
            assertThat(video.toString()).endsWith(".webm");
            assertThat(Files.size(video)).isGreaterThan(0);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void stopNonRecordingSessionReturnsNull() {
        BrowserProperties props = new BrowserProperties();
        props.setHeadless(true);
        PlaywrightBrowserManager manager = new PlaywrightBrowserManager(props);
        try {
            assertThat(manager.stopRecordingSession("never-existed")).isNull();
            assertThat(manager.isRecording("never-existed")).isFalse();
        } finally {
            manager.shutdown();
        }
    }
}
