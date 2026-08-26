package com.bizfty.anchon.dsh.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * anchon_kv 数据访问（复合主键 namespace+key）。
 */
public interface AnchonKvRepository extends JpaRepository<AnchonKvEntity, AnchonKvEntity.KvId> {

    /** 某命名空间下的全部键。 */
    List<AnchonKvEntity> findByNamespaceOrderByKeyAsc(String namespace);
}
