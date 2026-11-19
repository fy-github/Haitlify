-- 主体 ↔ 租户：把「租户」从调用方随意传入的数字，变成可校验的实体。
--
-- 之前 assistant_* 的 tenant_id 只是请求里的一个数字，没有实体、没有成员关系：
-- 任何拿到接口权限的人都能按任意 tenantId 读写（全局 ADMIN 更没有任何约束）。
-- 这里补上最小但可校验的模型：
--   tenants         租户实体（可停用）
--   tenant_members  主体（platform_users.username）↔ 租户成员关系
--   projects.tenant_id  项目归属租户；为空表示历史项目尚未绑定（由启动引导回填）
CREATE TABLE tenants (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_tenants_code UNIQUE (code)
);

ALTER TABLE projects ADD COLUMN tenant_id BIGINT NULL;
ALTER TABLE projects ADD CONSTRAINT fk_projects_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id);
CREATE INDEX idx_projects_tenant ON projects(tenant_id);

CREATE TABLE tenant_members (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    username VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_tenant_members_tenant_username UNIQUE (tenant_id, username),
    CONSTRAINT fk_tenant_members_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT fk_tenant_members_user FOREIGN KEY (username) REFERENCES platform_users(username)
);
CREATE INDEX idx_tenant_members_username ON tenant_members(username, tenant_id);
