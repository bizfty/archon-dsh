package com.bizfty.anchon.dsh.credentials;

import java.util.Optional;

/**
 * 凭据解析提供者 SPI（对应 DSH credentials 的提供者面：env/文件/KMS）。
 */
public interface CredentialProvider {

    String name();

    /** 解析凭据值；未配置返回 empty（空值即缺席）。 */
    Optional<String> resolve(String key);
}
