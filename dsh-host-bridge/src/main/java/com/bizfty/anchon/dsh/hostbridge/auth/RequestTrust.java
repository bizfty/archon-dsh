package com.bizfty.anchon.dsh.hostbridge.auth;

import java.util.List;
import java.util.Locale;

/**
 * 请求信任闸门（api-request-trust.ts 语义的 Java 版）：
 * Host 必须 loopback 或匹配 trustedHosts；Origin 若存在必须等于该 authority；
 * {@code sec-fetch-site: cross-site} 一律拒绝。只判 403 面，不判身份（401 由 cookie 面负责）。
 */
public final class RequestTrust {

    /** 信任判定结果。 */
    public enum Trust {
        TRUSTED, UNTRUSTED_HOST, CROSS_SITE, ORIGIN_MISMATCH
    }

    private RequestTrust() {
    }

    /** 判定（hostHeader 必填；originHeader/secFetchSiteHeader 可空）。 */
    public static Trust decide(String hostHeader, String originHeader, String secFetchSiteHeader,
                               List<String> trustedHosts) {
        if (!isTrustedHost(CookieCodec.normalizeAuthority(hostHeader), trustedHosts)) {
            return Trust.UNTRUSTED_HOST;
        }
        if ("cross-site".equals(secFetchSiteHeader)) {
            return Trust.CROSS_SITE;
        }
        if (originHeader != null && !originHeader.isEmpty()) {
            String originAuthority = authorityOfOrigin(originHeader);
            String requestAuthority = CookieCodec.normalizeAuthority(hostHeader);
            if (!requestAuthority.equals(originAuthority)) {
                return Trust.ORIGIN_MISMATCH;
            }
        }
        return Trust.TRUSTED;
    }

    private static boolean isTrustedHost(String authority, List<String> trustedHosts) {
        if (isLoopbackHost(authority)) {
            return true;
        }
        if (trustedHosts == null) {
            return false;
        }
        for (String entry : trustedHosts) {
            String normalized = CookieCodec.normalizeAuthority(entry);
            if (normalized.equals(authority)) {
                return true;
            }
            // port-less 条目匹配任意端口：条目无 ':' 且 host 部分相等
            if (normalized.indexOf(':') == -1 && authority.startsWith(normalized + ":")) {
                return true;
            }
        }
        return false;
    }

    /** loopback：127.0.0.0/8、::1、localhost（含 .localhost 后缀）。 */
    public static boolean isLoopbackHost(String authority) {
        String host = authority;
        if (host.startsWith("[")) {
            int close = host.indexOf(']');
            host = close != -1 ? host.substring(1, close) : host;
        } else {
            int colon = host.indexOf(':');
            if (colon != -1) {
                host = host.substring(0, colon);
            }
        }
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost")) {
            return true;
        }
        if (lower.equals("::1")) {
            return true;
        }
        // 127.0.0.0/8
        if (lower.startsWith("127.")) {
            String[] parts = lower.split("\\.");
            if (parts.length == 4) {
                try {
                    return Integer.parseInt(parts[0]) == 127;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
        }
        return false;
    }

    /** 从 Origin URL 提取 authority。 */
    public static String authorityOfOrigin(String origin) {
        int schemeEnd = origin.indexOf("://");
        String rest = schemeEnd == -1 ? origin : origin.substring(schemeEnd + 3);
        int slash = rest.indexOf('/');
        return CookieCodec.normalizeAuthority(slash == -1 ? rest : rest.substring(0, slash));
    }
}
