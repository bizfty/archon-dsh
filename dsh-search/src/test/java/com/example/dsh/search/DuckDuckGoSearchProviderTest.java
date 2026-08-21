package com.example.dsh.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckDuckGoSearchProviderTest {

    @Test
    void parsesAbstractAndRelatedTopics() {
        String json = """
                {"Heading":"DeepSeek","AbstractText":"DeepSeek is a Chinese AI company.","AbstractURL":"https://en.wikipedia.org/wiki/DeepSeek",
                 "RelatedTopics":[
                   {"Text":"DeepSeek (company) — details","FirstURL":"https://duckduckgo.com/deepseek-company"},
                   {"Name":"Group","Topics":[
                      {"Text":"DeepSeek R1 — model","FirstURL":"https://duckduckgo.com/r1"}
                   ]}
                 ]}
                """;
        List<WebSearchProvider.SearchResult> results = DuckDuckGoSearchProvider.parse(json, 8);

        assertTrue(results.size() >= 3, "应解析出摘要 + 平铺 + 嵌套分组条目");
        assertTrue(results.stream().anyMatch(r -> r.snippet().contains("Chinese AI company")));
        assertTrue(results.stream().anyMatch(r -> r.url().contains("deepseek-company")));
        assertTrue(results.stream().anyMatch(r -> r.snippet().contains("R1")));
    }

    @Test
    void emptyBodyYieldsEmpty() {
        assertTrue(DuckDuckGoSearchProvider.parse("", 5).isEmpty());
        assertTrue(DuckDuckGoSearchProvider.parse(null, 5).isEmpty());
        assertTrue(DuckDuckGoSearchProvider.parse("not json", 5).isEmpty());
    }

    @Test
    void respectsMaxResults() {
        StringBuilder json = new StringBuilder("{\"RelatedTopics\":[");
        for (int i = 0; i < 10; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"Text\":\"r").append(i).append("\",\"FirstURL\":\"https://x/").append(i).append("\"}");
        }
        json.append("]}");
        List<WebSearchProvider.SearchResult> results = DuckDuckGoSearchProvider.parse(json.toString(), 3);
        assertEquals(3, results.size());
    }
}