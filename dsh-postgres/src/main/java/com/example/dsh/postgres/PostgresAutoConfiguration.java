package com.example.dsh.postgres;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * dsh-postgres 模块配置类。
 */
@Configuration
@EnableConfigurationProperties(com.example.dsh.postgres.properties.PostgresProperties.class)
public class PostgresAutoConfiguration {
}