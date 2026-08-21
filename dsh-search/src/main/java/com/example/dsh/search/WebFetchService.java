package com.example.dsh.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class WebFetchService {

    private final HttpClient httpClient;
    private final long maxBytes;
    private final Duration timeout;

    public WebFetchService(@Value("${dsh.search.fetch-max-bytes:524288}") long maxBytes,
                           @Value("${dsh.search.fetch-timeout-ms:10000}") long timeoutMs) {
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.maxBytes = maxBytes;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    public record FetchResult(String url, String text, int bytes, boolean truncated) {
    }

    public FetchResult fetch(String url) {
        URI uri = parseUrl(url);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", "dsh-java/0.1")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] body = response.body();
            if (body.length > maxBytes) {
                throw new WebFetchException("内容超过上限: " + body.length + " bytes > " + maxBytes);
            }
            String html = new String(body, StandardCharsets.UTF_8);
            String text = htmlToText(html);
            return new FetchResult(url, text, body.length, false);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new WebFetchException("抓取失败: " + e.getMessage());
        }
    }

    private URI parseUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
                throw new WebFetchException("仅支持 http/https: " + url);
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new WebFetchException("无效 URL（无主机）: " + url);
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new WebFetchException("无效 URL: " + url);
        }
    }

    static String htmlToText(String html) {
        String s = html;
        s = s.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        s = s.replaceAll("(?is)<br\\s*/?>", "\n");
        s = s.replaceAll("(?is)</(p|div|h[1-6]|li|pre|tr)>", "\n");
        s = s.replaceAll("(?s)<[^>]+>", " ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        s = s.replaceAll("[ \\t]+", " ");
        s = s.replaceAll(" ?\\n ?", "\n");
        s = s.replaceAll("\\n\\s*\\n+", "\n");
        return s.trim();
    }

    public static final class WebFetchException extends RuntimeException {
        public WebFetchException(String message) {
            super(message);
        }
    }
}