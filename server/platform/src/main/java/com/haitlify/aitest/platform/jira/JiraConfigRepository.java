package com.haitlify.aitest.platform.jira;

import java.util.Optional;

import org.springframework.data.repository.CrudRepository;

interface JiraConfigRepository extends CrudRepository<JiraConfig, Long> {

    Optional<JiraConfig> findByConfigKey(String configKey);
}
