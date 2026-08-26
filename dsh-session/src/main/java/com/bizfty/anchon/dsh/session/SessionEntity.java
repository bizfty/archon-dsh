package com.bizfty.anchon.dsh.session;

import com.bizfty.anchon.dsh.core.model.Session;
import com.bizfty.anchon.dsh.core.model.SessionId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 会话持久化实体。
 */
@Entity
@Table(name = "anchon_session")
public class SessionEntity {

    @Id
    @Column(length = 64)
    private String id;

    @Column(length = 255)
    private String title;

    @Column(length = 128)
    private String model;

    @Column(length = 1024)
    private String cwd;

    @Column(length = 32)
    private String status;

    private Instant createdAt;
    private Instant updatedAt;

    protected SessionEntity() {
    }

    public static SessionEntity from(Session session) {
        SessionEntity e = new SessionEntity();
        e.id = session.id().value();
        e.title = session.title();
        e.model = session.model();
        e.cwd = session.cwd();
        e.status = "active";
        e.createdAt = session.createdAt();
        e.updatedAt = session.updatedAt();
        return e;
    }

    public Session toDomain() {
        return new Session(SessionId.of(id), title, model, cwd, createdAt, updatedAt);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getCwd() {
        return cwd;
    }

    public void setCwd(String cwd) {
        this.cwd = cwd;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
