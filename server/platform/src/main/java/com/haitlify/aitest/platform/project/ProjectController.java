package com.haitlify.aitest.platform.project;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/projects")
class ProjectController {

    private final ProjectService projectService;

    ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    ProjectListResponse list() {
        return projectService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProjectResponse create(@Valid @RequestBody CreateProjectRequest request) {
        return projectService.create(request);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
    java.util.Map<String, String> badRequest(IllegalArgumentException ex) {
        return java.util.Map.of("error", "invalid_request", "message", ex.getMessage());
    }
}

/** tenantId 为空时挂到当前部署的 default 租户；多租户部署应显式传。 */
record CreateProjectRequest(
    @NotBlank @Size(max = 64) String code,
    @NotBlank @Size(max = 128) String name,
    @Size(max = 1024) String description,
    Long tenantId
) {
}

record ProjectResponse(
    Long id,
    String code,
    String name,
    String description,
    boolean active,
    Long tenantId
) {
    static ProjectResponse from(Project project) {
        return new ProjectResponse(
            project.getId(),
            project.getCode(),
            project.getName(),
            project.getDescription(),
            project.isActive(),
            project.getTenantId()
        );
    }
}

record ProjectListResponse(
    List<ProjectResponse> items,
    int total
) {
}
