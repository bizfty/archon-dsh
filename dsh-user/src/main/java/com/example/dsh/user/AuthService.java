package com.example.dsh.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 认证服务 — 登录签发 token（内存会话；过期清理），token 校验/登出。
 */
@Service
public class AuthService {

    private final UserService userService;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public AuthService(UserService userService,
                       @Value("${dsh.user.auth.token-ttl-minutes:120}") long ttlMinutes) {
        this.userService = userService;
        this.ttlMillis = Math.max(1, ttlMinutes) * 60_000L;
    }

    private record Session(String userId, Instant expiresAt) {
    }

    /** 登录：成功返回 token，失败返回 empty。 */
    public Optional<String> login(String username, String password) {
        if (!userService.authenticate(username, password)) {
            return Optional.empty();
        }
        String token = "tok_" + UUID.randomUUID().toString().replace("-", "");
        userService.findByUsername(username).ifPresent(
                p -> sessions.put(token, new Session(p.id(), Instant.now().plusMillis(ttlMillis))));
        return Optional.of(token);
    }

    /** 校验 token：有效返回 userId。 */
    public Optional<String> authenticate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Session session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(session.userId());
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    public int activeSessions() {
        return sessions.size();
    }
}
