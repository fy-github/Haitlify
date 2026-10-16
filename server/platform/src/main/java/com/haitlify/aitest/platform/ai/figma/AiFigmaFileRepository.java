package com.haitlify.aitest.platform.ai.figma;

import java.util.Optional;

import org.springframework.data.repository.ListCrudRepository;

interface AiFigmaFileRepository extends ListCrudRepository<AiFigmaFile, Long> {

    Optional<AiFigmaFile> findByFigmaFileId(String figmaFileId);

    Optional<AiFigmaFile> findByProjectIdAndFileKeyAndSelectedNodeId(Long projectId, String fileKey, String selectedNodeId);
}
