package com.example.dsh.api;

import com.example.dsh.user.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * 可选 API 认证过滤器（`dsh.api.auth.enabled=true` 启用，默认关闭不影响现有开放 API）。
 * <p>
 * 保护 /api/**（白名单除外）；未带有效 X-Auth-Token 返回 401。
 */
@Component
public class UserAuthFilter extends OncePerRequestFilter {

    private static final Set<String> WHITELIST = Set.of(
            "/api/users/login", "/api/users/register", "/api/users/logout",
            "/healthz");

    private final AuthService authService;
    private final boolean enabled;

    public UserAuthFilter(AuthService authService,
                          @Value("${dsh.api.auth.enabled:false}") boolean enabled) {
        this.authService = authService;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || WHITELIST.contains(path)) {
            chain.doFilter(request, response);
            return;
        }
        String token = request.getHeader("X-Auth-Token");
        if (authService.authenticate(token).isPresent()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(401);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"未认证\"}");
    }
}
