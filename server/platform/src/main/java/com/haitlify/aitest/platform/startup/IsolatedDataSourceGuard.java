package com.haitlify.aitest.platform.startup;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * 数据源「实际生效目标」校验（计划 §7.1）。
 *
 * <p>第三轮独立复核实测出一个绕过：只在环境属性层面校验（只认值形如 {@code jdbc:} 的属性）
 * 会漏掉间接改写——例如通过挂载的 {@code application.yml} 设置
 * {@code spring.datasource.hikari.data-source-class-name} +
 * {@code spring.datasource.hikari.data-source-properties.serverName/port/databaseName}，
 * 此时 HikariCP 会忽略已校验的 jdbcUrl，直接按 dataSource 属性连接攻击者指定的主机。
 *
 * <p>因此这里在**数据源 bean 被创建时**读取它真正持有的连接配置（jdbcUrl / dataSourceClassName /
 * dataSourceProperties），凡不是隔离目标、或存在能绕过 jdbcUrl 的间接配置，即拒绝启动——
 * 这发生在任何连接之前，也发生在 Flyway 迁移之前。
 */
public final class IsolatedDataSourceGuard {

    private IsolatedDataSourceGuard() {
    }

    /** 返回违规项；空列表表示该数据源的实际目标满足隔离契约。 */
    public static List<String> violations(DataSource dataSource, String beanName) {
        List<String> violations = new ArrayList<>();
        String name = beanName == null || beanName.isBlank() ? dataSource.getClass().getSimpleName() : beanName;
        String type = dataSource.getClass().getName();
        String jdbcUrl = readString(dataSource, "getJdbcUrl", "getUrl", "getURL");
        String dataSourceClassName = readString(dataSource, "getDataSourceClassName");
        Object dataSourceProperties = read(dataSource, "getDataSourceProperties");

        if (dataSourceClassName != null && !dataSourceClassName.isBlank()) {
            violations.add(name + " 声明了 dataSourceClassName=" + dataSourceClassName
                    + "：会绕过 jdbcUrl 直接由驱动类决定连接目标");
        }
        if (dataSourceProperties instanceof Map<?, ?> properties && !properties.isEmpty()) {
            violations.add(name + " 声明了 dataSourceProperties（" + properties.size()
                    + " 项）：可间接改写连接目标（如 serverName/port/databaseName）");
        }
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            violations.add(name + "（" + type + "）读不到实际 JDBC 地址：无法验证隔离目标，拒绝启动");
        } else {
            violations.addAll(IsolatedEnvironmentGuard.datasourceViolations(jdbcUrl));
        }
        return List.copyOf(violations);
    }

    /** 依次尝试若干 getter，返回第一个可读到的字符串值。 */
    static String readString(Object target, String... getters) {
        for (String getter : getters) {
            Object value = read(target, getter);
            if (value instanceof String text) {
                return text;
            }
        }
        return null;
    }

    static Object read(Object target, String getter) {
        try {
            Method method = target.getClass().getMethod(getter);
            return method.invoke(target);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | RuntimeException error) {
            return null;
        }
    }
}
