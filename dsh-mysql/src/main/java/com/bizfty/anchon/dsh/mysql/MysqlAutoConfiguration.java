package com.bizfty.anchon.dsh.mysql;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * dsh-mysql 模块配置类。
 */
@Configuration
@EnableConfigurationProperties(com.bizfty.anchon.dsh.mysql.properties.MysqlProperties.class)
public class MysqlAutoConfiguration {
}