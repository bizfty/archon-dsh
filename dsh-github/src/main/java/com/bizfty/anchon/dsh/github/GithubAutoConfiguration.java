package com.bizfty.anchon.dsh.github;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * dsh-github 模块配置类。
 */
@Configuration
@EnableConfigurationProperties(com.bizfty.anchon.dsh.github.properties.GithubProperties.class)
public class GithubAutoConfiguration {
}