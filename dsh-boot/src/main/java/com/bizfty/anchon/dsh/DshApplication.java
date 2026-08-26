package com.bizfty.anchon.dsh;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DSH Java 复刻 — 可运行应用（对应 DSH packages/boot/app-boot）。
 * <p>
 * 模块划分镜像 external/deepseek/packages 的能力组：
 * dsh-core(会话/事件/system-prompt) · dsh-tool(工具管线) · dsh-session(持久化) ·
 * dsh-llm(模型网关) · dsh-agent(agent-loop) · dsh-api(REST/SSE/OpenAI 兼容) ·
 * dsh-todo/dsh-plan/dsh-fs/dsh-shell/dsh-skill(能力工具) ·
 * dsh-interaction(审批/问答) · dsh-guard(守卫) · dsh-compaction(压缩) ·
 * dsh-subagent(子代理) · dsh-search(抓取/搜索)。
 */
@SpringBootApplication
@EnableScheduling
public class DshApplication {

    private static final Logger log = LoggerFactory.getLogger(DshApplication.class);

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(DshApplication.class, args);
        AppVersion version = ctx.getBean(AppVersion.class);
        log.info("========== DSH started | version={} | build={} | startedAt={} ==========",
                version.getVersion(),
                version.getBuildTimestamp(),
                version.getStartedAt());
    }
}