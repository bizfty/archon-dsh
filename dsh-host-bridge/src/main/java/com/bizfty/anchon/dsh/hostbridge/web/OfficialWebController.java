package com.bizfty.anchon.dsh.hostbridge.web;

import com.bizfty.anchon.dsh.hostbridge.auth.BrowserSessionService;
import com.bizfty.anchon.dsh.hostbridge.auth.CookieCodec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 官方 client 伺服控制器（browser-auth + dist + /plugins bundle + boot 注入）。
 * 仅 {@code hostbridge} profile。launch token 于启动随机生成并打日志，
 * {@code GET /?token=} 交换签名 cookie → 302 /；已认证 GET / 回注入后 index.html。
 */
@RestController
@Profile("hostbridge")
public class OfficialWebController {

    private final BrowserSessionService sessions;
    private final OfficialWebAssets assets;
    private final Path webRoot;
    private final String launchToken;

    public OfficialWebController(BrowserSessionService sessions, OfficialWebAssets assets,
                                 Path webRoot) {
        this.sessions = sessions;
        this.assets = assets;
        this.webRoot = webRoot;
        this.launchToken = BrowserSessionService.newLaunchToken();
    }

    /** 启动后暴露登录 URL（冒烟用；生产由 host 打印）。 */
    public String launchToken() {
        return launchToken;
    }

    @GetMapping(value = "/", produces = "text/html;charset=UTF-8")
    public void index(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String token = request.getParameter("token");
        if (token != null && BrowserSessionService.tokenMatches(token, launchToken)) {
            response.setHeader("Set-Cookie", sessions.issueCookieHeader(authority(request), 30));
            response.sendRedirect(request.getContextPath() + "/");
            return;
        }
        String cookie = request.getHeader("Cookie");
        CookieCodec.Verify verify = sessions.verifyCookie(cookie, authority(request), System.currentTimeMillis());
        if (verify != CookieCodec.Verify.VALID) {
            System.out.println("hostbridge web: unauthenticated root (verify=" + verify + "); open with /?token=" + launchToken);
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "hostbridge: open /?token=" + launchToken);
            return;
        }
        Path index = webRoot.resolve("index.html");
        String html = Files.exists(index) ? Files.readString(index, StandardCharsets.UTF_8)
                : "<html><body>missing dist index.html at " + webRoot + "</body></html>";
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(assets.renderIndex(html));
    }

    /** /plugins/&lt;id&gt;/client.js[.map] —— 官方 client 动态模块。 */
    @GetMapping(value = "/plugins/**", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public void plugin(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String tail = tail(request, "/plugins/");
        boolean map = tail.endsWith(".map");
        if (map) tail = tail.substring(0, tail.length() - 4);
        String id = decodePath(tail);
        id = id.endsWith("/client.js") ? id.substring(0, id.length() - "/client.js".length()) : id;
        Path bundle = assets.bundlePath(id);
        if (bundle == null || !Files.isRegularFile(bundle)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "no client bundle for " + id);
            return;
        }
        byte[] body = Files.readAllBytes(map ? Path.of(bundle + ".map") : bundle);
        if (!map) response.setContentType("text/javascript;charset=UTF-8");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    /** dist 静态产物与 SPA 兜底。 */
    @GetMapping({"/assets/**", "/favicon.svg", "/manifest.webmanifest"})
    public void asset(HttpServletRequest request, HttpServletResponse response) throws IOException {
        serveStatic(request, response);
    }

    private void serveStatic(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String uri = request.getRequestURI();
        String relative = uri.substring(request.getContextPath().length());
        if (relative.startsWith("/")) relative = relative.substring(1);
        Path file = webRoot.resolve(relative).normalize();
        if (!file.startsWith(webRoot.normalize()) || !Files.isRegularFile(file)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        response.setContentType(contentType(file));
        byte[] body = Files.readAllBytes(file);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private String tail(HttpServletRequest request, String prefix) {
        String uri = request.getRequestURI();
        int cut = request.getContextPath().length() + prefix.length();
        return uri.substring(Math.min(cut, uri.length()));
    }

    private static String decodePath(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String authority(HttpServletRequest request) {
        return request.getServerName() + (request.getServerPort() == 80 ? "" : ":" + request.getServerPort());
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".js")) return "text/javascript;charset=UTF-8";
        if (name.endsWith(".css")) return "text/css;charset=UTF-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".woff")) return "font/woff";
        if (name.endsWith(".ttf")) return "font/ttf";
        if (name.endsWith(".webmanifest")) return "application/manifest+json";
        if (name.endsWith(".html")) return "text/html;charset=UTF-8";
        return MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }
}
