package com.bizfty.anchon.dsh.hostbridge.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * browser-session cookie 测试：authority 归一化、确定性名、签发/验签/篡改/过期/错域。
 */
class CookieCodecTest {

    private static final byte[] SECRET = "spike-secret".getBytes(StandardCharsets.UTF_8);

    @Test
    void normalizesAuthorities() {
        assertEquals("localhost", CookieCodec.normalizeAuthority("LOCALHOST"));
        assertEquals("localhost:8080", CookieCodec.normalizeAuthority("localhost:8080"));
        assertEquals("example.com", CookieCodec.normalizeAuthority("Example.COM:80"));
        assertEquals("[::1]:8080", CookieCodec.normalizeAuthority("[::1]:8080"));
    }

    @Test
    void cookieNameIsDeterministicPerAuthority() {
        assertEquals(CookieCodec.cookieName("localhost:8080"), CookieCodec.cookieName("localhost:8080"));
        assertNotEquals(CookieCodec.cookieName("localhost:8080"), CookieCodec.cookieName("localhost:9090"));
    }

    @Test
    void encodeThenVerifyValid() {
        long now = System.currentTimeMillis();
        CookieCodec.BrowserSession session = new CookieCodec.BrowserSession("localhost:8080", now, now + 60_000);
        String value = CookieCodec.encode(session, SECRET);
        assertEquals(CookieCodec.Verify.VALID,
                CookieCodec.parseAndVerify(SECRET, value, "localhost:8080", now + 1_000));
    }

    @Test
    void tamperedCookieFails() {
        long now = System.currentTimeMillis();
        CookieCodec.BrowserSession session = new CookieCodec.BrowserSession("localhost:8080", now, now + 60_000);
        String value = CookieCodec.encode(session, SECRET);
        String tampered = value + "x";
        assertEquals(CookieCodec.Verify.BAD_SIGNATURE,
                CookieCodec.parseAndVerify(SECRET, tampered, "localhost:8080", now));
    }

    @Test
    void expiredCookieFails() {
        long now = System.currentTimeMillis();
        CookieCodec.BrowserSession session = new CookieCodec.BrowserSession("localhost:8080", now - 120_000, now - 60_000);
        String value = CookieCodec.encode(session, SECRET);
        assertEquals(CookieCodec.Verify.EXPIRED,
                CookieCodec.parseAndVerify(SECRET, value, "localhost:8080", now));
    }

    @Test
    void wrongAuthorityFails() {
        long now = System.currentTimeMillis();
        CookieCodec.BrowserSession session = new CookieCodec.BrowserSession("localhost:8080", now, now + 60_000);
        String value = CookieCodec.encode(session, SECRET);
        assertEquals(CookieCodec.Verify.BAD_AUTHORITY,
                CookieCodec.parseAndVerify(SECRET, value, "localhost:9090", now));
    }

    @Test
    void headerHasHttpOnlySameSiteStrictAndPath() {
        String header = CookieCodec.setCookieHeader("dsh.cookie", "v1.abc.def", System.currentTimeMillis() + 60_000);
        assertTrue(header.contains("HttpOnly"));
        assertTrue(header.contains("SameSite=Strict"));
        assertTrue(header.contains("Path=/"));
        assertTrue(header.contains("Max-Age="));
    }

    @Test
    void parsesCookieHeaderValue() {
        String header = "dsh.a=one; dsh.b=two; session=three";
        assertEquals("two", CookieCodec.cookieValueFromHeader(header, "dsh.b"));
        assertEquals("one", CookieCodec.cookieValueFromHeader(header, "dsh.a"));
        assertEquals(null, CookieCodec.cookieValueFromHeader(header, "missing"));
    }
}
