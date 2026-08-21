package com.example.dsh.mysql;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * dsh-mysql 模块配置类。
 */
@Configuration
@EnableConfigurationProperties(com.example.dsh.mysql.properties.MysqlProperties.class)
public class MysqlAutoConfiguration {
}