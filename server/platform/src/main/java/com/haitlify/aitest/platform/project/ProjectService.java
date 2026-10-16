package com.haitlify.aitest.platform.project;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;
import org.springframework.stereotype.Service;

@Service
@Profile("!jira-readonly")
class ProjectService {

    private final ProjectRepository projectRepository;

    ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    ProjectResponse create(CreateProjectRequest request) {
        String code = request.code().trim();
        Project existing = projectRepository.findByCode(code).orElse(null);
        if (existing != null) {
            return ProjectResponse.from(existing);
        }

        Instant now = Instant.now();
        Project project = new Project(
            null,
            code,
            request.name().trim(),
            request.description() == null ? "" : request.description().trim(),
            true,
            now,
            now
        );

        try {
            return ProjectResponse.from(projectRepository.save(project));
        } catch (DbActionExecutionException error) {
            if (error.getCause() instanceof DuplicateKeyException) {
                return projectRepository.findByCode(code)
                    .map(ProjectResponse::from)
                    .orElseThrow(() -> error);
            }
            throw error;
        }
    }

    ProjectListResponse list() {
        List<ProjectResponse> items = projectRepository.findAll().stream()
            .sorted(Comparator.comparing(Project::getId))
            .map(ProjectResponse::from)
            .toList();

        return new ProjectListResponse(items, items.size());
    }
}
