package com.bizfty.anchon.dsh.storage;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 存储服务 — 聚合后端（对应 DSH storage hub）。
 */
@Service
public class StorageService {

    private final List<StorageBackend> backends;

    public StorageService(ObjectProvider<StorageBackend> backendProvider) {
        this.backends = backendProvider.orderedStream().toList();
    }

    public Optional<String> get(String namespace, String key) {
        for (StorageBackend backend : backends) {
            Optional<String> value = backend.get(namespace, key);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    public void put(String namespace, String key, String value) {
        if (backends.isEmpty()) {
            throw new IllegalStateException("无存储后端");
        }
        // 写入第一个（最高优先级）后端
        backends.get(0).put(namespace, key, value);
    }

    public void delete(String namespace, String key) {
        for (StorageBackend backend : backends) {
            backend.delete(namespace, key);
        }
    }

    public List<String> keys(String namespace) {
        return backends.isEmpty() ? List.of() : backends.get(0).keys(namespace);
    }
}
