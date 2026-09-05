package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.settings.SettingDescriptor;
import com.bizfty.anchon.dsh.settings.SettingsPathOp;
import com.bizfty.anchon.dsh.settings.SchemasteryEnvelope;
import com.bizfty.anchon.dsh.settings.SettingsRedactor;
import com.bizfty.anchon.dsh.settings.SettingsService;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 命名空间设置端点（P3 官方语义 wire）：
 * <ul>
 *   <li>GET /api/settings/meta → 每已注册描述符 namespace 的 <b>redacted view</b>
 *       （value/user 剥离 secret + secrets sidecar + revision + applies）——前端动态表单唯一数据源；</li>
 *   <li>PUT /{ns}/{key} body {value, expectedRevision?} —— 整键 set（向后兼容，带可选 CAS）；</li>
 *   <li>POST /{ns}/ops body {ops:[{op:set|unset,path,value?}], expectedRevision?} —— path 级写；</li>
 *   <li>DELETE /{ns}/{key} —— unset（恢复默认）。</li>
 * </ul>
 * 冲突（expectedRevision 过期）→ 409 SETTINGS_CONFLICT（GlobalExceptionHandler）；非法值/路径 → 400。
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/{namespace}/{key}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String namespace,
                                                   @PathVariable String key) {
        Object value = settingsService.get(namespace, key);
        // 红线：wire 出参必经 redact —— secret 键不回明文，只回持有状态
        SettingDescriptor desc = settingsService.describe(namespace).stream()
                .filter(d -> d.key().equals(key)).findFirst().orElse(null);
        if (desc != null && Boolean.TRUE.equals(desc.secret())) {
            return ResponseEntity.ok(Map.of("namespace", namespace, "key", key,
                    "set", value != null));
        }
        return ResponseEntity.ok(Map.of("namespace", namespace, "key", key, "value", value));
    }

    @PutMapping("/{namespace}/{key}")
    public ResponseEntity<Map<String, Object>> set(@PathVariable String namespace,
                                                   @PathVariable String key,
                                                   @RequestBody SetBody body) {
        if (body == null || body.value() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "value 不能为空"));
        }
        long revision = settingsService.mutate(namespace,
                List.of(SettingsPathOp.set(key, body.value())), body.expectedRevision());
        return ResponseEntity.ok(Map.of("namespace", namespace, "key", key, "set", true, "revision", revision));
    }

    /** path 级写：set/unset ops + 可选 expectedRevision CAS。 */
    @PostMapping("/{namespace}/ops")
    public ResponseEntity<Map<String, Object>> ops(@PathVariable String namespace,
                                                   @RequestBody OpsBody body) {
        if (body == null || body.ops() == null || body.ops().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ops 不能为空"));
        }
        List<SettingsPathOp> ops = body.ops().stream()
                .map(o -> new SettingsPathOp(o.op(), o.path(), o.value()))
                .toList();
        long revision = settingsService.mutate(namespace, ops, body.expectedRevision());
        return ResponseEntity.ok(Map.of("namespace", namespace, "revision", revision));
    }

    /** unset 整键（恢复默认）。 */
    @DeleteMapping("/{namespace}/{key}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String namespace,
                                                      @PathVariable String key) {
        long revision = settingsService.unset(namespace, List.of(key), null);
        return ResponseEntity.ok(Map.of("namespace", namespace, "key", key, "unset", true, "revision", revision));
    }

    @GetMapping("/{namespace}")
    public ResponseEntity<Map<String, Object>> all(@PathVariable String namespace) {
        // 红线：全量视图也过 redact（secret 键不随 all 下发明文）
        SettingsRedactor.Result r = SettingsRedactor.redact(settingsService.describe(namespace),
                settingsService.all(namespace));
        return ResponseEntity.ok(r.value());
    }

    /** schema 单源 redacted view：已注册描述符命名空间 → 描述符树 + redacted resolved/user + revision + secrets。 */
    @GetMapping("/meta")
    public ResponseEntity<List<SettingsMetaDto>> meta() {
        List<SettingsMetaDto> out = settingsService.describedNamespaces().stream()
                .map(this::view)
                .toList();
        return ResponseEntity.ok(out);
    }

    private SettingsMetaDto view(String ns) {
        List<SettingDescriptor> descriptors = settingsService.describe(ns);
        SettingsRedactor.Result resolved = SettingsRedactor.redact(descriptors,
                settingsService.all(ns));
        Map<String, Object> userRaw = settingsService.userSection(ns);
        Map<String, Object> user = userRaw.isEmpty()
                ? null
                : SettingsRedactor.redact(descriptors, userRaw).value();
        return new SettingsMetaDto(ns, descriptors, resolved.value(), user,
                settingsService.revision(ns), resolved.secrets(), settingsService.applies(ns),
                SchemasteryEnvelope.toEnvelope(descriptors));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SettingsMetaDto(String namespace,
                                  List<SettingDescriptor> settings,
                                  Map<String, Object> value,
                                  Map<String, Object> user,
                                  long revision,
                                  List<SettingsRedactor.Secret> secrets,
                                  String applies,
                                  Map<String, Object> schema) {
    }

    public record SetBody(Object value, Long expectedRevision) {
    }

    public record OpsBody(List<OpDto> ops, Long expectedRevision) {
    }

    public record OpDto(String op, List<String> path, Object value) {
    }
}
