package com.haitlify.aitest.platform.ai.visual;

import java.util.Optional;

import org.springframework.data.repository.ListCrudRepository;

interface AiVisualAssetRepository extends ListCrudRepository<AiVisualAsset, Long> {

    Optional<AiVisualAsset> findByAssetId(String assetId);
}
