package com.bizfty.anchon.dsh.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * JPA/PostgreSQL 存储后端（表 {@code anchon_kv}）— 非会话数据的数据库落盘。
 * <p>
 * 优先级 {@code @Order(0)} 在 JSON 文件后端（{@code @Order(0)} 同序，按注册顺序）
 * 之前注册，StorageService 的 {@code put} 写入第一个后端 → 数据入库。
 * 若需要保留文件后端作为只读回退，可把本后端置于更高优先级且 JSON 后端改为只读。
 * <p>
 * 启用：应用使用 Postgres 数据源时自动生效（本后端依赖 JPA 实体 anchon_kv）。
 */
@Component
@Order(0)
public class JpaStorageBackend implements StorageBackend {

    private static final Logger log = LoggerFactory.getLogger(JpaStorageBackend.class);

    private final AnchonKvRepository repository;

    public JpaStorageBackend(AnchonKvRepository repository) {
        this.repository = repository;
    }

    @Override
    public String name() {
        return "jpa";
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> get(String namespace, String key) {
        return repository.findById(new AnchonKvEntity.KvId(namespace, key))
                .map(AnchonKvEntity::getValue);
    }

    @Override
    @Transactional
    public void put(String namespace, String key, String value) {
        AnchonKvEntity.KvId id = new AnchonKvEntity.KvId(namespace, key);
        AnchonKvEntity entity = repository.findById(id).orElse(new AnchonKvEntity(namespace, key, value));
        entity.setValue(value);
        repository.save(entity);
    }

    @Override
    @Transactional
    public void delete(String namespace, String key) {
        repository.deleteById(new AnchonKvEntity.KvId(namespace, key));
    }

    @Override
    @Transactional(readOnly = true)
    public List<String> keys(String namespace) {
        return repository.findByNamespaceOrderByKeyAsc(namespace).stream()
                .map(AnchonKvEntity::getKey)
                .toList();
    }
}
