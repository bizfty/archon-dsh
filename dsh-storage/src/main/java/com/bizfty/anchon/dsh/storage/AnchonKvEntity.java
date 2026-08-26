package com.bizfty.anchon.dsh.storage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

/**
 * 通用 KV 存储实体（表 {@code anchon_kv}）— 承载 goals/plan/todo/users/
 * credentials/settings/compaction-boundary 等全部命名空间的非会话数据。
 * <p>
 * 复合主键 (namespace, key)。value 为 TEXT（各模块的 JSON 序列化值）。
 */
@Entity
@Table(name = "anchon_kv", indexes = {
        @Index(name = "idx_anchon_kv_ns", columnList = "namespace")
})
@IdClass(AnchonKvEntity.KvId.class)
public class AnchonKvEntity {

    @Id
    @Column(length = 128, nullable = false)
    private String namespace;

    @Id
    @Column(name = "kv_key", length = 256, nullable = false)
    private String key;

    @Column(name = "kv_value", columnDefinition = "TEXT")
    private String value;

    protected AnchonKvEntity() {
    }

    public AnchonKvEntity(String namespace, String key, String value) {
        this.namespace = namespace;
        this.key = key;
        this.value = value;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    /** 复合主键类（JPA IdClass 需要）。 */
    public static class KvId implements Serializable {
        private String namespace;
        private String key;

        public KvId() {
        }

        public KvId(String namespace, String key) {
            this.namespace = namespace;
            this.key = key;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof KvId other)) {
                return false;
            }
            return Objects.equals(namespace, other.namespace) && Objects.equals(key, other.key);
        }

        @Override
        public int hashCode() {
            return Objects.hash(namespace, key);
        }
    }
}
