package com.bizfty.anchon.dsh.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WorkspaceRepository extends JpaRepository<WorkspaceEntity, String> {

    Optional<WorkspaceEntity> findByPath(String path);
}
