package com.haitlify.aitest.platform.caselibrary;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!jira-readonly")
@Transactional
class CaseLibraryService {

    private final InterfaceCaseRepository repository;
    private final ObjectMapper objectMapper;

    CaseLibraryService(InterfaceCaseRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    SaveInterfaceCasesResponse save(Long projectId, SaveInterfaceCasesRequest request) {
        int inserted = 0;
        int updated = 0;

        for (SaveInterfaceCaseRequest item : request.cases()) {
            InterfaceCaseRecord current = repository.findByProjectIdAndLibraryId(projectId, item.libraryId()).orElse(null);
            repository.save(toRecord(projectId, item, current));
            if (current == null) {
                inserted += 1;
            } else {
                updated += 1;
            }
        }

        int total = repository.findByProjectIdOrderByRunOrderAscSavedAtDesc(projectId).size();
        return new SaveInterfaceCasesResponse(inserted, updated, total);
    }

    InterfaceCaseListResponse list(Long projectId) {
        List<SavedInterfaceCaseResponse> items = repository.findByProjectIdOrderByRunOrderAscSavedAtDesc(projectId).stream()
            .map(this::toResponse)
            .toList();

        return new InterfaceCaseListResponse(items, items.size());
    }

    DeleteInterfaceCasesResponse delete(Long projectId, DeleteInterfaceCasesRequest request) {
        List<String> libraryIds = request == null || request.libraryIds() == null ? List.of() : request.libraryIds();
        if (libraryIds.isEmpty()) {
            int total = repository.findByProjectIdOrderByRunOrderAscSavedAtDesc(projectId).size();
            return new DeleteInterfaceCasesResponse(0, total);
        }

        List<InterfaceCaseRecord> deleting = repository.findByProjectIdOrderByRunOrderAscSavedAtDesc(projectId).stream()
            .filter(record -> libraryIds.contains(record.getLibraryId()))
            .toList();
        repository.deleteAll(deleting);

        int total = repository.findByProjectIdOrderByRunOrderAscSavedAtDesc(projectId).size();
        return new DeleteInterfaceCasesResponse(deleting.size(), total);
    }

    private InterfaceCaseRecord toRecord(Long projectId, SaveInterfaceCaseRequest item, InterfaceCaseRecord current) {
        Instant now = Instant.now();
        JsonNode row = item.row();
        JsonNode metadata = item.metadata();

        return new InterfaceCaseRecord(
            current == null ? null : current.getId(),
            projectId,
            item.libraryId(),
            text(row, "caseId", item.id()),
            text(row, "module", ""),
            text(row, "caseType", ""),
            text(row, "title", ""),
            text(metadata, "sourceFile", ""),
            text(metadata, "sourceOperationId", ""),
            integer(metadata, "runOrder", 0),
            stringify(row),
            stringify(metadata),
            item.savedAt() == null || item.savedAt().isBlank() ? now.toString() : item.savedAt(),
            current == null ? now : current.getCreatedAt(),
            now
        );
    }

    private SavedInterfaceCaseResponse toResponse(InterfaceCaseRecord record) {
        return new SavedInterfaceCaseResponse(
            record.getCaseId(),
            record.getLibraryId(),
            record.getSavedAt(),
            parse(record.getRowJson()),
            parse(record.getMetadataJson())
        );
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid case JSON", exception);
        }
    }

    private String stringify(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid case payload", exception);
        }
    }

    private String text(JsonNode node, String fieldName, String defaultValue) {
        JsonNode value = node == null ? null : node.get(fieldName);
        return value == null || value.isNull() ? defaultValue : value.asText(defaultValue);
    }

    private int integer(JsonNode node, String fieldName, int defaultValue) {
        JsonNode value = node == null ? null : node.get(fieldName);
        return value == null || value.isNull() ? defaultValue : value.asInt(defaultValue);
    }
}

record SaveInterfaceCasesRequest(
    List<SaveInterfaceCaseRequest> cases
) {
}

record SaveInterfaceCaseRequest(
    String id,
    String libraryId,
    String savedAt,
    JsonNode row,
    JsonNode metadata
) {
}

record SaveInterfaceCasesResponse(
    int inserted,
    int updated,
    int total
) {
}

record SavedInterfaceCaseResponse(
    String id,
    String libraryId,
    String savedAt,
    JsonNode row,
    JsonNode metadata
) {
}

record InterfaceCaseListResponse(
    List<SavedInterfaceCaseResponse> items,
    int total
) {
}

record DeleteInterfaceCasesRequest(
    List<String> libraryIds
) {
}

record DeleteInterfaceCasesResponse(
    int deleted,
    int total
) {
}
