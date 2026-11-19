package com.haitlify.aitest.platform.project;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("!jira-readonly")
class ProjectService {

    private final ProjectRepository projectRepository;
    private final JdbcTemplate jdbcTemplate;
    private final boolean securityEnabled;
    private final com.haitlify.aitest.platform.auth.ProjectAccessService projectAccess;

    ProjectService(ProjectRepository projectRepository, JdbcTemplate jdbcTemplate,
                   com.haitlify.aitest.platform.auth.ProjectAccessService projectAccess,
                   @Value("${app.security.enabled:true}") boolean securityEnabled) {
        this.projectRepository = projectRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.projectAccess = projectAccess;
        this.securityEnabled = securityEnabled;
    }

    /**
     * 项目注册表也要按租户隔离：非 ADMIN 只能看到自己所属租户的项目（可以属于多个租户）。
     * 之前 GET /api/projects 会把所有租户的项目连 tenantId 一起返回，等于泄露别人的租户与项目清单。
     */
    private java.util.List<Long> currentTenantIds() {
        if (!securityEnabled || projectAccess.isAdmin()) {
            return null;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
            || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        // 多租户成员必须看到全部所属租户，不能只取第一条（复核 P2-D）。
        return jdbcTemplate.queryForList(
            "select tenant_id from tenant_members where username = ? order by tenant_id",
            Long.class, authentication.getName());
    }

    /** 创建项目时必须落在「自己是成员」的租户里；ADMIN 例外（但仍要求租户存在）。 */
    private void requireTenantMembership(Long tenantId) {
        if (!securityEnabled || projectAccess.isAdmin()) {
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication == null ? null : authentication.getName();
        Integer member = jdbcTemplate.queryForObject(
            "select count(*) from tenant_members where tenant_id = ? and username = ?",
            Integer.class, tenantId, username);
        if (member == null || member == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权在该租户下创建项目：" + tenantId);
        }
    }

    private Long resolveTenantId(Long requested) {
        if (requested != null) {
            Integer exists = jdbcTemplate.queryForObject(
                "select count(*) from tenants where id = ? and active = true", Integer.class, requested);
            if (exists == null || exists == 0) {
                throw new IllegalArgumentException("租户不存在或已停用：" + requested);
            }
            return requested;
        }
        return jdbcTemplate.query(
            "select id from tenants where code = 'default'",
            (rs, rowNum) -> rs.getLong("id")
        ).stream().findFirst().orElse(null);
    }

    ProjectResponse create(CreateProjectRequest request) {
        String code = request.code().trim();
        Long tenantId = resolveTenantId(request.tenantId());
        // 不传 tenantId 会落到 default：同样必须是 default 的成员，否则会建出自己看不到的项目（复核 P2-C）。
        if (tenantId != null) {
            requireTenantMembership(tenantId);
        }
        Project existing = projectRepository.findByCode(code).orElse(null);
        if (existing != null) {
            // 只在同租户内回显：否则 A 租户可以用 code 抢到 B 租户的项目行。
            if (java.util.Objects.equals(existing.getTenantId(), tenantId)) {
                return ProjectResponse.from(existing);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "项目 code 已被其它租户占用：" + code);
        }

        Instant now = Instant.now();
        Project project = new Project(
            null,
            code,
            request.name().trim(),
            request.description() == null ? "" : request.description().trim(),
            true,
            tenantId,
            now,
            now
        );

        try {
            return ProjectResponse.from(projectRepository.save(project));
        } catch (DbActionExecutionException | DuplicateKeyException error) {
            // 实测 Spring Data JDBC 在唯一键冲突时直接抛 DuplicateKeyException（不是包一层 DbAction），
            // 两个都接住；并发抢同一 code 时只有同租户才回显，跨租户按冲突处理，绝不泄露别的租户项目。
            return projectRepository.findByCode(code)
                .filter(existingInSameTenant -> java.util.Objects.equals(existingInSameTenant.getTenantId(), tenantId))
                .map(ProjectResponse::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                    "项目 code 已被其它租户占用：" + code));
        }
    }

    ProjectListResponse list() {
        List<Long> tenantIds = currentTenantIds();
        List<Project> visible;
        if (tenantIds == null) {
            visible = projectRepository.findAll();
        } else if (tenantIds.isEmpty()) {
            // 不属于任何租户 ⇒ 空列表（不是 403：VIEWER 读列表本身是允许的）。
            visible = List.of();
        } else {
            String placeholders = String.join(",", java.util.Collections.nCopies(tenantIds.size(), "?"));
            visible = jdbcTemplate.query(
                "select * from projects where tenant_id in (" + placeholders + ") order by id",
                (rs, rowNum) -> projectRepository.findById(rs.getLong("id")).orElse(null),
                tenantIds.toArray());
            visible = visible.stream().filter(java.util.Objects::nonNull).toList();
        }
        List<ProjectResponse> items = visible.stream()
            .sorted(Comparator.comparing(Project::getId))
            .map(ProjectResponse::from)
            .toList();

        return new ProjectListResponse(items, items.size());
    }
}
