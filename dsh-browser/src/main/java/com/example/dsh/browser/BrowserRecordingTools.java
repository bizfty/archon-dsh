package com.example.dsh.browser;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * 浏览器录屏工具 — 开启/停止/查询录屏。
 * <p>
 * Playwright 的录屏只能在 BrowserContext 创建时开启（recordVideoDir），
 * 因此 browser_start_recording 会创建一个新的录屏会话（复用同一浏览器实例），
 * 停止时关闭会话并返回 webm 视频文件路径。
 */
public class BrowserRecordingTools {

    @Component
    @Tool(name = "browser_start_recording", description = "开启页面录屏：创建录屏会话并开始录制，返回会话 ID 与视频目录。")
    public static class StartRecordingTool extends AbstractBrowserTool {

        public StartRecordingTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_start_recording";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("开启录屏。")
                    .addParameter("session_id", "string", "录屏会话 ID（可选，默认自动生成）")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            try {
                String sessionId = call.getString("session_id");
                if (sessionId == null || sessionId.isBlank()) {
                    sessionId = "rec-" + UUID.randomUUID().toString().substring(0, 8);
                }
                com.example.dsh.browser.PlaywrightSession session =
                        browserManager.createRecordingSession(sessionId);
                return ToolResult.success("录屏已开启，会话: " + sessionId,
                        Map.of(
                                "session_id", sessionId,
                                "video_dir", browserManager.videoDir().toString(),
                                "recording", session.isRecording()));
            } catch (Exception e) {
                return ToolResult.failure("开启录屏失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_stop_recording", description = "停止录屏并关闭会话，返回视频文件路径。")
    public static class StopRecordingTool extends AbstractBrowserTool {

        public StopRecordingTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_stop_recording";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("停止录屏。")
                    .addParameter("session_id", "string", "录屏会话 ID")
                    .required("session_id")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String sessionId = call.getString("session_id");
            if (sessionId == null || sessionId.isBlank()) {
                return ToolResult.failure("缺少必要参数 session_id");
            }
            try {
                if (!browserManager.isRecording(sessionId)) {
                    return ToolResult.failure("会话未在录屏或不存在: " + sessionId);
                }
                Path video = browserManager.stopRecordingSession(sessionId);
                if (video == null || !Files.exists(video)) {
                    return ToolResult.failure("录屏停止但未找到视频文件");
                }
                long sizeBytes = Files.size(video);
                return ToolResult.success("录屏已停止",
                        Map.of(
                                "session_id", sessionId,
                                "video_path", video.toString(),
                                "size_bytes", sizeBytes,
                                "size_kb", sizeBytes / 1024));
            } catch (Exception e) {
                return ToolResult.failure("停止录屏失败: " + e.getMessage());
            }
        }
    }

    @Component
    @Tool(name = "browser_get_recording", description = "查询录屏会话状态与视频路径（未停止时路径为空）。")
    public static class GetRecordingTool extends AbstractBrowserTool {

        public GetRecordingTool(PlaywrightBrowserManager browserManager) {
            super(browserManager);
        }

        @Override
        public String name() {
            return "browser_get_recording";
        }

        @Override
        public ToolSchema getSchema() {
            return ToolSchema.builder()
                    .name(name())
                    .description("查询录屏状态。")
                    .addParameter("session_id", "string", "录屏会话 ID")
                    .required("session_id")
                    .build();
        }

        @Override
        public ToolResult execute(ToolCall call, ToolContext context) {
            String sessionId = call.getString("session_id");
            if (sessionId == null || sessionId.isBlank()) {
                return ToolResult.failure("缺少必要参数 session_id");
            }
            try {
                boolean recording = browserManager.isRecording(sessionId);
                Path video = browserManager.videoPath(sessionId);
                return ToolResult.success(recording ? "录屏进行中" : "未在录屏",
                        Map.of(
                                "session_id", sessionId,
                                "recording", recording,
                                "video_path", video == null ? "" : video.toString()));
            } catch (Exception e) {
                return ToolResult.failure("查询录屏失败: " + e.getMessage());
            }
        }
    }
}
