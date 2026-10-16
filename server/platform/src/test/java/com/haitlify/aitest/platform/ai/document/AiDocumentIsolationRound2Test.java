package com.haitlify.aitest.platform.ai.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import com.haitlify.aitest.platform.auth.ProjectAccessService;

import tools.jackson.databind.ObjectMapper;

/**
 * T-R5 轮 2 回归（复核发现 1：document 面）：他项目真实文档与猜测 id 必须同形 404——
 * 不得用 403「无权访问项目」与「Unknown AI source document」区分对象存在。
 */
class AiDocumentIsolationRound2Test {

    private JdbcTemplate jdbc;
    private AiSourceDocumentService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:tr5-doc-isolation;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("create table if not exists projects (id bigint primary key, active boolean not null)");
        jdbc.execute("create table if not exists platform_users (username varchar(64) primary key,"
            + " role varchar(32) not null, enabled boolean not null)");
        jdbc.execute("create table if not exists project_members (id bigint auto_increment primary key,"
            + " project_id bigint not null, username varchar(64) not null, created_at timestamp not null)");
        jdbc.execute("delete from project_members");
        jdbc.execute("delete from platform_users");
        jdbc.execute("delete from projects");
        jdbc.update("insert into projects(id, active) values (1, true), (2, true)");
        jdbc.update("insert into platform_users(username, role, enabled) values"
            + " ('editor1', 'EDITOR', true), ('editor2', 'EDITOR', true)");
        jdbc.update("insert into project_members(project_id, username, created_at) values (1, 'editor1', ?)",
            Timestamp.from(Instant.now()));
        jdbc.update("insert into project_members(project_id, username, created_at) values (2, 'editor2', ?)",
            Timestamp.from(Instant.now()));

        AiSourceDocumentRepository repository = mock(AiSourceDocumentRepository.class);
        AiSourceDocument docP1 = mock(AiSourceDocument.class);
        when(docP1.getProjectId()).thenReturn(1L);
        when(repository.findByDocumentId("doc-p1")).thenReturn(Optional.of(docP1));
        when(repository.findByDocumentId("doc-guess")).thenReturn(Optional.empty());
        service = new AiSourceDocumentService(
            repository, null, null, null, new ObjectMapper(), new ProjectAccessService(jdbc, true));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String username) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            username, "n/a", List.of(new SimpleGrantedAuthority("ROLE_EDITOR"))));
    }

    private String messageOf(Runnable call) {
        try {
            call.run();
            return "NO-ERROR";
        } catch (ResponseStatusException failure) {
            return failure.getStatusCode().value() + " " + failure.getReason();
        } catch (RuntimeException failure) {
            return failure.getClass().getSimpleName() + ": " + failure.getMessage();
        }
    }

    @Test
    void otherProjectsDocumentAndAGuessedIdAreIndistinguishable() {
        authenticate("editor2");
        String forbidden = messageOf(() -> service.getDocument("doc-p1"));
        String missing = messageOf(() -> service.getDocument("doc-guess"));
        assertThat(forbidden).isEqualTo("404 not_found");
        assertThat(missing).isEqualTo(forbidden);
    }

    @Test
    void chunksFaceIsUniformToo() {
        authenticate("editor2");
        String forbidden = messageOf(() -> service.listChunks("doc-p1"));
        String missing = messageOf(() -> service.listChunks("doc-guess"));
        assertThat(forbidden).isEqualTo("404 not_found");
        assertThat(missing).isEqualTo(forbidden);
    }

    @Test
    void theOwnerStillReadsTheDocument() {
        authenticate("editor1");
        try {
            service.getDocument("doc-p1");
        } catch (RuntimeException failure) {
            // 读取继续走到响应装配；mock 字段不全允许 NPE/装配异常，但不应是 404 not_found。
            assertThat(failure).isNotInstanceOf(ResponseStatusException.class);
        }
    }
}
