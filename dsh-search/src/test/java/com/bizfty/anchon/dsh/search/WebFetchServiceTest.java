package com.bizfty.anchon.dsh.search;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFetchServiceTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page", exchange -> {
            byte[] body = ("<html><head><style>p{color:red}</style><script>var x=1;</script></head>"
                    + "<body><h1>标题</h1><p>第一段</p><p>第二段 &amp; 更多</p></body></html>")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/big", exchange -> {
            byte[] body = new byte[600_000];
            java.util.Arrays.fill(body, (byte) 'a');
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void fetchesAndConvertsHtmlToText() {
        WebFetchService service = new WebFetchService(524_288, 10_000);
        WebFetchService.FetchResult result = service.fetch(baseUrl + "/page");
        assertTrue(result.text().contains("标题"));
        assertTrue(result.text().contains("第一段"));
        assertTrue(result.text().contains("第二段 & 更多"));
        assertTrue(!result.text().contains("<p>"));
        assertTrue(!result.text().contains("var x"));
    }

    @Test
    void rejectsNonHttpSchemes() {
        WebFetchService service = new WebFetchService(524_288, 10_000);
        assertThrows(WebFetchService.WebFetchException.class,
                () -> service.fetch("file:///etc/passwd"));
        assertThrows(WebFetchService.WebFetchException.class,
                () -> service.fetch("ftp://example.com/file"));
    }

    @Test
    void rejectsOversizedContent() {
        WebFetchService service = new WebFetchService(100_000, 10_000);
        assertThrows(WebFetchService.WebFetchException.class,
                () -> service.fetch(baseUrl + "/big"));
    }

    @Test
    void connectionFailureIsStructured() {
        WebFetchService service = new WebFetchService(524_288, 1_000);
        assertThrows(WebFetchService.WebFetchException.class,
                () -> service.fetch("http://127.0.0.1:1/nope"));
    }

    @Test
    void htmlToTextStripsTagsAndCollapsesWhitespace() {
        assertEquals("a b", WebFetchService.htmlToText("<div>a <b> b</b></div>"));
        assertEquals("x\ny", WebFetchService.htmlToText("<p>x</p><p>y</p>"));
    }
}