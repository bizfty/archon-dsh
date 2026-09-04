package com.bizfty.anchon.dsh.hostbridge.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 官方浏览器信任 + 会话认证闸门薄壳（对 /api/** 生效，静态资产公开）。
 * 顺序：trust 403 → cookie 401。仅 {@code hostbridge} profile。
 * 真实接入时经配置挂到 hostbridge servlet 路径（P5），不影响 archon 既有 /api。
 */
@Profile("hostbridge")
public class RequestTrustFilter extends OncePerRequestFilter {

    private final List<String> trustedHosts;
    private final BrowserSessionService sessionService;

    public RequestTrustFilter(List<String> trustedHosts, BrowserSessionService sessionService) {
        this.trustedHosts = trustedHosts;
        this.sessionService = sessionService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String host = request.getHeader("Host");
        String origin = request.getHeader("Origin");
        String secFetchSite = request.getHeader("Sec-Fetch-Site");
        RequestTrust.Trust trust = RequestTrust.decide(host, origin, secFetchSite, trustedHosts);
        if (trust != RequestTrust.Trust.TRUSTED) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        CookieCodec.Verify verify = sessionService.verifyCookie(
                request.getHeader("Cookie"), host == null ? "" : host, System.currentTimeMillis());
        if (verify != CookieCodec.Verify.VALID) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
