package com.haitlify.aitest.platform.ai.figma;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.ListCrudRepository;

interface AiFigmaNodeRepository extends ListCrudRepository<AiFigmaNode, Long> {

    Optional<AiFigmaNode> findByFigmaNodeId(String figmaNodeId);

    List<AiFigmaNode> findByFigmaFilePkOrderByIdAsc(Long figmaFilePk);

    Optional<AiFigmaNode> findByFigmaFilePkAndNodeId(Long figmaFilePk, String nodeId);

    List<AiFigmaNode> findByFigmaFilePkAndParentNodeId(Long figmaFilePk, String parentNodeId);
}
