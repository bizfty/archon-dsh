package com.bizfty.anchon.dsh.hostbridge.rpc;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 官方 unary RPC HTTP 载体薄壳：POST /api/{endpoint}，endpoint 可含多段
 * （ns/method，如 settings/describe；每段 {@code [A-Za-z0-9_$.-]+}）。
 * 仅 {@code hostbridge} profile 激活时注册。
 */
@Profile("hostbridge")
@RestController
public class RpcBridgeController {

    private final RpcBridgeService service;

    public RpcBridgeController(RpcBridgeService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/**", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(HttpServletRequest request, @RequestBody String body) {
        String uri = request.getRequestURI();
        int cut = request.getContextPath().length() + "/api/".length();
        String endpoint = uri.substring(Math.min(cut, uri.length()));
        RpcBridgeService.BridgeResponse response = service.handle(endpoint, body);
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
