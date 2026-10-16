-- 用户实测：上传 2MB 需求文档 → 源文档落库报 "Data too long" → 接口 500。
-- 两列均为 TEXT（64KB），但需求文档全文可达数 MB（上传上限 25MB），扩为 LONGTEXT（与 MySQL 8.4 对齐）：
--   ai_source_documents.extracted_text —— 文档解析后的全文
--   ai_source_chunks.content_text     —— 文档级 chunk 直接落整篇全文（AiSourceDocumentService.buildChunk）
ALTER TABLE ai_source_documents MODIFY COLUMN extracted_text LONGTEXT;
ALTER TABLE ai_source_chunks MODIFY COLUMN content_text LONGTEXT;
