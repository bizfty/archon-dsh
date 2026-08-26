package com.bizfty.anchon.dsh.credentials;

/**
 * 凭据引用 — 配置里只出现引用（provider:key），不落密钥。
 * <p>
 * 三原则（对应 DSH credentials）：引用不落配置、每操作解析（不跨操作缓存）、
 * 空值即未配置（缺席与空等价）。
 */
public record CredentialRef(String provider, String key) {

    public CredentialRef {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("凭据 key 不能为空");
        }
    }

    /** 解析 "provider:key" 格式（缺省 provider 为 env）。 */
    public static CredentialRef parse(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("凭据引用不能为空");
        }
        int idx = ref.indexOf(':');
        if (idx < 0) {
            return new CredentialRef("env", ref);
        }
        return new CredentialRef(ref.substring(0, idx), ref.substring(idx + 1));
    }

    @Override
    public String toString() {
        return provider + ":" + key;
    }
}
