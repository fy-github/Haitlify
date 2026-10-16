package com.haitlify.aitest.platform.ai.config;

import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

interface AiSystemConfigRepository extends CrudRepository<AiSystemConfig, Long> {

    Optional<AiSystemConfig> findByConfigKey(String configKey);
}
