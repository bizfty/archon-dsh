package com.example.dsh.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class DuckDuckGoSearchProvider implements WebSearchProvider {

    private final HttpClient httpClient;
    private final Duration timeout;
    private final int maxResults;

    public DuckDuckGoSearchProvider(@Value("${dsh.search.search-timeout-ms:10000}") long timeoutMs,
                                    @Value("${dsh.search.search-max-results:8}") int maxResults) {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.timeout = Duration.ofMillis(timeoutMs);
        this.maxResults = maxResults;
    }

    @Override
    public String name() {
        return "duckduckgo";
    }

    @Override
    public List<SearchResult> search(String query, int maxResults) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = URI.create("https://api.duckduckgo.com/?q=" + encoded
                + "&format=json&no_html=1&skip_disambig=1");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", "dsh-java/0.1")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return parse(response.body(), Math.min(maxResults, this.maxResults));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new WebSearchService.NoSearchProviderException("搜索请求失败: " + e.getMessage());
        }
    }

    static List<SearchResult> parse(String json, int maxResults) {
        List<SearchResult> results = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return results;
        }
        try {
            com.example.dsh.util.JsonUtils jsonUtils = new com.example.dsh.util.JsonUtils();
            Map<String, Object> root = jsonUtils.toMap(json);
            Object abstractText = root.get("AbstractText");
            Object abstractUrl = root.get("AbstractURL");
            if (abstractText != null && !String.valueOf(abstractText).isBlank()) {
                results.add(new SearchResult(
                        String.valueOf(root.getOrDefault("Heading", "")),
                        abstractUrl == null ? "" : String.valueOf(abstractUrl),
                        String.valueOf(abstractText)));
            }
            if (root.get("RelatedTopics") instanceof List<?> topics) {
                for (Object topic : topics) {
                    if (results.size() >= maxResults) {
                        break;
                    }
                    if (topic instanceof Map<?, ?> topicMap
                            && topicMap.get("Topics") instanceof List<?> nested) {
                        for (Object sub : nested) {
                            if (results.size() >= maxResults) {
                                break;
                            }
                            addIfValid(results, sub);
                        }
                    } else {
                        addIfValid(results, topic);
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        return results.size() > maxResults ? results.subList(0, maxResults) : results;
    }

    private static void addIfValid(List<SearchResult> results, Object item) {
        if (!(item instanceof Map<?, ?> raw)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) raw;
        Object text = m.get("Text");
        Object url = m.get("FirstURL");
        if (text != null && !String.valueOf(text).isBlank()) {
            results.add(new SearchResult(
                    String.valueOf(m.getOrDefault("FirstURL", "")),
                    url == null ? "" : String.valueOf(url),
                    String.valueOf(text)));
        }
    }
}