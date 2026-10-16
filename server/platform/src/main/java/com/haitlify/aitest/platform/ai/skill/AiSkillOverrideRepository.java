package com.haitlify.aitest.platform.ai.skill;

import java.util.Optional;

import org.springframework.data.repository.ListCrudRepository;

interface AiSkillOverrideRepository extends ListCrudRepository<AiSkillOverride, Long> {

    Optional<AiSkillOverride> findByBaseSkillIdAndProjectIdAndScope(String baseSkillId, Long projectId, String scope);
}
