package com.bizfty.anchon.dsh.storage;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存后端（测试/回退用）— 排在文件后端之后，仅作读回退。
 */
@Component
@Order(1)
public class InMemoryStorageBackend implements StorageBackend {

    private final Map<String, Map<String, String>> store = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public Optional<String> get(String namespace, String key) {
        Map<String, String> ns = store.get(namespace);
        return ns == null ? Optional.empty() : Optional.ofNullable(ns.get(key));
    }

    @Override
    public void put(String namespace, String key, String value) {
        store.computeIfAbsent(namespace, k -> new ConcurrentHashMap<>()).put(key, value);
    }

    @Override
    public void delete(String namespace, String key) {
        Map<String, String> ns = store.get(namespace);
        if (ns != null) {
            ns.remove(key);
        }
    }

    @Override
    public List<String> keys(String namespace) {
        Map<String, String> ns = store.get(namespace);
        return ns == null ? List.of() : new ArrayList<>(ns.keySet());
    }
}
