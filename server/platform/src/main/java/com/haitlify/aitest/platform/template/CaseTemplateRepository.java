package com.haitlify.aitest.platform.template;

import java.util.Optional;

import org.springframework.data.repository.ListCrudRepository;

interface CaseTemplateRepository extends ListCrudRepository<CaseTemplateConfig, Long> {

    Optional<CaseTemplateConfig> findByProjectIdAndKind(Long projectId, String kind);
}
