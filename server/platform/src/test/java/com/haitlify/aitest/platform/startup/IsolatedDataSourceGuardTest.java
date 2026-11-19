package com.haitlify.aitest.platform.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;

class IsolatedDataSourceGuardTest {

    private static final String ISOLATED_URL = "jdbc:mysql://mysql:3306/haitlify_isolated?useUnicode=true";

    /** 最小 DataSource 桩：只暴露被校验的取值器，不建立任何连接。 */
    static class StubDataSource implements DataSource {

        private final String jdbcUrl;
        private final String dataSourceClassName;
        private final Map<String, Object> dataSourceProperties;

        StubDataSource(String jdbcUrl, String dataSourceClassName, Map<String, Object> dataSourceProperties) {
            this.jdbcUrl = jdbcUrl;
            this.dataSourceClassName = dataSourceClassName;
            this.dataSourceProperties = dataSourceProperties;
        }

        public String getJdbcUrl() {
            return jdbcUrl;
        }

        public String getDataSourceClassName() {
            return dataSourceClassName;
        }

        public Map<String, Object> getDataSourceProperties() {
            return dataSourceProperties;
        }

        @Override
        public Connection getConnection() throws SQLException {
            throw new UnsupportedOperationException("测试桩不建立连接");
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new UnsupportedOperationException("测试桩不建立连接");
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException("测试桩不支持");
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("测试桩不支持 unwrap");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }

    /** 完全没有 URL 取值器的数据源（无法验证 ⇒ fail closed）。 */
    static class OpaqueDataSource extends StubDataSource {
        OpaqueDataSource() {
            super(null, null, null);
        }

        @Override
        public String getJdbcUrl() {
            throw new UnsupportedOperationException("no url");
        }
    }

    private static List<String> violations(DataSource dataSource) {
        return IsolatedDataSourceGuard.violations(dataSource, "dataSource");
    }

    @Test
    void acceptsIsolatedJdbcUrl() {
        assertTrue(violations(new StubDataSource(ISOLATED_URL, null, Map.of())).isEmpty());
    }

    @Test
    void rejectsForeignTargetOnTheEffectivePoolConfig() {
        List<String> violations = violations(new StubDataSource("jdbc:mysql://legacy.example.internal:3306/haitlify_isolated", null, Map.of()));
        assertTrue(violations.stream().anyMatch(message -> message.contains("不是隔离目标")));
    }

    @Test
    void rejectsLegacyDatabaseOnTheEffectivePoolConfig() {
        List<String> violations = violations(new StubDataSource("jdbc:mysql://127.0.0.1:3306/ai_test_platform", null, Map.of()));
        assertTrue(violations.stream().anyMatch(message -> message.contains("旧资源标识 ai_test_platform")));
    }

    @Test
    void rejectsDataSourceClassNameThatWouldBypassJdbcUrl() {
        List<String> violations = violations(new StubDataSource(ISOLATED_URL, "com.mysql.cj.jdbc.MysqlDataSource", Map.of()));
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("dataSourceClassName"));
    }

    @Test
    void rejectsDataSourcePropertiesThatRedirectTheTarget() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("serverName", "legacy.example.internal");
        properties.put("port", "3306");
        properties.put("databaseName", "haitlify_isolated");
        List<String> violations = violations(new StubDataSource(ISOLATED_URL, null, properties));
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("dataSourceProperties"));
    }

    @Test
    void rejectsDataSourceWithoutReadableUrl() {
        List<String> violations = violations(new OpaqueDataSource());
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("读不到实际 JDBC 地址"));
    }

    @Test
    void redactsCredentialsInsideEffectiveJdbcUrl() {
        List<String> violations = violations(new StubDataSource(
                "jdbc:mysql://app:s3cret@127.0.0.1:3306/haitlify_isolated", null, Map.of()));
        assertTrue(violations.stream().noneMatch(message -> message.contains("s3cret")));
    }

    @Test
    void postProcessorPassesTheBeanThroughWhenTargetIsIsolated() {
        StubDataSource dataSource = new StubDataSource(ISOLATED_URL, null, Map.of());
        assertEquals(dataSource, new IsolatedDataSourcePostProcessor().postProcessAfterInitialization(dataSource, "dataSource"));
    }

    @Test
    void postProcessorRefusesWhenTheEffectiveTargetIsRewritten() {
        Map<String, Object> properties = Map.of("serverName", "legacy.example.internal");
        StubDataSource dataSource = new StubDataSource(ISOLATED_URL, null, properties);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> new IsolatedDataSourcePostProcessor().postProcessAfterInitialization(dataSource, "dataSource"));
        assertTrue(error.getMessage().contains("隔离环境启动保护拒绝启动（数据源实际目标）"));
    }

    @Test
    void postProcessorIgnoresNonDataSourceBeans() {
        Object bean = "not-a-datasource";
        assertEquals(bean, new IsolatedDataSourcePostProcessor().postProcessAfterInitialization(bean, "someBean"));
    }
}
