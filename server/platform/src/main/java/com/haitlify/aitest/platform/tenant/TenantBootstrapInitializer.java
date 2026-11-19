package com.haitlify.aitest.platform.tenant;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 租户模型引导：老部署升级到 V71 时不会突然「全员无权」。
 *
 * <p>只做一次、可重复执行的事情：
 * <ol>
 *   <li>没有任何租户时，建一个 {@code default} 租户；</li>
 *   <li>把尚未绑定租户的历史项目挂到该租户。</li>
 * </ol>
 *
 * <p>引导只补「缺省归属」，不会覆盖已有绑定：再次启动时若 tenants 非空则直接跳过。
 */
@Component
public class TenantBootstrapInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TenantBootstrapInitializer.class);

    private final JdbcTemplate jdbcTemplate;

    public TenantBootstrapInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant now = Instant.now();
        // 幂等：只保证 default 租户存在。V72 可能已经为历史账本建过 legacy-* 租户，
        // 这里若因为「已有租户」直接 return，就会永远没有 default，新项目无从归属。
        jdbcTemplate.update(
            "insert into tenants(code, name, active, created_at, updated_at)"
                + " select ?, ?, true, ?, ? where not exists (select 1 from tenants where code = ?)",
            "default", "默认租户", now, now, "default");
        Long tenantId = jdbcTemplate.queryForObject(
            "select id from tenants where code = ?", Long.class, "default");

        int bound = jdbcTemplate.update(
            "update projects set tenant_id = ? where tenant_id is null", tenantId);
        // 不在这里给 ADMIN 写成员行：ADMIN 本来就跨租户放行，写成员行只会让「删用户」被外键挡住。
        // 真正的成员关系由管理接口/后续租户管理功能显式授予。
        log.info("租户模型引导：创建 default 租户 id={}，回填项目 {} 个", tenantId, bound);
    }
}
