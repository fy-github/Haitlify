package com.haitlify.aitest.platform.ai.figma;

import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.haitlify.aitest.platform.document.FigmaDesignClient;
import com.haitlify.aitest.platform.document.FigmaDesignParseResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
class AiFigmaImportService {

    private static final Pattern FILE_KEY_PATTERN = Pattern.compile("figma\\.com/(?:design|file)/([^/?]+)");
    private static final Pattern NODE_ID_PATTERN = Pattern.compile("[?&]node-id=([^&]+)");

    private final AiFigmaFileRepository repository;
    private final FigmaDesignClient figmaDesignClient;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    AiFigmaImportService(
        AiFigmaFileRepository repository,
        FigmaDesignClient figmaDesignClient,
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.figmaDesignClient = figmaDesignClient;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    AiFigmaFileResponse importFigma(ImportAiFigmaRequest request) {
        requireFigmaEnabled();
        String fileKey = blankToDefault(request.fileKey(), extract(request.figmaUrl(), FILE_KEY_PATTERN));
        String nodeId = blankToDefault(request.nodeId(), extract(request.figmaUrl(), NODE_ID_PATTERN).replace('-', ':'));
        if (fileKey.isBlank()) {
            throw new IllegalArgumentException("Figma file key is required");
        }
        FigmaDesignParseResult parsed = figmaDesignClient.fetchDesign(fileKey, nodeId);
        String rawSummaryJson = summaryJson(fileKey, nodeId, parsed);
        String snapshotHash = sha256(rawSummaryJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        AiFigmaFile existing = repository.findByProjectIdAndFileKeyAndSelectedNodeId(request.projectId(), fileKey, nodeId).orElse(null);
        Instant now = Instant.now();
        AiFigmaFile saved = existing == null
            ? repository.save(new AiFigmaFile(
                null,
                "figma-file-" + UUID.randomUUID(),
                request.projectId(),
                fileKey,
                nodeId,
                parsed.fileName(),
                snapshotHash,
                rawSummaryJson,
                com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
                com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
                now,
                now
            ))
            : updateExisting(existing, parsed.fileName(), snapshotHash, rawSummaryJson, now);
        return toResponse(saved);
    }

    AiFigmaFileResponse getFile(String figmaFileId) {
        return toResponse(repository.findByFigmaFileId(figmaFileId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown Figma file: " + figmaFileId)));
    }

    private AiFigmaFile updateExisting(AiFigmaFile existing, String fileName, String snapshotHash, String rawSummaryJson, Instant now) {
        jdbcTemplate.update("""
            update ai_figma_files
            set file_name = ?, snapshot_hash = ?, raw_summary_json = ?, updated_at = ?
            where id = ?
            """,
            fileName,
            snapshotHash,
            rawSummaryJson,
            Timestamp.from(now),
            existing.getId()
        );
        return repository.findByFigmaFileId(existing.getFigmaFileId()).orElseThrow();
    }

    private void requireFigmaEnabled() {
        List<String> configs = jdbcTemplate.queryForList(
            "select provider_config_json from ai_system_configs where config_key = 'default'",
            String.class
        );
        if (configs.isEmpty()) {
            throw new IllegalArgumentException("Figma import is disabled");
        }
        JsonNode config = parse(configs.getFirst());
        if (!config.path("figmaEnabled").asBoolean(false) || !config.path("figmaTokenConfigured").asBoolean(false)) {
            throw new IllegalArgumentException("Figma import is disabled");
        }
    }

    private String summaryJson(String fileKey, String nodeId, FigmaDesignParseResult parsed) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("fileKey", fileKey);
        summary.put("nodeId", nodeId);
        summary.put("fileName", parsed.fileName());
        summary.put("text", parsed.text());
        summary.set("visualSummary", objectMapper.valueToTree(parsed.visualSummary()));
        summary.set("suggestedRequirementPoints", objectMapper.valueToTree(parsed.suggestedRequirementPoints()));
        return stringify(summary);
    }

    private AiFigmaFileResponse toResponse(AiFigmaFile file) {
        return new AiFigmaFileResponse(
            file.getFigmaFileId(),
            file.getProjectId(),
            file.getFileKey(),
            file.getSelectedNodeId(),
            file.getFileName(),
            file.getSnapshotHash(),
            parse(file.getRawSummaryJson()),
            file.getActorId(),
            file.getActorName(),
            file.getCreatedAt() == null ? null : file.getCreatedAt().toString(),
            file.getUpdatedAt() == null ? null : file.getUpdatedAt().toString()
        );
    }

    private String extract(String value, Pattern pattern) {
        if (value == null || value.isBlank()) {
            return "";
        }
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            return objectMapper.createObjectNode();
        }
    }

    private String stringify(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid Figma import payload", exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash Figma snapshot", exception);
        }
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

record ImportAiFigmaRequest(
    Long projectId,
    String figmaUrl,
    String fileKey,
    String nodeId
) {
}

record AiFigmaFileResponse(
    String figmaFileId,
    Long projectId,
    String fileKey,
    String selectedNodeId,
    String fileName,
    String snapshotHash,
    JsonNode rawSummary,
    String actorId,
    String actorName,
    String createdAt,
    String updatedAt
) {
}
