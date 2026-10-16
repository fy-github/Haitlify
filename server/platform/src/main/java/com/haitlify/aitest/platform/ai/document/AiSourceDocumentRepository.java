package com.haitlify.aitest.platform.ai.document;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.ListCrudRepository;

interface AiSourceDocumentRepository extends ListCrudRepository<AiSourceDocument, Long> {

    Optional<AiSourceDocument> findByDocumentId(String documentId);

    List<AiSourceDocument> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
