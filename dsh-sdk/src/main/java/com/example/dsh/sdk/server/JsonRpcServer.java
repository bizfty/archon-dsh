package com.example.dsh.sdk.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * JSON-RPC stdio 服务器 — 行分隔协议：stdout 只走协议（对应 DSH sdk/server）。
 * <p>
 * 通过 {@code dsh.sdk.stdin-server.enabled: true} 启用（应用装配处）。
 * 测试可用管道流构造本类。
 */
@Component
public class JsonRpcServer {

    private static final Logger log = LoggerFactory.getLogger(JsonRpcServer.class);

    private final JsonRpcDispatcher dispatcher;

    public JsonRpcServer(JsonRpcDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** 运行服务器直到 shutdown 请求或输入结束（阻塞）。 */
    public void run(InputStream in, OutputStream out) {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        PrintWriter writer = new PrintWriter(out, true, StandardCharsets.UTF_8);
        log.info("[JSON-RPC] 服务器启动（行分隔 JSON-RPC over stdio）");
        try {
            String line;
            while (!dispatcher.isShutdownRequested() && (line = reader.readLine()) != null) {
                String response = dispatcher.handleLine(line);
                if (response != null) {
                    writer.println(response);
                }
            }
        } catch (Exception e) {
            log.error("[JSON-RPC] 服务器异常: {}", e.getMessage(), e);
        } finally {
            writer.flush();
        }
        log.info("[JSON-RPC] 服务器退出");
    }
}
