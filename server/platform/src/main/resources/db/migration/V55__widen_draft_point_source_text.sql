-- 用户实测类缺陷：草稿点 source_text 原样抄入来源 chunk 全文（文档级 chunk = 整篇正文）。
-- 上传上限 25MB 生效后，几十 KB 以上的文档在合并落库时截断报 500（TEXT 仅 64KB）。
-- 与 V54 同类修复：扩为 LONGTEXT（与 MySQL 8.4 对齐）。
ALTER TABLE ai_draft_test_points MODIFY COLUMN source_text LONGTEXT;
