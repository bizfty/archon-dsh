package com.bizfty.anchon.dsh.mysql;

import com.bizfty.anchon.dsh.mysql.properties.MysqlProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * MySQL 连接管理器 — 懒加载创建只读连接。
 */
@Component
public class MysqlConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(MysqlConnectionManager.class);

    private final MysqlProperties properties;
    private final Object lock = new Object();
    private volatile Connection connection;

    public MysqlConnectionManager(MysqlProperties properties) {
        this.properties = properties;
    }

    public Connection getConnection() throws SQLException {
        if (connection != null && !connection.isClosed() && connection.isValid(2)) {
            return connection;
        }
        synchronized (lock) {
            if (connection != null && !connection.isClosed() && connection.isValid(2)) {
                return connection;
            }
            String url = properties.getUrl();
            if (url == null || url.isBlank()) {
                throw new SQLException("MySQL URL 未配置 (dsh.mysql.url)");
            }
            Properties props = new Properties();
            props.setProperty("user", properties.getUsername() == null ? "" : properties.getUsername());
            props.setProperty("password", properties.getPassword() == null ? "" : properties.getPassword());
            props.setProperty("useSSL", "false");
            props.setProperty("allowMultiQueries", "false");
            props.setProperty("allowUrlInLocalInfile", "false");
            props.setProperty("autoCommit", "false");
            Connection conn = DriverManager.getConnection(url, props);
            if (properties.isReadOnly()) {
                conn.setReadOnly(true);
            }
            conn.setAutoCommit(false);
            log.info("MySQL connection established to {}", sanitize(url));
            this.connection = conn;
            return conn;
        }
    }

    private String sanitize(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}