package com.haitlify.aitest.platform.project;

import java.util.Optional;

import org.springframework.data.repository.ListCrudRepository;

interface ProjectRepository extends ListCrudRepository<Project, Long> {
    Optional<Project> findByCode(String code);
}
