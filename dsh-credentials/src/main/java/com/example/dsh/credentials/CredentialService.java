package com.example.dsh.credentials;

import com.example.dsh.storage.StorageService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 凭据服务 — 按引用每操作解析（不缓存跨操作值）；describe 永不带值；
 * 内存覆盖层用于运行时 set/unset（P2 持久化到 storage）。
 */
@Service
public class CredentialService {

    private static final String OVERRIDES_NS = "credentials";

    private final Map<String, CredentialProvider> providers;
    private final Map<CredentialRef, String> overrides = new ConcurrentHashMap<>();
    private final StorageService storage;

    public CredentialService(ObjectProvider<CredentialProvider> providerProvider) {
        this(providerProvider, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CredentialService(ObjectProvider<CredentialProvider> providerProvider, StorageService storage) {
        Map<String, CredentialProvider> map = new LinkedHashMap<>();
        providerProvider.orderedStream().forEach(p -> map.put(p.name(), p));
        this.providers = map;
        this.storage = storage; // 可空：无 storage 时覆盖值仅内存
        if (storage != null) {
            // 从持久层恢复覆盖值（重启存活）
            for (String key : storage.keys(OVERRIDES_NS)) {
                storage.get(OVERRIDES_NS, key).ifPresent(v -> overrides.put(parseRefKey(key), v));
            }
        }
    }

    /** 每操作解析；未配置返回 empty（空值即缺席）。 */
    public Optional<String> resolve(CredentialRef ref) {
        String override = overrides.get(ref);
        if (override != null) {
            return Optional.of(override);
        }
        CredentialProvider provider = providers.get(ref.provider());
        if (provider == null) {
            return Optional.empty();
        }
        return provider.resolve(ref.key());
    }

    /** 描述引用（provider + key，永不带值）。 */
    public String describe(CredentialRef ref) {
        return ref.provider() + ":" + ref.key() + "（已配置: " + resolve(ref).isPresent() + "）";
    }

    public void set(CredentialRef ref, String value) {
        overrides.put(ref, value);
        if (storage != null) {
            storage.put(OVERRIDES_NS, refKey(ref), value);
        }
    }

    public void unset(CredentialRef ref) {
        overrides.remove(ref);
        if (storage != null) {
            storage.delete(OVERRIDES_NS, refKey(ref));
        }
    }

    private static String refKey(CredentialRef ref) {
        return ref.provider() + ":" + ref.key();
    }

    private static CredentialRef parseRefKey(String key) {
        int idx = key.indexOf(':');
        if (idx < 0) {
            return new CredentialRef("env", key);
        }
        return new CredentialRef(key.substring(0, idx), key.substring(idx + 1));
    }
}
