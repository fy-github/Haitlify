package com.haitlify.aitest.platform.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Authorizes access to an active project for the current platform user. */
@Service
public class ProjectAccessService {

    private final JdbcTemplate jdbcTemplate;
    private final boolean securityEnabled;

    @Autowired
    public ProjectAccessService(JdbcTemplate jdbcTemplate, @Value("${app.security.enabled:true}") boolean securityEnabled) {
        this.jdbcTemplate = jdbcTemplate;
        this.securityEnabled = securityEnabled;
    }

    public ProjectAccessService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, true);
    }

    public void requireAccess(Long projectId) {
        if (projectId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问项目");
        }
        if (!securityEnabled) {
            return;
        }
        Authentication authentication = currentAuthentication();
        String username = authentication.getName();
        String role = jdbcTemplate.query(
            "select role from platform_users where username = ? and enabled = true",
            (rs, rowNum) -> rs.getString("role"), username
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在或已停用"));
        Integer active = jdbcTemplate.queryForObject("select count(*) from projects where id = ? and active = true", Integer.class, projectId);
        if (active == null || active == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问项目");
        }
        if (!"ADMIN".equalsIgnoreCase(role)) {
            Integer member = jdbcTemplate.queryForObject(
                "select count(*) from project_members where project_id = ? and username = ?",
                Integer.class, projectId, username
            );
            if (member == null || member == 0) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问项目");
            }
        }
    }

    public boolean isAdmin() {
        if (!securityEnabled) return true;
        Authentication authentication = currentAuthentication();
        return jdbcTemplate.query(
            "select role from platform_users where username = ? and enabled = true",
            (rs, rowNum) -> rs.getString("role"), authentication.getName()
        ).stream().anyMatch("ADMIN"::equalsIgnoreCase);
    }

    private Authentication currentAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return authentication;
    }
}
