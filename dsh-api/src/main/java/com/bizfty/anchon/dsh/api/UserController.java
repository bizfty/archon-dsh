package com.bizfty.anchon.dsh.api;

import com.bizfty.anchon.dsh.user.AuthService;
import com.bizfty.anchon.dsh.user.UserProfile;
import com.bizfty.anchon.dsh.user.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * 用户端点：注册 / 登录 / 登出 / 我的 profile / LLM 配置更新。
 * <p>
 * 受保护端点需 `X-Auth-Token` 头（登录签发）。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final AuthService authService;

    public UserController(UserService userService, AuthService authService) {
        this.userService = userService;
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        try {
            UserProfile profile = userService.register(
                    request.username(), request.password(),
                    request.llmProvider(), request.llmModel());
            return ResponseEntity.ok(profile.view());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        Optional<String> token = authService.login(request.username(), request.password());
        if (token.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }
        UserProfile profile = userService.findByUsername(request.username()).orElseThrow();
        return ResponseEntity.ok(Map.of("token", token.get(), "user", profile.view()));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        authService.logout(token);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "X-Auth-Token", required = false) String token) {
        Optional<ResponseEntity<?>> found = currentUser(token)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(p.view()));
        return found.orElseGet(() -> ResponseEntity.status(401).body(Map.of("error", "未认证")));
    }

    @PutMapping("/me/llm")
    public ResponseEntity<?> updateLlm(@RequestHeader(value = "X-Auth-Token", required = false) String token,
                                       @RequestBody UpdateLlmRequest request) {
        Optional<String> userId = authService.authenticate(token);
        if (userId.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "未认证"));
        }
        try {
            UserProfile updated = userService.updateLlmConfig(
                    userId.get(), request.llmProvider(), request.llmModel(), request.apiKey());
            return ResponseEntity.ok(updated.view());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Optional<UserProfile> currentUser(String token) {
        return authService.authenticate(token).flatMap(userService::findById);
    }

    public record RegisterRequest(String username, String password, String llmProvider, String llmModel) {
    }

    public record LoginRequest(String username, String password) {
    }

    /** JSON 字段与 register 一致：llmProvider / llmModel / apiKey。 */
    public record UpdateLlmRequest(String llmProvider, String llmModel, String apiKey) {
    }
}
