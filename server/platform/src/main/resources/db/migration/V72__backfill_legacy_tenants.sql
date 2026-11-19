-- 租户回填：历史 assistant_* 行里的 tenant_id 可能没有对应 tenants 行。
-- 不回填的话，这些租户连 ADMIN 都会被「租户不存在」拒绝，历史数据变成只读孤儿。
--
-- 关键点（两轮复核后的最终口径）：
--   1) legacy 租户**显式使用偏移 id**（历史 id + 1000000000），而不是自增 id：
--      自增 id 可能正好等于另一个历史 tenant_id（历史 {2,7} ⇒ legacy-7 拿到 2），
--      之后无论按 id 还是按 code 重映射都会把两个租户搅在一起（第五轮复核 P2）。
--      偏移 id 与历史 id 永不相交，且不动自增序列。
--   2) 若 tenants 里已存在 id = 历史 tenant_id 的真租户，直接复用该真租户（不再建 legacy）。
--   3) 全部可重复执行：重放不会 Duplicate entry。
--   4) 项目归属来源覆盖 operation / request / draft 三张表（只在草稿里出现的项目也要回填），
--      优先级 operation > request > draft；三段 UPDATE 各自只填空值，天然实现优先级。
INSERT INTO tenants(id, code, name, active, created_at, updated_at)
SELECT t.tenant_id + 1000000000, CONCAT('legacy-', t.tenant_id), CONCAT('历史租户 ', t.tenant_id),
       TRUE, NOW(), NOW()
FROM (
    SELECT DISTINCT tenant_id FROM assistant_request_ledger
    UNION
    SELECT DISTINCT tenant_id FROM assistant_operation_ledger
    UNION
    SELECT DISTINCT tenant_id FROM assistant_draft
) t
WHERE t.tenant_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM tenants x WHERE x.id = t.tenant_id)
  AND NOT EXISTS (SELECT 1 FROM tenants x WHERE x.code = CONCAT('legacy-', t.tenant_id))
  AND NOT EXISTS (SELECT 1 FROM tenants x WHERE x.id = t.tenant_id + 1000000000);

-- 优先级 1：历史 operation。用相关子查询而不是 MySQL 的 UPDATE ... JOIN（H2 不支持多表 UPDATE）。
UPDATE projects
SET tenant_id = (
    SELECT COALESCE(
        (SELECT x.id FROM tenants x WHERE x.id = o.tenant_id),
        (SELECT x.id FROM tenants x WHERE x.code = CONCAT('legacy-', o.tenant_id))
    )
    FROM assistant_operation_ledger o
    WHERE o.project_id = projects.id
      AND (SELECT COUNT(*) FROM tenants x WHERE x.id = o.tenant_id
              OR x.code = CONCAT('legacy-', o.tenant_id)) > 0
    LIMIT 1
)
WHERE tenant_id IS NULL
  AND EXISTS (
      SELECT 1 FROM assistant_operation_ledger o2
      JOIN tenants x2 ON x2.id = o2.tenant_id OR x2.code = CONCAT('legacy-', o2.tenant_id)
      WHERE o2.project_id = projects.id
  );

-- 优先级 2：历史受理请求（可能只有受理、没有派发）。
UPDATE projects
SET tenant_id = (
    SELECT COALESCE(
        (SELECT x.id FROM tenants x WHERE x.id = r.tenant_id),
        (SELECT x.id FROM tenants x WHERE x.code = CONCAT('legacy-', r.tenant_id))
    )
    FROM assistant_request_ledger r
    WHERE r.project_id = projects.id
      AND (SELECT COUNT(*) FROM tenants x WHERE x.id = r.tenant_id
              OR x.code = CONCAT('legacy-', r.tenant_id)) > 0
    LIMIT 1
)
WHERE tenant_id IS NULL
  AND EXISTS (
      SELECT 1 FROM assistant_request_ledger r2
      JOIN tenants x2 ON x2.id = r2.tenant_id OR x2.code = CONCAT('legacy-', r2.tenant_id)
      WHERE r2.project_id = projects.id
  );

-- 优先级 3：历史草稿（可能只停在草稿阶段，从未受理/派发）。
UPDATE projects
SET tenant_id = (
    SELECT COALESCE(
        (SELECT x.id FROM tenants x WHERE x.id = d.tenant_id),
        (SELECT x.id FROM tenants x WHERE x.code = CONCAT('legacy-', d.tenant_id))
    )
    FROM assistant_draft d
    WHERE d.project_id = projects.id
      AND (SELECT COUNT(*) FROM tenants x WHERE x.id = d.tenant_id
              OR x.code = CONCAT('legacy-', d.tenant_id)) > 0
    LIMIT 1
)
WHERE tenant_id IS NULL
  AND EXISTS (
      SELECT 1 FROM assistant_draft d2
      JOIN tenants x2 ON x2.id = d2.tenant_id OR x2.code = CONCAT('legacy-', d2.tenant_id)
      WHERE d2.project_id = projects.id
  );
