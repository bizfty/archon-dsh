package com.bizfty.anchon.dsh.api.hostbridge;

import com.bizfty.anchon.dsh.credentials.CredentialService;
import com.bizfty.anchon.dsh.hostbridge.mux.MuxSessionRegistry;
import com.bizfty.anchon.dsh.hostbridge.rpc.RpcBridgeService;
import com.bizfty.anchon.dsh.hostbridge.rpc.RpcDispatcher;
import com.bizfty.anchon.dsh.session.SessionService;
import com.bizfty.anchon.dsh.settings.SettingsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * A2 hostbridge 业务装配：把 archon 领域服务（settings/credentials/session）注册为
 * 官方 Remote endpoint（ns/method，payload {@code {args}}），供官方 client 的
 * {@code ctx.remote.*} 调用。仅 {@code hostbridge} profile 激活，不进默认应用面。
 *
 * <p>wire 侧 RpcBridgeController / RemoteMuxWebSocketHandler 由 dsh-host-bridge 的
 * 组件扫描提供（同 profile），经本配置的 RpcDispatcher / MuxSessionRegistry 组装。
 */
@Configuration
@Profile("hostbridge")
public class HostBridgeConfig {

    @Bean
    public RpcDispatcher hostBridgeDispatcher(SettingsService settings,
                                              CredentialService credentials,
                                              SessionService sessions,
                                              MuxSessionRegistry registry) {
        RpcDispatcher dispatcher = new RpcDispatcher();
        RpcBridgeService.registerBuiltins(dispatcher);
        SettingsEndpoints.register(dispatcher, settings, registry);
        CredentialsEndpoints.register(dispatcher, credentials);
        SessionEndpoints.register(dispatcher, sessions);
        return dispatcher;
    }

    @Bean
    public RpcBridgeService hostBridgeService(RpcDispatcher hostBridgeDispatcher) {
        return new RpcBridgeService(hostBridgeDispatcher);
    }
}
