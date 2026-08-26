package com.bizfty.anchon.dsh.storage;

import java.util.List;
import java.util.Optional;

/**
 * 存储后端 SPI（对应 DSH storage 的 backend 注册表；kv 形式）。
 */
public interface StorageBackend {

    /** 后端名（诊断/路由）。 */
    String name();

    Optional<String> get(String namespace, String key);

    void put(String namespace, String key, String value);

    void delete(String namespace, String key);

    List<String> keys(String namespace);
}
