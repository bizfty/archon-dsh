package com.bizfty.anchon.dsh.hostbridge.rpc;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 官方 unary RPC HTTP 载体薄壳：POST /api/{endpoint}。
 * 仅 {@code hostbridge} profile 激活时注册，避免污染主应用路由。
 */
@Profile("hostbridge")
@RestController
public class RpcBridgeController {

    private final RpcBridgeService service;

    public RpcBridgeController(RpcBridgeService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/{endpoint}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(@PathVariable String endpoint, @RequestBody String body) {
        RpcBridgeService.BridgeResponse response = service.handle(endpoint, body);
        return ResponseEntity.status(response.status())
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.body());
    }
}
