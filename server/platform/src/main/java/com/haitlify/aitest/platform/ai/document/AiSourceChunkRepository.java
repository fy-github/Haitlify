package com.haitlify.aitest.platform.ai.document;

import java.util.List;

import org.springframework.data.repository.ListCrudRepository;

interface AiSourceChunkRepository extends ListCrudRepository<AiSourceChunk, Long> {

    List<AiSourceChunk> findByDocumentIdOrderByIdAsc(Long documentId);
}
