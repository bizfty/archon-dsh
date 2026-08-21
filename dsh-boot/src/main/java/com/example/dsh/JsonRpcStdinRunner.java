package com.example.dsh;

import com.example.dsh.sdk.server.JsonRpcServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * JSON-RPC stdio 服务器入口（对应 DSH 的 headless/自动化驱动模式）。
 * <p>
 * 启用：{@code --dsh.sdk.stdin-server.enabled=true}（默认关闭，避免与 Web 模式抢占 stdin）。
 * 另一个进程以行分隔 JSON-RPC 驱动 harness：
 * initialize → session/prompt → shutdown。
 */
@Component
public class JsonRpcStdinRunner implements CommandLineRunner {

    private final JsonRpcServer jsonRpcServer;
    private final boolean enabled;

    public JsonRpcStdinRunner(JsonRpcServer jsonRpcServer,
                              @Value("${dsh.sdk.stdin-server.enabled:false}") boolean enabled) {
        this.jsonRpcServer = jsonRpcServer;
        this.enabled = enabled;
    }

    @Override
    public void run(String... args) {
        if (enabled) {
            jsonRpcServer.run(System.in, System.out);
        }
    }
}
