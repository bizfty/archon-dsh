package com.example.dsh.browser;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * dsh-browser 模块配置类 — 启用 BrowserProperties。
 */
@Configuration
@EnableConfigurationProperties(com.example.dsh.browser.properties.BrowserProperties.class)
public class BrowserAutoConfiguration {
}