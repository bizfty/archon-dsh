package com.example.dsh.search;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;

import java.util.Map;

@Tool(name = "web_fetch", description = "抓取一个 http(s) URL 的内容并转为纯文本。")
public class WebFetchTool implements AgentTool {

    private final WebFetchService fetchService;

    public WebFetchTool(WebFetchService fetchService) {
        this.fetchService = fetchService;
    }

    @Override
    public String name() {
        return "web_fetch";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("抓取网页内容。")
                .addParameter("url", "string", "http(s) URL")
                .required("url")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String url = call.getString("url");
        if (url == null || url.isBlank()) {
            return ToolResult.failure("缺少必要参数 url");
        }
        try {
            WebFetchService.FetchResult result = fetchService.fetch(url);
            return ToolResult.success(result.text(), Map.of(
                    "url", result.url(), "bytes", result.bytes(), "truncated", result.truncated()));
        } catch (WebFetchService.WebFetchException e) {
            return ToolResult.failure("抓取失败: " + e.getMessage() + " — 请检查 URL 或稍后重试。");
        }
    }
}