package com.haitlify.aitest.platform.ai.visual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * T-R5 轮 2 回归（复核发现：assetId 面）：视觉资产读取必须项目隔离且与「不存在」同形 404——
 * 原先 editor2 可读项目1 资产（200+全量数据）、猜测 id 返回 400 Unknown，二者可区分。
 */
class AiVisualAssetIsolationRound2Test {

    private JdbcTemplate jdbc;
    private AiVisualAssetService service;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
            "jdbc:h2:mem:tr5-asset-isolation;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
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

        AiVisualAssetRepository repository = mock(AiVisualAssetRepository.class);
        AiVisualAsset assetP1 = mock(AiVisualAsset.class);
        when(assetP1.getProjectId()).thenReturn(1L);
        when(assetP1.getAssetId()).thenReturn("vis-p1");
        when(repository.findByAssetId("vis-p1")).thenReturn(Optional.of(assetP1));
        when(repository.findByAssetId("vis-guess")).thenReturn(Optional.empty());
        service = new AiVisualAssetService(repository, jdbc, new ObjectMapper(),
            new ProjectAccessService(jdbc, true));
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
    void otherProjectsAssetAndAGuessedIdAreIndistinguishable404s() {
        authenticate("editor2");
        String forbidden = messageOf(() -> service.getAsset("vis-p1"));
        String missing = messageOf(() -> service.getAsset("vis-guess"));
        assertThat(forbidden).isEqualTo("404 not_found");
        assertThat(missing).isEqualTo(forbidden);
    }

    @Test
    void crossProjectUploadIsRefusedBeforeAnyWrite() {
        authenticate("editor2");
        var repository = mock(AiVisualAssetRepository.class);
        var service = new AiVisualAssetService(repository, jdbc, new ObjectMapper(),
            new ProjectAccessService(jdbc, true));
        String message = messageOf(() -> service.upload(1L, null));
        assertThat(message).isEqualTo("404 not_found");
        // 不得「先落库再鉴权」：任何仓储写入都不允许发生。
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void theOwnerStillReadsTheAsset() {
        authenticate("editor1");
        try {
            service.getAsset("vis-p1");
        } catch (RuntimeException failure) {
            // 读取继续走到响应装配；mock 字段不全允许装配异常，但不得是 404 not_found。
            assertThat(failure).isNotInstanceOf(ResponseStatusException.class);
        }
    }
}
