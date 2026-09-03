package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.settings.SettingDescriptor;
import com.bizfty.anchon.dsh.settings.SettingsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 命名空间设置端点：GET/PUT 单键 + 全量视图。
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
        return ResponseEntity.ok(Map.of("namespace", namespace, "key", key, "value", value));
    }

    @PutMapping("/{namespace}/{key}")
    public ResponseEntity<Map<String, Object>> set(@PathVariable String namespace,
                                                   @PathVariable String key,
                                                   @RequestBody SetBody body) {
        if (body == null || body.value() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "value 不能为空"));
        }
        settingsService.set(namespace, key, body.value());
        return ResponseEntity.ok(Map.of("namespace", namespace, "key", key, "set", true));
    }

    @GetMapping("/{namespace}")
    public ResponseEntity<Map<String, Object>> all(@PathVariable String namespace) {
        return ResponseEntity.ok(settingsService.all(namespace));
    }

    /** schema 单源：已注册描述符的命名空间 → 描述符 + 当前值合并视图（前端动态表单用）。 */
    @GetMapping("/meta")
    public ResponseEntity<List<SettingsMetaDto>> meta() {
        List<SettingsMetaDto> out = settingsService.describedNamespaces().stream()
                .map(ns -> new SettingsMetaDto(ns, settingsService.describe(ns), settingsService.all(ns)))
                .toList();
        return ResponseEntity.ok(out);
    }

    public record SettingsMetaDto(String namespace,
                                  List<SettingDescriptor> settings,
                                  Map<String, Object> values) {
    }

    public record SetBody(Object value) {
    }
}
