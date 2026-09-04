package com.bizfty.anchon.dsh.hostbridge.auth;

import com.bizfty.anchon.dsh.hostbridge.json.Json;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 浏览器会话 cookie 编解码（browser-auth.ts 语义的 Java 版）。
 *
 * <p>cookie 名由规范化 authority 确定性派生；值为
 * {@code v1.<base64url(body)>.<base64url(hmac-sha256(secret, body))>}，
 * body 绑定 authority 与签发/过期时刻。HttpOnly/SameSite=Strict 等属性由
 * {@link #setCookieHeader} 输出。
 */
public final class CookieCodec {

    public static final long DAY_MILLISECONDS = 24L * 60 * 60 * 1000;

    /** 会话负载（wire body）。 */
    public record BrowserSession(String authority, long issuedAt, long expiresAt) {
    }

    /** 验签结果。 */
    public enum Verify {
        VALID, EXPIRED, BAD_AUTHORITY, BAD_SIGNATURE
    }

    private CookieCodec() {
    }

    /** 规范化 authority：host 小写、去默认端口（80/443）、IPv6 加括号。 */
    public static String normalizeAuthority(String hostPort) {
        if (hostPort == null || hostPort.isEmpty()) {
            return "";
        }
        String value = hostPort;
        int bracketEnd = value.lastIndexOf(']');
        int colon = value.lastIndexOf(':');
        String host;
        int port = -1;
        if (bracketEnd != -1) {
            host = value.substring(0, bracketEnd + 1);
            if (colon > bracketEnd) {
                port = parsePort(value.substring(colon + 1));
            }
        } else if (colon != -1 && value.indexOf(':') == colon) {
            host = value.substring(0, colon);
            port = parsePort(value.substring(colon + 1));
        } else {
            host = value;
        }
        host = host.toLowerCase();
        if (port == 80 || port == 443) {
            port = -1;
        }
        return port == -1 ? host : host + ":" + port;
    }

    private static int parsePort(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 派生 cookie 名（authority 绑定；确定性）。 */
    public static String cookieName(String authority) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(authority.getBytes(StandardCharsets.UTF_8));
            return "dsh." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(java.util.Arrays.copyOf(hash, 18));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 编码 cookie 值。 */
    public static String encode(BrowserSession session, byte[] secret) {
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(
                Json.write(Json.object()
                        .put("a", session.authority())
                        .put("i", session.issuedAt())
                        .put("e", session.expiresAt()))
                        .getBytes(StandardCharsets.UTF_8));
        String sig = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(secret, body));
        return "v1." + body + "." + sig;
    }

    /**
     * 验签并核对 authority 与过期。
     *
     * @param secret           签名密钥
     * @param cookieValue      请求携带的 cookie 值
     * @param expectedAuthority 归一化 authority（请求 Host 派生）
     * @param nowMillis        当前时刻
     */
    public static Verify verify(BrowserSession session, byte[] secret, String cookieValue,
                                String expectedAuthority, long nowMillis) {
        if (session == null) {
            return Verify.BAD_SIGNATURE;
        }
        if (!expectedAuthority.equals(session.authority())) {
            return Verify.BAD_AUTHORITY;
        }
        if (nowMillis >= session.expiresAt()) {
            return Verify.EXPIRED;
        }
        return Verify.VALID;
    }

    /**
     * 解析并验签 cookie 值。
     *
     * @return VALID 时 session 非 null；否则 session 为 null
     */
    public static Verify parseAndVerify(byte[] secret, String cookieValue, String expectedAuthority, long nowMillis) {
        if (cookieValue == null) {
            return Verify.BAD_SIGNATURE;
        }
        String[] parts = cookieValue.split("\\.");
        if (parts.length != 3 || !"v1".equals(parts[0])) {
            return Verify.BAD_SIGNATURE;
        }
        byte[] expected = hmac(secret, parts[1]);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            return Verify.BAD_SIGNATURE;
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            return Verify.BAD_SIGNATURE;
        }
        BrowserSession session;
        try {
            String body = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            var node = Json.parseObject(body);
            if (node == null) {
                return Verify.BAD_SIGNATURE;
            }
            session = new BrowserSession(node.path("a").asText(), node.path("i").asLong(),
                    node.path("e").asLong());
        } catch (RuntimeException e) {
            return Verify.BAD_SIGNATURE;
        }
        return verify(session, secret, cookieValue, expectedAuthority, nowMillis);
    }

    /** Set-Cookie 头（HttpOnly; SameSite=Strict; Path=/; Max-Age/Expires 绝对值）。 */
    public static String setCookieHeader(String name, String value, long expiresAtMillis) {
        long maxAgeSeconds = Math.max(0, (expiresAtMillis - System.currentTimeMillis()) / 1000);
        return name + "=" + value + "; Max-Age=" + maxAgeSeconds
                + "; Path=/; Expires=" + new java.util.Date(expiresAtMillis).toInstant()
                .atZone(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
                + "; HttpOnly; SameSite=Strict";
    }

    /** 从 Cookie 头取指定 cookie 值（最小实现）。 */
    public static String cookieValueFromHeader(String cookieHeader, String name) {
        if (cookieHeader == null) {
            return null;
        }
        for (String segment : cookieHeader.split(";")) {
            String part = segment.trim();
            int at = part.indexOf('=');
            if (at != -1 && part.substring(0, at).trim().equals(name)) {
                return part.substring(at + 1);
            }
        }
        return null;
    }

    private static byte[] hmac(byte[] secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
