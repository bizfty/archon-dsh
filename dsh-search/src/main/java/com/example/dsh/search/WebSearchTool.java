package com.example.dsh.search;

import com.example.dsh.tool.AgentTool;
import com.example.dsh.tool.Tool;
import com.example.dsh.tool.ToolCall;
import com.example.dsh.tool.ToolContext;
import com.example.dsh.tool.ToolResult;
import com.example.dsh.tool.ToolSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Tool(name = "web_search", description = "搜索网络（需配置搜索提供者）。")
public class WebSearchTool implements AgentTool {

    private final WebSearchService searchService;

    public WebSearchTool(WebSearchService searchService) {
        this.searchService = searchService;
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public ToolSchema getSchema() {
        return ToolSchema.builder()
                .name(name())
                .description("网络搜索。")
                .addParameter("query", "string", "搜索词")
                .addParameter("max_results", "integer", "结果上限（默认 5）")
                .required("query")
                .build();
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext context) {
        String query = call.getString("query");
        if (query == null || query.isBlank()) {
            return ToolResult.failure("缺少必要参数 query");
        }
        int maxResults = call.getInt("max_results", 5);
        try {
            List<WebSearchProvider.SearchResult> results = searchService.search(query, maxResults);
            List<Map<String, String>> items = new ArrayList<>();
            for (WebSearchProvider.SearchResult r : results) {
                items.add(Map.of("title", r.title() == null ? "" : r.title(),
                        "url", r.url() == null ? "" : r.url(),
                        "snippet", r.snippet() == null ? "" : r.snippet()));
            }
            return ToolResult.success("找到 " + results.size() + " 条结果", Map.of("results", items));
        } catch (WebSearchService.NoSearchProviderException e) {
            return ToolResult.failure("搜索不可用: " + e.getMessage() + " — 改用 web_fetch 直接抓取已知 URL。");
        }
    }
}