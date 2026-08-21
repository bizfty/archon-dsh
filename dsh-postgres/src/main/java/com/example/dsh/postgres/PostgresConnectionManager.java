package com.example.dsh.postgres;

import com.example.dsh.postgres.properties.PostgresProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * PostgreSQL 连接管理器 — 懒加载创建只读连接。
 */
@Component
public class PostgresConnectionManager {

    private static final Logger log = LoggerFactory.getLogger(PostgresConnectionManager.class);

    private final PostgresProperties properties;
    private final Object lock = new Object();
    private volatile Connection connection;

    public PostgresConnectionManager(PostgresProperties properties) {
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
                throw new SQLException("PostgreSQL URL 未配置 (dsh.postgres.url)");
            }
            Properties props = new Properties();
            props.setProperty("user", properties.getUsername() == null ? "" : properties.getUsername());
            props.setProperty("password", properties.getPassword() == null ? "" : properties.getPassword());
            props.setProperty("ssl", "false");
            props.setProperty("autoCommit", "false");
            Connection conn = DriverManager.getConnection(url, props);
            if (properties.isReadOnly()) {
                conn.setReadOnly(true);
                try (var st = conn.createStatement()) {
                    st.execute("SET TRANSACTION READ ONLY");
                }
            }
            conn.setAutoCommit(false);
            log.info("PostgreSQL connection established to {}", sanitize(url));
            this.connection = conn;
            return conn;
        }
    }

    private String sanitize(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}