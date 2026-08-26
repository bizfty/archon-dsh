package com.bizfty.anchon.dsh.session;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工作区根目录装配。
 * <p>
 * 通过 {@code @Value} 直接绑定环境变量（带默认值），避开 {@code dsh.*}
 * 前缀 Binder 绑定污染问题（见 {@link AgentProperties} 注释）；yml 中的
 * {@code dsh.workspace.*} 与这里的默认值保持一致，仅作文档化。
 */
@Configuration
public class WorkspaceAutoConfiguration {

    @Bean
    public WorkspaceProperties workspaceProperties(
            @Value("${DSH_WORKSPACE_ROOT:/data/anchon/workspace}") String root,
            @Value("${DSH_CODE_ROOT:/data/anchon/code}") String codeRoot,
            @Value("${DSH_WORKSPACE_INIT_ENABLED:true}") boolean initEnabled) {
        return new WorkspaceProperties(root, codeRoot, initEnabled);
    }
}
