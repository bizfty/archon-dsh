package com.example.dsh.browser;

import com.example.dsh.tool.Tool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证录屏工具注解声明与结构（纯单元测试，不拉起 Spring 上下文）。
 */
class BrowserRecordingToolsRegistrationTest {

    @Test
    void recordingToolsDeclaredWithExpectedNames() {
        assertThat(BrowserRecordingTools.StartRecordingTool.class.getAnnotation(Tool.class).name())
                .isEqualTo("browser_start_recording");
        assertThat(BrowserRecordingTools.StopRecordingTool.class.getAnnotation(Tool.class).name())
                .isEqualTo("browser_stop_recording");
        assertThat(BrowserRecordingTools.GetRecordingTool.class.getAnnotation(Tool.class).name())
                .isEqualTo("browser_get_recording");
    }

    @Test
    void toolsAreAgentToolImplementations() {
        assertThat(com.example.dsh.tool.AgentTool.class.isAssignableFrom(BrowserRecordingTools.StartRecordingTool.class)).isTrue();
        assertThat(com.example.dsh.tool.AgentTool.class.isAssignableFrom(BrowserRecordingTools.StopRecordingTool.class)).isTrue();
        assertThat(com.example.dsh.tool.AgentTool.class.isAssignableFrom(BrowserRecordingTools.GetRecordingTool.class)).isTrue();
    }
}
