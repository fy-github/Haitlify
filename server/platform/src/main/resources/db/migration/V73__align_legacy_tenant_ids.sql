-- 让历史账本的 tenant_id 真正可达（第四/五轮复核）。
--
-- 背景：历史 assistant_* 行里的 tenant_id 是旧库的空间 id，tenants 里没有对应行；
-- 读接口都按 `tenant_id = ?` 过滤 ⇒ 历史单据「按新租户 id 查不到、按旧 id 又被 403」。
--
-- 第五轮教训（P2）：不能让 legacy 租户用自增 id——自增 id 可能正好等于另一个历史 tenant_id
-- （历史 {2,7} ⇒ legacy-7 拿到 id=2），之后按 id 重映射会把两个租户合并。
-- 因此 V72 起 legacy 租户一律用**偏移 id（历史 id + 1000000000）**，与历史 id、自增序列都不相交。
-- 本迁移只做「补缺 + 映射 + 搬迁」，用映射表与守卫表保证重放安全。
CREATE TABLE IF NOT EXISTS tenant_migration_guard (
    name VARCHAR(64) PRIMARY KEY,
    applied_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS tenant_legacy_map (
    from_id BIGINT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_tenant_legacy_map_tenant FOREIGN KEY (tenant_id) REFERENCES tenants(id)
);

-- 1) 仍缺 legacy 租户的历史 id（例如 V72 之后才写入的旧数据）⇒ 用偏移 id 补建。
INSERT INTO tenants(id, code, name, active, created_at, updated_at)
SELECT h.from_id + 1000000000, CONCAT('legacy-', h.from_id), CONCAT('历史租户 ', h.from_id),
       TRUE, NOW(), NOW()
FROM (
    SELECT DISTINCT tenant_id AS from_id FROM assistant_request_ledger
    UNION
    SELECT DISTINCT tenant_id FROM assistant_operation_ledger
    UNION
    SELECT DISTINCT tenant_id FROM assistant_draft
) h
WHERE h.from_id IS NOT NULL
  AND h.from_id < 1000000000
  AND NOT EXISTS (SELECT 1 FROM tenants x WHERE x.id = h.from_id)
  AND NOT EXISTS (SELECT 1 FROM tenants x WHERE x.code = CONCAT('legacy-', h.from_id))
  AND NOT EXISTS (SELECT 1 FROM tenants x WHERE x.id = h.from_id + 1000000000)
  AND NOT EXISTS (SELECT 1 FROM tenant_migration_guard WHERE name = 'v73_align_legacy_tenant_ids');

-- 2) 固定映射：历史 id → legacy 租户真实 id（同一历史 id 只记一次）。
INSERT INTO tenant_legacy_map(from_id, tenant_id, created_at)
SELECT h.from_id, x.id, NOW()
FROM (
    SELECT DISTINCT tenant_id AS from_id FROM assistant_request_ledger
    UNION
    SELECT DISTINCT tenant_id FROM assistant_operation_ledger
    UNION
    SELECT DISTINCT tenant_id FROM assistant_draft
) h
JOIN tenants x ON x.code = CONCAT('legacy-', h.from_id)
WHERE h.from_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM tenant_legacy_map m WHERE m.from_id = h.from_id)
  AND NOT EXISTS (SELECT 1 FROM tenant_migration_guard WHERE name = 'v73_align_legacy_tenant_ids');

-- 3) 账本行按映射搬迁：只处理「现值恰好是历史 id」的行。
--    偏移 id ≥ 1e9，搬过的行不会再被匹配 ⇒ 即使去掉守卫也不会反复搬家。
UPDATE assistant_draft
SET tenant_id = (SELECT m.tenant_id FROM tenant_legacy_map m WHERE m.from_id = assistant_draft.tenant_id)
WHERE EXISTS (SELECT 1 FROM tenant_legacy_map m
              WHERE m.from_id = assistant_draft.tenant_id AND m.tenant_id <> assistant_draft.tenant_id)
  AND NOT EXISTS (SELECT 1 FROM tenant_migration_guard WHERE name = 'v73_align_legacy_tenant_ids');

UPDATE assistant_request_ledger
SET tenant_id = (SELECT m.tenant_id FROM tenant_legacy_map m WHERE m.from_id = assistant_request_ledger.tenant_id)
WHERE EXISTS (SELECT 1 FROM tenant_legacy_map m
              WHERE m.from_id = assistant_request_ledger.tenant_id
                AND m.tenant_id <> assistant_request_ledger.tenant_id)
  AND NOT EXISTS (SELECT 1 FROM tenant_migration_guard WHERE name = 'v73_align_legacy_tenant_ids');

UPDATE assistant_operation_ledger
SET tenant_id = (SELECT m.tenant_id FROM tenant_legacy_map m WHERE m.from_id = assistant_operation_ledger.tenant_id)
WHERE EXISTS (SELECT 1 FROM tenant_legacy_map m
              WHERE m.from_id = assistant_operation_ledger.tenant_id
                AND m.tenant_id <> assistant_operation_ledger.tenant_id)
  AND NOT EXISTS (SELECT 1 FROM tenant_migration_guard WHERE name = 'v73_align_legacy_tenant_ids');

-- 4) 项目：V72 已按 code 指到 legacy 租户；这里兜底「还停在历史 id」的行。
UPDATE projects
SET tenant_id = (SELECT m.tenant_id FROM tenant_legacy_map m WHERE m.from_id = projects.tenant_id)
WHERE tenant_id IS NOT NULL
  AND EXISTS (SELECT 1 FROM tenant_legacy_map m
              WHERE m.from_id = projects.tenant_id AND m.tenant_id <> projects.tenant_id)
  AND NOT EXISTS (SELECT 1 FROM tenant_migration_guard WHERE name = 'v73_align_legacy_tenant_ids');

-- 5) 标记已应用：DBA 重放时整段 no-op。
INSERT INTO tenant_migration_guard(name, applied_at)
SELECT 'v73_align_legacy_tenant_ids', NOW()
WHERE NOT EXISTS (SELECT 1 FROM tenant_migration_guard WHERE name = 'v73_align_legacy_tenant_ids');
