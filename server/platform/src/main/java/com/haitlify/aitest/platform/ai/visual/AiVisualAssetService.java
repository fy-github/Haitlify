package com.haitlify.aitest.platform.ai.visual;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
public class AiVisualAssetService {

    private static final long DEFAULT_MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final List<String> DEFAULT_ALLOWED_IMAGE_TYPES = List.of("image/png", "image/jpeg", "image/webp");

    private final AiVisualAssetRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    AiVisualAssetService(AiVisualAssetRepository repository, JdbcTemplate jdbcTemplate,
                         ObjectMapper objectMapper,
                         com.haitlify.aitest.platform.auth.ProjectAccessService projectAccessService) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.projectAccessService = projectAccessService;
    }

    private final com.haitlify.aitest.platform.auth.ProjectAccessService projectAccessService;

    AiVisualAssetResponse upload(Long projectId, MultipartFile file) {
        // T-R5 轮 3：写入前授权——不得「跨项目落库成功却返回 404」（先写后读才鉴权的旧语义）。
        try {
            projectAccessService.requireAccess(projectId);
        } catch (org.springframework.web.server.ResponseStatusException denied) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
        CreatedVisualAsset created = create(projectId, file);
        return getAsset(created.assetId());
    }

    public CreatedVisualAsset create(Long projectId, MultipartFile file) {
        VisualAssetSettings settings = loadSettings();
        String contentType = file.getContentType() == null ? "" : file.getContentType().trim();
        if (!settings.allowedImageTypes().contains(contentType)) {
            throw new IllegalArgumentException("Unsupported visual asset type: " + contentType);
        }
        if (file.getSize() > settings.maxImageBytes()) {
            throw new IllegalArgumentException("Visual asset is too large: " + file.getSize());
        }

        byte[] bytes = readBytes(file);
        ImageDimensions dimensions = dimensions(bytes);
        Instant now = Instant.now();
        String assetId = "vis-" + UUID.randomUUID();
        AiVisualAsset asset = repository.save(new AiVisualAsset(
            null,
            assetId,
            projectId,
            null,
            file.getOriginalFilename() == null ? "visual-asset" : file.getOriginalFilename(),
            contentType,
            sha256(bytes),
            "db:ai_visual_assets/" + assetId,
            bytes.length,
            dimensions.width(),
            dimensions.height(),
            Base64.getEncoder().encodeToString(bytes),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.ai.security.AiActorResolver.currentOrSystemName(),
            now,
            now
        ));
        return new CreatedVisualAsset(asset.getId(), asset.getAssetId());
    }

    public void linkSourceDocument(String assetId, Long sourceDocumentPk) {
        if (assetId == null || assetId.isBlank() || sourceDocumentPk == null) {
            return;
        }
        jdbcTemplate.update(
            "update ai_visual_assets set source_document_id = ?, updated_at = ? where asset_id = ?",
            sourceDocumentPk,
            java.sql.Timestamp.from(Instant.now()),
            assetId
        );
    }

    public String assetIdForPk(Long assetPk) {
        if (assetPk == null) {
            return null;
        }
        List<String> assetIds = jdbcTemplate.queryForList(
            "select asset_id from ai_visual_assets where id = ?",
            String.class,
            assetPk
        );
        return assetIds.isEmpty() ? null : assetIds.getFirst();
    }

    AiVisualAssetResponse getAsset(String assetId) {
        AiVisualAsset asset = repository.findByAssetId(assetId).orElseThrow(
            com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization::notFound);
        // T14-R5 轮 2：资产读取同样项目隔离——不存在与无权同形 404（原先可跨项目读且 400 可区分）。
        try {
            projectAccessService.requireAccess(asset.getProjectId());
        } catch (org.springframework.web.server.ResponseStatusException denied) {
            throw com.haitlify.aitest.platform.ai.security.AiInvocationAuthorization.notFound();
        }
        return toResponse(asset);
    }

    private VisualAssetSettings loadSettings() {
        List<VisualAssetSettings> settings = jdbcTemplate.query("""
            select max_image_bytes, allowed_image_types_json
            from ai_system_configs
            where config_key = 'default'
            """, (rs, rowNum) -> new VisualAssetSettings(
            Math.max(rs.getLong("max_image_bytes"), 1),
            parseAllowedImageTypes(rs.getString("allowed_image_types_json"))
        ));
        return settings.isEmpty()
            ? new VisualAssetSettings(DEFAULT_MAX_IMAGE_BYTES, DEFAULT_ALLOWED_IMAGE_TYPES)
            : settings.getFirst();
    }

    private List<String> parseAllowedImageTypes(String json) {
        if (json == null || json.isBlank()) {
            return DEFAULT_ALLOWED_IMAGE_TYPES;
        }
        try {
            return objectMapper.readValue(
                json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class)
            );
        } catch (JacksonException exception) {
            return DEFAULT_ALLOWED_IMAGE_TYPES;
        }
    }

    private ImageDimensions dimensions(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new IllegalArgumentException("Unsupported visual asset image content");
            }
            return new ImageDimensions(image.getWidth(), image.getHeight());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to read visual asset dimensions", exception);
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Unable to read visual asset bytes", exception);
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash visual asset", exception);
        }
    }

    private AiVisualAssetResponse toResponse(AiVisualAsset asset) {
        return new AiVisualAssetResponse(
            asset.getAssetId(),
            asset.getProjectId(),
            externalSourceDocumentId(asset.getSourceDocumentId()),
            asset.getFileName(),
            asset.getContentType(),
            asset.getContentHash(),
            asset.getStorageLocation(),
            asset.getByteSize(),
            asset.getWidth(),
            asset.getHeight(),
            asset.getActorId(),
            asset.getActorName(),
            asset.getCreatedAt() == null ? null : asset.getCreatedAt().toString(),
            asset.getUpdatedAt() == null ? null : asset.getUpdatedAt().toString()
        );
    }

    private String externalSourceDocumentId(Long sourceDocumentPk) {
        if (sourceDocumentPk == null) {
            return null;
        }
        List<String> documentIds = jdbcTemplate.queryForList(
            "select document_id from ai_source_documents where id = ?",
            String.class,
            sourceDocumentPk
        );
        return documentIds.isEmpty() ? null : documentIds.getFirst();
    }
}

record AiVisualAssetResponse(
    String assetId,
    Long projectId,
    String sourceDocumentId,
    String fileName,
    String contentType,
    String contentHash,
    String storageLocation,
    long byteSize,
    int width,
    int height,
    String actorId,
    String actorName,
    String createdAt,
    String updatedAt
) {
}

record VisualAssetSettings(
    long maxImageBytes,
    List<String> allowedImageTypes
) {
}

record ImageDimensions(
    int width,
    int height
) {
}
