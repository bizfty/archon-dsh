package com.bizfty.anchon.dsh.browser;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * dsh-browser 模块配置类 — 启用 BrowserProperties。
 */
@Configuration
@EnableConfigurationProperties(com.bizfty.anchon.dsh.browser.properties.BrowserProperties.class)
public class BrowserAutoConfiguration {
}