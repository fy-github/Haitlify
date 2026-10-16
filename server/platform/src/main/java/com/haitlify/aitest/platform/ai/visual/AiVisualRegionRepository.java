package com.haitlify.aitest.platform.ai.visual;

import java.util.List;

import org.springframework.data.repository.ListCrudRepository;

interface AiVisualRegionRepository extends ListCrudRepository<AiVisualRegion, Long> {

    List<AiVisualRegion> findByAssetIdOrderByIdAsc(Long assetId);
}
