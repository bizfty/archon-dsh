package com.bizfty.anchon.dsh.settings;

import com.bizfty.anchon.dsh.storage.StorageService;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 命名空间设置（对应官方 DSH settings：schema 默认 > 用户覆盖的分层解析）。
 * <p>
 * <b>P3 存储模型：每 namespace 的 user 层 = 单个 JSON 文档</b>，存 storage 固定键
 * {@code settings.{ns}.doc}（JSON 对象）+ {@code settings.{ns}.rev}（整数修订号）。
 * 旧模型（{@code settings.{ns}.{key}} 每键一条目）在首次访问时 <b>lazy 迁移</b>并入文档并删除旧条目。
 * <p>
 * 写面（对齐官方 settings）：{@link #mutate}（path 级 set/unset ops）、{@link #update}（深合并 patch）、
 * {@link #replace}（整文档替换）、{@link #unset}；均支持 <b>revision CAS</b>（expectedRevision ≠ 当前 →
 * {@link SettingsConflictException}）。读取保留 defaults > user 解析链，AgentLoop 等消费点零漂移。
 * <p>
 * 覆盖标记 = presence：user 文档中某路径在场 ⇔ 该路径 user-overridden（{@link #overridden}）。
 */
@Service
public class SettingsService {

    /** user 文档与修订号在 storage 命名空间内的固定键。 */
    static final String KEY_DOC = "doc";
    static final String KEY_REV = "rev";

    private final StorageService storage;
    private final ObjectMapper mapper;
    private final Map<String, Map<String, Object>> defaults = new ConcurrentHashMap<>();
    private final Map<String, Map<String, SettingDescriptor>> descriptors = new ConcurrentHashMap<>();

    /** 内存缓存：user 文档（JSON 兼容 Map/List 结构）与修订号；写路径在 namespace 锁内替换引用。 */
    private final Map<String, Map<String, Object>> userDocs = new ConcurrentHashMap<>();
    private final Map<String, Long> revisions = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    /** 命名空间生效时机（live|restart），缺省 live；仅 UI 展示语义。 */
    private final Map<String, String> applies = new ConcurrentHashMap<>();

    public SettingsService(StorageService storage, ObjectMapper mapper) {
        this.storage = storage;
        this.mapper = mapper;
    }

    // ===== 注册（schema 层）=====

    /** 注册命名空间默认值（schema 层）。 */
    public void registerDefaults(String namespace, Map<String, Object> defaultValues) {
        defaults.put(namespace, new LinkedHashMap<>(defaultValues));
    }

    /** 注册命名空间下某键的描述符（schema 层；与默认值/覆盖独立，可单独注册）。 */
    public void registerDescriptor(String namespace, SettingDescriptor descriptor) {
        descriptors.computeIfAbsent(namespace, k ->
                        java.util.Collections.synchronizedMap(new LinkedHashMap<>()))
                .put(descriptor.key(), descriptor);
    }

    /** 某命名空间的描述符列表（注册顺序保序）；未注册 → 空列表。 */
    public List<SettingDescriptor> describe(String namespace) {
        Map<String, SettingDescriptor> ns = descriptors.get(namespace);
        if (ns == null) {
            return List.of();
        }
        synchronized (ns) {
            return List.copyOf(ns.values());
        }
    }

    /** 已注册描述符的命名空间（字典序；供 /meta 枚举，未注册描述符的不暴露）。 */
    public List<String> describedNamespaces() {
        return descriptors.keySet().stream().sorted().toList();
    }

    /** 声明命名空间生效时机（官方 applies: live|restart；缺省 live）。 */
    public void registerApplies(String namespace, String applies) {
        this.applies.put(namespace, "live".equals(applies) || "restart".equals(applies) ? applies : "live");
    }

    /** 命名空间生效时机（未声明 → "live"）。 */
    public String applies(String namespace) {
        return applies.getOrDefault(namespace, "live");
    }

    // ===== 读面（defaults > user；消费点零漂移）=====

    /** 解析值：user 覆盖 > 默认。 */
    public Object get(String namespace, String key) {
        synchronized (lock(namespace)) {
            loadUserLocked(namespace);
            Map<String, Object> user = userDocs.get(namespace);
            if (user.containsKey(key)) {
                return user.get(key);
            }
            return defaults.getOrDefault(namespace, Map.of()).get(key);
        }
    }

    public String getString(String namespace, String key, String fallback) {
        Object value = get(namespace, key);
        return value == null ? fallback : String.valueOf(value);
    }

    public int getInt(String namespace, String key, int fallback) {
        Object value = get(namespace, key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** 合并视图（默认 + user 覆盖，顶层键粒度）。 */
    public Map<String, Object> all(String namespace) {
        synchronized (lock(namespace)) {
            loadUserLocked(namespace);
            Map<String, Object> merged = new LinkedHashMap<>(defaults.getOrDefault(namespace, Map.of()));
            merged.putAll(userDocs.get(namespace));
            return merged;
        }
    }

    /** user 层某路径是否在场（覆盖标记 = presence）。路径自 user 文档根寻址；空路径恒 true（文档本身即 user 层）。 */
    public boolean overridden(String namespace, List<String> path) {
        synchronized (lock(namespace)) {
            loadUserLocked(namespace);
            Object node = userDocs.get(namespace);
            for (String segment : path) {
                Object next = child(node, segment);
                if (next == NOT_FOUND) {
                    return false;
                }
                node = next;
            }
            return true;
        }
    }

    /** 当前 user 修订号。 */
    public long revision(String namespace) {
        synchronized (lock(namespace)) {
            loadUserLocked(namespace);
            return revisions.get(namespace);
        }
    }

    /** user 层原始文档（redact 前；同进程消费用）。返回 detached 副本，改它不影响内部。 */
    public Map<String, Object> userSection(String namespace) {
        synchronized (lock(namespace)) {
            loadUserLocked(namespace);
            return deepCopyMap(userDocs.get(namespace));
        }
    }

    // ===== 写面（path ops + revision CAS）=====

    /** 兼容旧签名：整键 set（内部 = mutate set [key]，无 CAS）——存量调用/测试零改。 */
    public void set(String namespace, String key, Object value) {
        mutate(namespace, List.of(SettingsPathOp.set(key, value)), null);
    }

    /**
     * path 级写：对 user 文档应用 ops（set/unset），成功后 revision +1。
     * expectedRevision 提供且 ≠ 当前 → {@link SettingsConflictException}；null = 不校验。
     *
     * @return 新修订号（无实际变化时返回当前修订号）
     */
    public long mutate(String namespace, List<SettingsPathOp> ops, Long expectedRevision) {
        return writeLocked(namespace, expectedRevision, doc -> {
            for (SettingsPathOp op : ops) {
                applyPathOp(doc, op);
            }
        });
    }

    /** 官方 update 语义：把 patch 深合并进 user 文档（Map 递归合并；List/标量整替）。 */
    public long update(String namespace, Map<String, Object> patch, Long expectedRevision) {
        return writeLocked(namespace, expectedRevision, doc -> deepMerge(doc, patch));
    }

    /** 官方 replace 语义：整文档替换 user 层（缺键回继承 defaults）。 */
    public long replace(String namespace, Map<String, Object> section, Long expectedRevision) {
        return writeLocked(namespace, expectedRevision, doc -> {
            doc.clear();
            doc.putAll(deepCopyMap(section));
        });
    }

    /** path 级 unset（恢复默认）。 */
    public long unset(String namespace, List<String> path, Long expectedRevision) {
        return mutate(namespace, List.of(SettingsPathOp.unset(path)), expectedRevision);
    }

    // ===== 内部：锁 / 加载（含迁移）/ 写管线 / path op =====

    private Object lock(String namespace) {
        return locks.computeIfAbsent(namespace, k -> new Object());
    }

    private String storageNamespace(String namespace) {
        return "settings." + namespace;
    }

    /**
     * 加载 namespace 的 user 文档与修订号到缓存（调用方须持 namespace 锁）。
     * 首次访问：doc 键缺失但存在旧 {@code settings.{ns}.{key}} 条目 → lazy 迁移并入文档、rev=1、删除旧条目。
     */
    private void loadUserLocked(String namespace) {
        if (userDocs.containsKey(namespace)) {
            return;
        }
        String nsPrefix = storageNamespace(namespace);
        String docText = storage.get(nsPrefix, KEY_DOC).orElse(null);
        long rev = storage.get(nsPrefix, KEY_REV).map(this::parseRevision).orElse(0L);

        Map<String, Object> user;
        if (docText != null) {
            user = parseDoc(docText);
        } else {
            user = new LinkedHashMap<>();
        }

        // 旧模型（每键一条目）迁移：除 doc/rev 外均视为旧覆盖键
        List<String> legacyKeys = storage.keys(nsPrefix).stream()
                .filter(k -> !KEY_DOC.equals(k) && !KEY_REV.equals(k))
                .toList();
        if (!legacyKeys.isEmpty()) {
            for (String key : legacyKeys) {
                user.put(key, storage.get(nsPrefix, key).map(this::parseLegacy).orElse(null));
                storage.delete(nsPrefix, key);
            }
            rev = Math.max(rev, 1L);
            persistLocked(namespace, user, rev);
        }

        userDocs.put(namespace, user);
        revisions.put(namespace, rev);
    }

    /** 写管线：CAS → 深拷贝改 → 有变化才 rev+1 并持久化。返回新修订号。 */
    private long writeLocked(String namespace, Long expectedRevision, Consumer<Map<String, Object>> mutator) {
        synchronized (lock(namespace)) {
            loadUserLocked(namespace);
            long current = revisions.get(namespace);
            if (expectedRevision != null && expectedRevision != current) {
                throw new SettingsConflictException(namespace, expectedRevision, current);
            }
            Map<String, Object> currentDoc = userDocs.get(namespace);
            Map<String, Object> nextDoc = deepCopyMap(currentDoc);
            mutator.accept(nextDoc);
            validateUserLocked(namespace, nextDoc);
            if (!Objects.equals(currentDoc, nextDoc)) {
                long nextRev = current + 1;
                persistLocked(namespace, nextDoc, nextRev);
                userDocs.put(namespace, nextDoc);
                revisions.put(namespace, nextRev);
                return nextRev;
            }
            return current;
        }
    }

    /** 写前校验：该命名空间已注册描述符 → 按树强校验 user 文档（未知键/无描述符宽容跳过）。 */
    private void validateUserLocked(String namespace, Map<String, Object> doc) {
        Map<String, SettingDescriptor> ns = descriptors.get(namespace);
        if (ns != null && !ns.isEmpty()) {
            SettingValidator.validate(List.copyOf(ns.values()), doc);
        }
    }

    private void persistLocked(String namespace, Map<String, Object> doc, long rev) {
        String nsPrefix = storageNamespace(namespace);
        storage.put(nsPrefix, KEY_DOC, encode(doc));
        storage.put(nsPrefix, KEY_REV, String.valueOf(rev));
    }

    // ===== path op 语义（对齐官方 applyPathOp）=====

    /** 应用单个 op 到 doc（就地改；调用方持深拷贝）。set 顶层 String 值做标量归一（存量 set 语义）。 */
    private void applyPathOp(Map<String, Object> doc, SettingsPathOp op) {
        if (op.path().isEmpty()) {
            if (SettingsPathOp.UNSET.equals(op.op())) {
                doc.clear();
            } else {
                doc.clear();
                doc.putAll(asPlainMap(op.value(), "set 空路径值须为 JSON 对象"));
            }
            return;
        }
        Object container = doc;
        List<String> path = op.path();
        for (int i = 0; i < path.size() - 1; i++) {
            String segment = path.get(i);
            Object next = child(container, segment);
            if (next == NOT_FOUND || (!(next instanceof Map) && !(next instanceof List))) {
                Map<String, Object> created = new LinkedHashMap<>();
                setChild(container, segment, created);
                container = created;
            } else {
                container = next;
            }
        }
        String last = path.get(path.size() - 1);
        if (SettingsPathOp.SET.equals(op.op())) {
            Object value = op.value();
            if (path.size() == 1 && value instanceof String s) {
                value = normalizeScalarString(s); // 存量 set(String) 标量语义：文本 → bool/int/double
            }
            setChild(container, last, deepCopy(value));
        } else {
            removeChild(container, last);
        }
    }

    /** 深合并 patch 到 target（Map 递归；List/标量整替）。 */
    private void deepMerge(Map<String, Object> target, Map<String, Object> patch) {
        for (Map.Entry<String, Object> e : patch.entrySet()) {
            Object pv = e.getValue();
            Object tv = target.get(e.getKey());
            if (pv instanceof Map<?, ?> pm && tv instanceof Map<?, ?> tm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> childTarget = (Map<String, Object>) tm;
                @SuppressWarnings("unchecked")
                Map<String, Object> childPatch = (Map<String, Object>) pm;
                deepMerge(childTarget, childPatch);
            } else {
                target.put(e.getKey(), deepCopy(pv));
            }
        }
    }

    // ===== 容器寻址（Map / List 数组段）=====

    private static final Object NOT_FOUND = new Object();

    /** 取 child；数组索引越界/键缺失返回 NOT_FOUND（不创建）。 */
    @SuppressWarnings("unchecked")
    private Object child(Object container, String segment) {
        if (container instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            if (!m.containsKey(segment)) {
                return NOT_FOUND;
            }
            return m.get(segment);
        }
        if (container instanceof List<?> list) {
            Integer idx = parseIntSegment(segment);
            if (idx == null || idx < 0 || idx >= list.size()) {
                return NOT_FOUND;
            }
            return list.get(idx);
        }
        return NOT_FOUND;
    }

    /** 写 child；Map put；List 数组段 set（越界拒绝 IAE）。 */
    @SuppressWarnings("unchecked")
    private void setChild(Object container, String segment, Object value) {
        if (container instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).put(segment, value);
            return;
        }
        if (container instanceof List<?> list) {
            Integer idx = parseIntSegment(segment);
            if (idx == null || idx < 0) {
                throw new IllegalArgumentException("数组路径段须为非负整数: " + segment);
            }
            if (idx >= list.size()) {
                throw new IllegalArgumentException("数组越界写入: index " + idx + " >= size " + list.size());
            }
            ((List<Object>) list).set(idx, value);
            return;
        }
        throw new IllegalArgumentException("路径导航到非容器节点: " + segment);
    }

    @SuppressWarnings("unchecked")
    private void removeChild(Object container, String segment) {
        if (container instanceof Map<?, ?> map) {
            ((Map<String, Object>) map).remove(segment);
            return;
        }
        if (container instanceof List<?> list) {
            Integer idx = parseIntSegment(segment);
            if (idx != null && idx >= 0 && idx < list.size()) {
                list.remove((int) idx);
            }
            return;
        }
        throw new IllegalArgumentException("路径导航到非容器节点: " + segment);
    }

    private Integer parseIntSegment(String segment) {
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ===== JSON / 类型工具 =====

    @SuppressWarnings("unchecked")
    private Map<String, Object> asPlainMap(Object value, String message) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(message);
        }
        return (Map<String, Object>) value;
    }

    /** 深拷贝 JSON 兼容对象（Map/List）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> deepCopyMap(Map<String, Object> value) {
        try {
            String json = mapper.writeValueAsString(value);
            Map<String, Object> copy = mapper.readValue(json, Map.class);
            return copy == null ? new LinkedHashMap<>() : copy;
        } catch (Exception e) {
            throw new IllegalStateException("user 文档深拷贝失败", e);
        }
    }

    private Object deepCopy(Object value) {
        if (value instanceof Map || value instanceof List) {
            try {
                String json = mapper.writeValueAsString(value);
                return mapper.readValue(json, Object.class);
            } catch (Exception e) {
                throw new IllegalStateException("值深拷贝失败", e);
            }
        }
        return value; // 标量/字符串不可变
    }

    /** 持久化编码：Map/List → JSON 文本；标量 String.valueOf。 */
    private String encode(Object value) {
        if (value instanceof Map || value instanceof List) {
            return mapper.writeValueAsString(value);
        }
        return String.valueOf(value);
    }

    /** doc 文本 → Map；解析失败回落空文档（doc 由本服务写入，理论上不脏；防御）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseDoc(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> doc = mapper.readValue(trimmed, Map.class);
            return doc == null ? new LinkedHashMap<>() : doc;
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private long parseRevision(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 旧覆盖条目文本还原（沿用 P2 parse：JSON 对象/数组反序列化，坏 JSON/标量走 bool/int/double/string）。 */
    private Object parseLegacy(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return mapper.readValue(trimmed, Object.class);
            } catch (Exception ignored) {
                // 非合法 JSON → 回落标量归一（保持原字符串）
            }
        }
        return normalizeScalarString(text);
    }

    /** 存量标量文本归一：true/false → Boolean；int → Integer；long → Long；浮点 → Double；其余原字符串。 */
    private Object normalizeScalarString(String text) {
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
        }
        return text;
    }
}
