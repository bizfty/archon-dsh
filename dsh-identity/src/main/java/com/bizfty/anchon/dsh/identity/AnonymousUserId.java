package com.bizfty.anchon.dsh.identity;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 匿名用户 id（对应 DSH identity/anonymous-user-id）。
 * <p>
 * 首次启动生成 UUID 并持久化到文件（默认 {@code ./.dsh-identity}）；
 * 之后每次启动复用同一 id。用于遥测/反馈/请求头的匿名身份。
 */
@Component
public class AnonymousUserId {

    private static final Logger log = LoggerFactory.getLogger(AnonymousUserId.class);

    private final Path file;
    private String id;

    public AnonymousUserId(@Value("${dsh.identity.file:./.dsh-identity}") String file) {
        this.file = Paths.get(file);
    }

    @PostConstruct
    public void init() {
        this.id = loadOrCreate();
    }

    public String get() {
        return id;
    }

    private String loadOrCreate() {
        try {
            if (Files.isRegularFile(file)) {
                String existing = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!existing.isBlank()) {
                    return existing;
                }
            }
            String fresh = "anon_" + UUID.randomUUID();
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, fresh, StandardCharsets.UTF_8);
            log.info("[Identity] 生成匿名用户 id: {}", fresh);
            return fresh;
        } catch (IOException e) {
            log.warn("[Identity] 无法持久化匿名 id（使用进程内随机值）: {}", e.getMessage());
            return "anon_" + UUID.randomUUID();
        }
    }
}
