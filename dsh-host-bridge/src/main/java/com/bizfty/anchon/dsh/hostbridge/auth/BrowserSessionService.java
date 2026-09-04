package com.bizfty.anchon.dsh.hostbridge.auth;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * 浏览器会话服务：进程 launch token + cookie 签发（browser-auth.ts 语义）。
 *
 * <p>launch token 仅用于根 URL {@code ?token=} 交换；日常请求靠 authority 绑定签名 cookie。
 */
public final class BrowserSessionService {

    private final SecureRandom random = new SecureRandom();
    private final byte[] secret;

    public BrowserSessionService(byte[] secret) {
        this.secret = secret.clone();
    }

    /** 进程级 launch token（每次启动随机）。 */
    public static String newLaunchToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 常数时间比较 launch token。 */
    public static boolean tokenMatches(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 给 authority 签发新会话 cookie 的 Set-Cookie 头。 */
    public String issueCookieHeader(String authority, int maxAgeDays) {
        long now = System.currentTimeMillis();
        long expiresAt = now + (long) maxAgeDays * CookieCodec.DAY_MILLISECONDS;
        CookieCodec.BrowserSession session = new CookieCodec.BrowserSession(
                CookieCodec.normalizeAuthority(authority), now, expiresAt);
        String name = CookieCodec.cookieName(session.authority());
        String value = CookieCodec.encode(session, secret);
        return CookieCodec.setCookieHeader(name, value, expiresAt);
    }

    /**
     * 校验请求 cookie。
     *
     * @return VALID / EXPIRED / BAD_AUTHORITY / BAD_SIGNATURE
     */
    public CookieCodec.Verify verifyCookie(String cookieHeader, String requestAuthority, long nowMillis) {
        String authority = CookieCodec.normalizeAuthority(requestAuthority);
        String name = CookieCodec.cookieName(authority);
        String value = CookieCodec.cookieValueFromHeader(cookieHeader, name);
        if (value == null) {
            return CookieCodec.Verify.BAD_SIGNATURE;
        }
        return CookieCodec.parseAndVerify(secret, value, authority, nowMillis);
    }
}
