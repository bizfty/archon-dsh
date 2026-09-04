package com.bizfty.anchon.dsh.hostbridge.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * request-trust 判定测试（403 面）：loopback、trustedHosts 精确/通配端口、Origin 匹配、cross-site。
 */
class RequestTrustTest {

    private static final List<String> TRUSTED = List.of("192.168.1.10", "dsh.lan:8080");

    @Test
    void loopbackHostsAreTrusted() {
        assertEquals(RequestTrust.Trust.TRUSTED,
                RequestTrust.decide("localhost:8080", null, null, TRUSTED));
        assertEquals(RequestTrust.Trust.TRUSTED,
                RequestTrust.decide("127.0.0.1:9000", null, null, TRUSTED));
        assertEquals(RequestTrust.Trust.TRUSTED,
                RequestTrust.decide("[::1]:8080", null, null, TRUSTED));
    }

    @Test
    void trustedHostsExactAndPortLessEntry() {
        assertEquals(RequestTrust.Trust.TRUSTED,
                RequestTrust.decide("dsh.lan:8080", null, null, TRUSTED));
        // 192.168.1.10 条目无端口 → 匹配任意端口
        assertEquals(RequestTrust.Trust.TRUSTED,
                RequestTrust.decide("192.168.1.10:9999", null, null, TRUSTED));
    }

    @Test
    void untrustedHostIsRejected() {
        assertEquals(RequestTrust.Trust.UNTRUSTED_HOST,
                RequestTrust.decide("evil.example.com:8080", null, null, TRUSTED));
    }

    @Test
    void crossSiteFetchIsRejectedBeforeOrigin() {
        assertEquals(RequestTrust.Trust.CROSS_SITE,
                RequestTrust.decide("localhost:8080", "https://localhost:8080", "cross-site", TRUSTED));
    }

    @Test
    void sameOriginPassesCrossOriginFails() {
        assertEquals(RequestTrust.Trust.TRUSTED,
                RequestTrust.decide("localhost:8080", "http://localhost:8080", null, TRUSTED));
        assertEquals(RequestTrust.Trust.ORIGIN_MISMATCH,
                RequestTrust.decide("localhost:8080", "http://evil.example.com", null, TRUSTED));
    }
}
