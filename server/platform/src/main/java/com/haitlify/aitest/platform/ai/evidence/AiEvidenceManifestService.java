package com.haitlify.aitest.platform.ai.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * T08：证据清单（manifest）服务——把项目来源物料逐文件转成可审计的覆盖条目：
 * 解析状态（AVAILABLE / PARTIAL / UNSUPPORTED）、warnings、证据位置（章节/页码/表格/
 * 图片）、chunk 覆盖与缺口提示。正文容器内图表、扫描件、独立图片与 Figma 不可达
 * 都必须显式呈现（PARTIAL/缺口），绝不静默忽略；不支持的解析不得冒充成功。
 */
@Service
public class AiEvidenceManifestService {

    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_UNSUPPORTED = "UNSUPPORTED";

    public record ManifestEntry(String documentId, String fileName, String documentType, String parseStatus,
                                List<String> warnings, int chunkCount, int tableCount, int imageCount,
                                String parserVersion, String contentHash, List<String> locations) {}

    public record EvidenceManifest(Long projectId, List<ManifestEntry> entries, int availableCount,
                                   int partialCount, int unsupportedCount, List<String> gaps) {}

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public AiEvidenceManifestService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /** 按项目构建证据清单（只读；不触发任何模型/网络调用）。 */
    public EvidenceManifest manifestForProject(Long projectId) {
        if (projectId == null) {
            throw new IllegalArgumentException("projectId required");
        }
        List<Map<String, Object>> documents = jdbcTemplate.queryForList(
            "select document_id, file_name, document_type, content_hash, extracted_text, parse_metadata_json,"
                + " parser_version from ai_source_documents where project_id = ? order by id asc",
            projectId);
        List<ManifestEntry> entries = new ArrayList<>();
        List<String> gaps = new ArrayList<>();
        int available = 0;
        int partial = 0;
        int unsupported = 0;
        for (Map<String, Object> document : documents) {
            String documentId = (String) document.get("document_id");
            String documentType = document.get("document_type") == null ? "" : document.get("document_type").toString();
            String extractedText = document.get("extracted_text") == null
                ? "" : document.get("extracted_text").toString();
            String metadataRaw = document.get("parse_metadata_json") == null
                ? "{}" : document.get("parse_metadata_json").toString();
            JsonNode metadata = readJson(metadataRaw);
            int tableCount = metadata.path("tableCount").asInt(0);
            int imageCount = metadata.path("imageCount").asInt(0);
            int chunkCount = chunkCount(documentId);

            List<String> warnings = new ArrayList<>();
            boolean textUsable = !extractedText.isBlank();
            String storedStatus = metadata.path("parseStatus").asText("");
            if (!storedStatus.isBlank()) {
                // 存储真相优先（解析时的逐来源结论），仅在缺失时按存储行回退推导。
                for (JsonNode warning : metadata.path("warnings")) {
                    warnings.add(warning.asText());
                }
            } else {
                // 扫描件/无可提取文本：显式 PARTIAL + warning，绝不当作已解析成功。
                if (!textUsable) {
                    warnings.add("no extractable text layer (scanned image or unsupported container)");
                }
                if (imageCount > 0) {
                    warnings.add(imageCount + " inline image(s) present; verify visual asset coverage separately");
                }
            }
            String parseStatus;
            if (!storedStatus.isBlank()) {
                parseStatus = storedStatus;
            } else if (!textUsable && imageCount == 0 && tableCount == 0 && !"image".equals(documentType)) {
                parseStatus = STATUS_UNSUPPORTED;
            } else if (!textUsable || !warnings.isEmpty()) {
                parseStatus = STATUS_PARTIAL;
            } else {
                parseStatus = STATUS_AVAILABLE;
            }
            if (!STATUS_AVAILABLE.equals(parseStatus)) {
                gaps.add(documentId + ":" + parseStatus);
            }
            switch (parseStatus) {
                case STATUS_AVAILABLE -> available++;
                case STATUS_PARTIAL -> partial++;
                default -> unsupported++;
            }
            entries.add(new ManifestEntry(
                documentId,
                document.get("file_name") == null ? "" : document.get("file_name").toString(),
                documentType,
                parseStatus,
                List.copyOf(warnings),
                chunkCount,
                tableCount,
                imageCount,
                document.get("parser_version") == null ? "" : document.get("parser_version").toString(),
                document.get("content_hash") == null ? "" : document.get("content_hash").toString(),
                locations(documentId)));
        }
        return new EvidenceManifest(projectId, List.copyOf(entries), available, partial, unsupported, List.copyOf(gaps));
    }

    /** 证据位置：章节标题 + 页码（缺失时按行号），供引用定位。 */
    private List<String> locations(String documentId) {
        List<Map<String, Object>> chunks = jdbcTemplate.queryForList(
            "select section_heading, page_number, line_start, line_end from ai_source_chunks"
                + " where document_id = (select id from ai_source_documents where document_id = ?) order by id asc",
            documentId);
        List<String> locations = new ArrayList<>();
        for (Map<String, Object> chunk : chunks) {
            String heading = chunk.get("section_heading") == null ? "" : chunk.get("section_heading").toString();
            Object page = chunk.get("page_number");
            Object lineStart = chunk.get("line_start");
            Object lineEnd = chunk.get("line_end");
            StringBuilder location = new StringBuilder();
            if (!heading.isBlank()) {
                location.append(heading);
            }
            if (page != null) {
                location.append(location.isEmpty() ? "" : " @").append("p").append(page);
            }
            if (lineStart != null) {
                location.append(location.isEmpty() ? "" : " ").append("L").append(lineStart);
                if (lineEnd != null) {
                    location.append("-").append(lineEnd);
                }
            }
            locations.add(location.isEmpty() ? "(no location)" : location.toString());
        }
        return locations;
    }

    private int chunkCount(String documentId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from ai_source_chunks"
                + " where document_id = (select id from ai_source_documents where document_id = ?)",
            Integer.class, documentId);
        return count == null ? 0 : count;
    }

    private JsonNode readJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception malformed) {
            return objectMapper.createObjectNode();
        }
    }
}
