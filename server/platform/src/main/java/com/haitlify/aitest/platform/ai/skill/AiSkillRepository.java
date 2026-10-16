package com.haitlify.aitest.platform.ai.skill;

import java.util.Optional;

import org.springframework.data.repository.ListCrudRepository;

interface AiSkillRepository extends ListCrudRepository<AiSkill, Long> {

    Optional<AiSkill> findBySkillId(String skillId);
}
