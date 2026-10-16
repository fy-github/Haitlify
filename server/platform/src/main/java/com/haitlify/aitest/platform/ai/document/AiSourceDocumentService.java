package com.haitlify.aitest.platform.ai.document;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.haitlify.aitest.platform.ai.visual.AiVisualAssetService;
import com.haitlify.aitest.platform.auth.ProjectAccessService;
import com.haitlify.aitest.platform.ai.visual.CreatedVisualAsset;
import com.haitlify.aitest.platform.document.RequirementDocumentParseResponse;
import com.haitlify.aitest.platform.document.RequirementDocumentService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
class AiSourceDocumentService {

    private static final String PARSER_VERSION = "requirement-document-parser-v1";

    private final AiSourceDocumentRepository documentRepository;
    private final AiSourceChunkRepository chunkRepository;
    private final RequirementDocumentService requirementDocumentService;
    private final AiVisualAssetService visualAssetService;
    private final ObjectMapper objectMapper;
    private final ProjectAccessService projectAccessService;

    AiSourceDocumentService(
        AiSourceDocumentRepository documentRepository,
        AiSourceChunkRepository chunkRepository,
        RequirementDocumentService requirementDocumentService,
        AiVisualAssetService visualAssetService,
        ObjectMapper objectMapper,
        ProjectAccessService projectAccessService
    ) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.requirementDocumentService = requirementDocumentService;
        this.visualAssetService = visualAssetService;
        this.objectMapper = objectMapper;
        this.projectAccessService = projectAccessService;
    }

    AiSourceDocumentResponse upload(Long projectId, MultipartFile file) {
        projectAccessService.requireAccess(projectId);
        RequirementDocumentParseResponse parsed = requirementDocumentService.parse(file);
        byte[] bytes = readBytes(file);
        String contentHash = sha256(bytes);
        Instant now = Instant.now();
        CreatedVisualAsset visualAsset = "image".equals(parsed.documentType())
            ? visualAssetService.create(projectId, file)
            : null;

        AiSourceDocument document = documentRepository.save(new AiSourceDocument(
            null,
            "doc-" + UUID.randomUUID(),
            projectId,
            parsed.fileName(),
            parsed.documentType(),
            contentHash,
            "",
            parsed.text(),
            stringify(metadataWithParseFacts(objectMapper.createObjectNode()
                .put("lineCount", parsed.lineCount())
                .put("projectName", parsed.projectName())
                .put("parserVersion", PARSER_VERSION), parsed)),
            PARSER_VERSION,
            stringify(objectMapper.createObjectNode()
                .put("fileName", parsed.fileName())
                .put("contentType", file.getContentType() == null ? "" : file.getContentType())
                .put("size", file.getSize())
                .put("visualAssetId", visualAsset == null ? "" : visualAsset.assetId())),
            visualAsset == null ? null : visualAsset.id(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            now,
            now
        ));

        if (visualAsset != null) {
            visualAssetService.linkSourceDocument(visualAsset.assetId(), document.getId());
        }
        chunkRepository.save(buildChunk(document, now));
        return toDocumentResponse(document);
    }

    /** 项目级文档目录（运行卡选择器数据源）：只读，limit 50，新→旧；无权与不存在同形。 */
    AiSourceDocumentListResponse list(Long projectId) {
        requireReadAccess(projectId);
        List<AiSourceDocumentSummary> items = documentRepository
            .findByProjectIdOrderByCreatedAtDesc(projectId).stream()
            .limit(50)
            .map(document -> new AiSourceDocumentSummary(
                document.getDocumentId(),
                document.getFileName(),
                document.getDocumentType(),
                document.getParserVersion(),
                document.getCreatedAt()))
            .toList();
        return new AiSourceDocumentListResponse(items);
    }

    AiSourceDocumentResponse getDocument(String documentId) {
        AiSourceDocument document = findDocumentForRead(documentId);
        requireReadAccess(document.getProjectId());
        return toDocumentResponse(document);
    }

    /** T14-R5：读取路径——不存在与无权同形 404 not_found（不可用状态码/文案区分对象存在）。 */
    private AiSourceDocument findDocumentForRead(String documentId) {
        return documentRepository.findByDocumentId(documentId).orElseThrow(
            com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization::notFound);
    }

    private void requireReadAccess(Long projectId) {
        try {
            projectAccessService.requireAccess(projectId);
        } catch (org.springframework.web.server.ResponseStatusException denied) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
    }

    AiSourceChunkListResponse listChunks(String documentId) {
        AiSourceDocument document = findDocumentForRead(documentId);
        requireReadAccess(document.getProjectId());
        List<AiSourceChunkResponse> items = chunkRepository.findByDocumentIdOrderByIdAsc(document.getId()).stream()
            .map(chunk -> toChunkResponse(document, chunk))
            .toList();
        return new AiSourceChunkListResponse(items);
    }

    private AiSourceChunk buildChunk(AiSourceDocument document, Instant now) {
        String contentText = document.getExtractedText() == null ? "" : document.getExtractedText();
        int lineEnd = contentText.isBlank() ? 0 : (int) contentText.lines().count();
        return new AiSourceChunk(
            null,
            "chunk-" + UUID.randomUUID(),
            document.getId(),
            document.getFileName(),
            null,
            contentText.isBlank() ? 0 : 1,
            lineEnd,
            "",
            "",
            "",
            "text",
            sha256(contentText.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            contentText,
            stringify(objectMapper.createObjectNode()
                .put("documentId", document.getDocumentId())
                .put("fileName", document.getFileName())
                .put("documentType", document.getDocumentType())
                .put("parserVersion", document.getParserVersion())),
            now,
            now
        );
    }

    private AiSourceDocumentResponse toDocumentResponse(AiSourceDocument document) {
        return new AiSourceDocumentResponse(
            document.getDocumentId(),
            document.getProjectId(),
            document.getFileName(),
            document.getDocumentType(),
            document.getContentHash(),
            document.getExtractedText(),
            document.getParserVersion(),
            parse(document.getParseMetadataJson()),
            parse(document.getSourceFileMetadataJson()),
            visualAssetService.assetIdForPk(document.getVisualAssetId()),
            document.getActorId(),
            document.getActorName(),
            document.getCreatedAt() == null ? null : document.getCreatedAt().toString(),
            document.getUpdatedAt() == null ? null : document.getUpdatedAt().toString()
        );
    }

    private AiSourceChunkResponse toChunkResponse(AiSourceDocument document, AiSourceChunk chunk) {
        return new AiSourceChunkResponse(
            chunk.getChunkId(),
            document.getDocumentId(),
            document.getFileName(),
            chunk.getSectionHeading(),
            chunk.getLineStart(),
            chunk.getLineEnd(),
            chunk.getChunkType(),
            chunk.getContentHash(),
            chunk.getContentText(),
            parse(chunk.getEvidenceMetadataJson())
        );
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to read source document bytes", exception);
        }
    }

    /** T08：把解析状态/warnings/计数一并落入 parse_metadata_json（证据清单的存储真相）。 */
    private tools.jackson.databind.node.ObjectNode metadataWithParseFacts(
        tools.jackson.databind.node.ObjectNode metadata,
        RequirementDocumentParseResponse parsed
    ) {
        metadata.put("parseStatus", parsed.parseStatus() == null ? "PARTIAL" : parsed.parseStatus());
        metadata.put("tableCount", parsed.tableCount());
        metadata.put("imageCount", parsed.imageCount());
        var warnings = metadata.putArray("warnings");
        if (parsed.warnings() != null) {
            for (String warning : parsed.warnings()) {
                warnings.add(warning);
            }
        }
        return metadata;
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash source document", exception);
        }
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AI source document JSON", exception);
        }
    }

    private String stringify(JsonNode node) {
        if (node == null) return null;
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid AI source document payload", exception);
        }
    }
}

record AiSourceDocumentResponse(
    String documentId,
    Long projectId,
    String fileName,
    String documentType,
    String contentHash,
    String extractedText,
    String parserVersion,
    JsonNode parseMetadata,
    JsonNode sourceFileMetadata,
    String visualAssetId,
    String actorId,
    String actorName,
    String createdAt,
    String updatedAt
) {
}

record AiSourceChunkResponse(
    String chunkId,
    String documentId,
    String sourceFile,
    String sectionHeading,
    Integer lineStart,
    Integer lineEnd,
    String chunkType,
    String contentHash,
    String contentText,
    JsonNode evidenceMetadata
) {
}

record AiSourceDocumentSummary(
    String documentId,
    String fileName,
    String documentType,
    String parserVersion,
    Instant createdAt
) {
}

record AiSourceDocumentListResponse(
    List<AiSourceDocumentSummary> items
) {
}

record AiSourceChunkListResponse(
    List<AiSourceChunkResponse> items
) {
}
