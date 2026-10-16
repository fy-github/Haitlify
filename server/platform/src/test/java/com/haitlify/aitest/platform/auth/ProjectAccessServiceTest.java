package com.haitlify.aitest.platform.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

class ProjectAccessServiceTest {

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void allowsEnabledMemberOfActiveProject() {
        JdbcTemplate jdbc = newStore("member");
        jdbc.update("insert into project_members(project_id, username, created_at) values (1, 'editor', ?)", Timestamp.from(Instant.now()));
        authenticate("editor", "EDITOR");

        assertThatCode(() -> new ProjectAccessService(jdbc).requireAccess(1L)).doesNotThrowAnyException();
    }

    @Test
    void rejectsAuthenticatedNonMember() {
        JdbcTemplate jdbc = newStore("non-member");
        authenticate("editor", "EDITOR");

        assertThatThrownBy(() -> new ProjectAccessService(jdbc).requireAccess(1L))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403 FORBIDDEN");
    }

    @Test
    void adminHasGlobalProjectAccess() {
        JdbcTemplate jdbc = newStore("admin");
        authenticate("admin", "ADMIN");

        assertThatCode(() -> new ProjectAccessService(jdbc).requireAccess(1L)).doesNotThrowAnyException();
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            username, "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    private JdbcTemplate newStore(String name) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:project-access-" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table projects (id bigint primary key, active boolean not null)");
        jdbc.execute("create table platform_users (username varchar(64) primary key, role varchar(32) not null, enabled boolean not null)");
        jdbc.execute("create table project_members (id bigint auto_increment primary key, project_id bigint not null, username varchar(64) not null, created_at timestamp not null)");
        jdbc.update("insert into projects(id, active) values (1, true)");
        jdbc.update("insert into platform_users(username, role, enabled) values ('editor', 'EDITOR', true), ('admin', 'ADMIN', true)");
        return jdbc;
    }
}
