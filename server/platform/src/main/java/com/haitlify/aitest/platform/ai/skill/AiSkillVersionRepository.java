package com.haitlify.aitest.platform.ai.skill;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.ListCrudRepository;

interface AiSkillVersionRepository extends ListCrudRepository<AiSkillVersion, Long> {

    List<AiSkillVersion> findBySkillIdOrderByVersionAsc(String skillId);

    Optional<AiSkillVersion> findBySkillIdAndVersion(String skillId, int version);
}
