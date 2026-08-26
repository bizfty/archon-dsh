package com.bizfty.anchon.dsh.search;

import com.bizfty.anchon.dsh.tool.AgentTool;
import com.bizfty.anchon.dsh.tool.Tool;
import com.bizfty.anchon.dsh.tool.ToolCall;
import com.bizfty.anchon.dsh.tool.ToolContext;
import com.bizfty.anchon.dsh.tool.ToolResult;
import com.bizfty.anchon.dsh.tool.ToolSchema;

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

    /** 只读网络查询，无共享可变状态 — 并发安全（可并行执行）。 */
    @Override
    public boolean isConcurrencySafe() {
        return true;
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