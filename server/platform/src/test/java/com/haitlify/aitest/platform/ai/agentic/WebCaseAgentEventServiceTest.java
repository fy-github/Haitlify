package com.haitlify.aitest.platform.ai.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;

class WebCaseAgentEventServiceTest {

    @Test
    void truncatesOversizedEventSummariesBeforeDatabaseInsert() {
        String normalized = WebCaseAgentEventService.normalizeSummary("错误详情".repeat(400));

        assertThat(normalized.codePointCount(0, normalized.length())).isEqualTo(1024);
        assertThat(normalized).endsWith("...");
    }

    @Test
    void keepsShortEventSummariesUnchanged() {
        assertThat(WebCaseAgentEventService.normalizeSummary("编译失败"))
            .isEqualTo("编译失败");
    }

    @Test
    void appendDetailedPersistsStructuredDetailsAndKeepsLegacyAppendCompatible() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ObjectMapper mapper = new ObjectMapper();
        WebCaseAgentEventService service = new WebCaseAgentEventService(jdbc, mapper);
        WebCaseAgentModels.WebCaseAgentEventResponse persisted = new WebCaseAgentModels.WebCaseAgentEventResponse(
            "evt-1", "run-1", 1L, "2026-08-28T00:00:00Z", WebCaseAgentModels.WebCaseAgentStage.EVIDENCE,
            WebCaseAgentModels.WebCaseAgentEventType.ACTION, "info", "第2/5轮 · 页面35 · 元素25 · 总分70 · 菜单搜索",
            "point-1", "browser-discovery", 10L, 1, List.<String>of(), null,
            JsonNodeFactory.instance.objectNode().put("round", 2).put("pageUrl", "https://example.test/logs")
        );
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), anyString())).thenReturn(1L);
        when(jdbc.query(anyString(), any(RowMapper.class), anyString(), any(Long.class))).thenReturn(List.of(persisted));

        var result = service.appendDetailed("run-1", WebCaseAgentModels.WebCaseAgentStage.EVIDENCE,
            WebCaseAgentModels.WebCaseAgentEventType.ACTION, "info", "第2/5轮 · 页面35 · 元素25 · 总分70 · 菜单搜索",
            "point-1", "browser-discovery", 10L, 1, List.<String>of(), null,
            JsonNodeFactory.instance.objectNode().put("round", 2).put("pageUrl", "https://example.test/logs"));

        assertThat(result.details().path("round").asInt()).isEqualTo(2);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(jdbc).update(anyString(), args.capture());
        assertThat(String.valueOf(args.getValue()[14])).contains("\"round\":2");
    }

    @Test
    void listReturnsAbsentDetailsForMissingMalformedOrNonObjectJson() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WebCaseAgentEventService service = new WebCaseAgentEventService(jdbc, new ObjectMapper());
        AtomicReference<String> detailJson = new AtomicReference<>("{bad-json");
        ArgumentCaptor<RowMapper<WebCaseAgentModels.WebCaseAgentEventResponse>> mapperCaptor = ArgumentCaptor.forClass(RowMapper.class);
        when(jdbc.query(anyString(), mapperCaptor.capture(), anyString(), any(Long.class))).thenAnswer(invocation -> {
            RowMapper<WebCaseAgentModels.WebCaseAgentEventResponse> mapper = mapperCaptor.getValue();
            ResultSet row = mock(ResultSet.class);
            when(row.getString("event_id")).thenReturn("evt-1");
            when(row.getString("run_id")).thenReturn("run-1");
            when(row.getLong("sequence_no")).thenReturn(1L);
            when(row.getTimestamp("occurred_at")).thenReturn(java.sql.Timestamp.from(java.time.Instant.now()));
            when(row.getString("stage")).thenReturn("EVIDENCE");
            when(row.getString("event_type")).thenReturn("ACTION");
            when(row.getString("severity")).thenReturn("info");
            when(row.getString("summary")).thenReturn("summary");
            when(row.getString("details_json")).thenAnswer(ignored -> detailJson.get());
            when(row.getString("knowledge_hit_ids_json")).thenReturn("[]");
            return List.of(mapper.mapRow(row, 0));
        });

        var result = service.list("run-1", 0);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).details()).isNull();

        detailJson.set("[]");
        var scalarResult = service.list("run-1", 0);
        assertThat(scalarResult.get(0).details()).isNull();
    }
}
