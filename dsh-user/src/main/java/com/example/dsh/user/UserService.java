package com.example.dsh.user;

import com.example.dsh.credentials.CredentialRef;
import com.example.dsh.credentials.CredentialService;
import com.example.dsh.storage.StorageService;
import com.example.dsh.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户服务 — 注册/认证/profile/LLM 配置（对应本次需求的用户 profile 管理）。
 * <p>
 * 持久化：profile 经 StorageService（JSON 文件）；LLM API key 经 CredentialService
 * （provider=user，key=userId:llm-api-key，describe 永不带值，重启存活）。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private static final String NAMESPACE = "users";

    private final StorageService storage;
    private final CredentialService credentials;
    private final JsonUtils jsonUtils;

    public UserService(StorageService storage, CredentialService credentials) {
        this.storage = storage;
        this.credentials = credentials;
        this.jsonUtils = new JsonUtils();
    }

    /**
     * 注册用户。
     *
     * @throws IllegalArgumentException 用户名/密码非法或已存在
     */
    public UserProfile register(String username, String password,
                                String llmProvider, String llmModel) {
        String normalized = normalizeUsername(username);
        if (password == null || password.length() < 4) {
            throw new IllegalArgumentException("密码至少 4 位");
        }
        if (findByUsername(normalized).isPresent()) {
            throw new IllegalArgumentException("用户名已存在: " + normalized);
        }
        UserProfile profile = new UserProfile(
                "u_" + UUID.randomUUID().toString().substring(0, 8),
                normalized,
                Passwords.hash(password.toCharArray()),
                llmProvider == null || llmProvider.isBlank() ? "deepseek" : llmProvider,
                llmModel,
                Instant.now());
        storage.put(NAMESPACE, normalized, jsonUtils.toJson(profile));
        log.info("[User] 注册用户: {}", normalized);
        return profile;
    }

    public boolean authenticate(String username, String password) {
        if (username == null || password == null) {
            return false;
        }
        return findByUsername(normalizeUsername(username))
                .map(p -> Passwords.verify(password.toCharArray(), p.passwordHash()))
                .orElse(false);
    }

    public Optional<UserProfile> findByUsername(String username) {
        String normalized = normalizeUsername(username);
        return storage.get(NAMESPACE, normalized).map(v -> jsonUtils.fromJson(v, UserProfile.class));
    }

    public Optional<UserProfile> findById(String id) {
        for (String key : storage.keys(NAMESPACE)) {
            UserProfile profile = jsonUtils.fromJson(storage.get(NAMESPACE, key).orElse(""), UserProfile.class);
            if (profile.id().equals(id)) {
                return Optional.of(profile);
            }
        }
        return Optional.empty();
    }

    /** 更新用户 LLM 配置（provider/model/api key）。 */
    public UserProfile updateLlmConfig(String userId, String provider, String model, String apiKey) {
        UserProfile profile = findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在: " + userId));
        UserProfile updated = new UserProfile(
                profile.id(), profile.username(), profile.passwordHash(),
                provider == null || provider.isBlank() ? profile.llmProvider() : provider,
                model == null || model.isBlank() ? profile.llmModel() : model,
                profile.createdAt());
        storage.put(NAMESPACE, updated.username(), jsonUtils.toJson(updated));
        if (apiKey != null && !apiKey.isBlank()) {
            setLlmApiKey(userId, apiKey);
        }
        return updated;
    }

    /** LLM API key → CredentialService（provider=user，key=userId:llm-api-key）。 */
    public void setLlmApiKey(String userId, String apiKey) {
        credentials.set(llmKeyRef(userId), apiKey);
    }

    public Optional<String> getLlmApiKey(String userId) {
        return credentials.resolve(llmKeyRef(userId));
    }

    private static CredentialRef llmKeyRef(String userId) {
        return new CredentialRef("user", userId + ":llm-api-key");
    }

    private static String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        return username.trim().toLowerCase();
    }
}
