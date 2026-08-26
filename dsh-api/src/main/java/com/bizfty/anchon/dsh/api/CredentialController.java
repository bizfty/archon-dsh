package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.credentials.CredentialRef;
import com.bizfty.anchon.dsh.credentials.CredentialService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 凭据操作端点 — describe 永不带值；set/unset 走内存覆盖层。
 * <p>
 * 生产环境应置于认证之后（当前 API 未启用鉴权，勿直接暴露敏感操作）。
 */
@RestController
@RequestMapping("/api/credentials")
public class CredentialController {

    private final CredentialService credentialService;

    public CredentialController(CredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /** 描述凭据（provider:key + 是否已配置；永不含值）。 */
    @GetMapping("/describe")
    public ResponseEntity<Map<String, Object>> describe(@RequestParam String ref) {
        try {
            CredentialRef credentialRef = CredentialRef.parse(ref);
            return ResponseEntity.ok(Map.of(
                    "ref", credentialRef.toString(),
                    "configured", credentialService.resolve(credentialRef).isPresent(),
                    "description", credentialService.describe(credentialRef)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 设置凭据（内存覆盖层）。 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> set(@RequestBody SetRequest request) {
        try {
            CredentialRef ref = CredentialRef.parse(request.ref());
            if (request.value() == null || request.value().isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "value 不能为空"));
            }
            credentialService.set(ref, request.value());
            return ResponseEntity.ok(Map.of("ref", ref.toString(), "set", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> unset(@RequestParam String ref) {
        try {
            CredentialRef credentialRef = CredentialRef.parse(ref);
            credentialService.unset(credentialRef);
            return ResponseEntity.ok(Map.of("ref", credentialRef.toString(), "unset", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    public record SetRequest(String ref, String value) {
    }
}
