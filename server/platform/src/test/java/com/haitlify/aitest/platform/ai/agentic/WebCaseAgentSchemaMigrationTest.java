package com.haitlify.aitest.platform.ai.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.haitlify.aitest.platform.ai.draft.AgentWebCaseGenerationFacade;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class WebCaseAgentSchemaMigrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    WebCaseAgentRunService runService;

    @Test
    void createsAgentPersistenceTablesAndRequiredKeys() {
        Set<String> tables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables where lower(table_schema) = 'public'",
                String.class
            )
            .stream()
            .map(String::toLowerCase)
            .collect(Collectors.toSet());

        Set<String> expected = Set.of(
            "ai_agent_rule_versions",
            "ai_web_case_agent_runs",
            "ai_web_case_agent_events",
            "ai_web_case_agent_artifacts",
            "ai_locator_knowledge_entries",
            "ai_locator_knowledge_history",
            "ai_agent_memories",
            "ai_agent_memory_audit"
        );
        expected.forEach(table -> assertTrue(tables.contains(table), "missing table " + table));

        assertUniqueConstraint("ai_agent_rule_versions", "uk_ai_agent_rule_version_scope");
        assertUniqueConstraint("ai_web_case_agent_runs", "uk_ai_web_case_agent_run_id");
        assertUniqueConstraint("ai_web_case_agent_events", "uk_ai_web_case_agent_event_sequence");
        assertUniqueConstraint("ai_web_case_agent_artifacts", "uk_ai_web_case_agent_artifact_id");

        Integer detailsColumn = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.columns where lower(table_schema) = 'public' and lower(table_name) = ? and lower(column_name) = ?",
            Integer.class,
            "ai_web_case_agent_events",
            "details_json"
        );
        assertTrue(detailsColumn != null && detailsColumn > 0, "missing details_json column");
    }

    private void assertUniqueConstraint(String tableName, String constraintName) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from information_schema.table_constraints "
                + "where lower(table_schema) = 'public' and lower(table_name) = ? "
                + "and lower(constraint_name) = ? and constraint_type = 'UNIQUE'",
            Integer.class,
            tableName,
            constraintName
        );
        assertTrue(count != null && count > 0, "missing unique constraint " + constraintName);
    }

    /**
     * T-Review-3 fail-closed invariant: an artifact may never be marked accepted while its acceptance
     * state is not ACCEPTED. Application code only sets both fields together, but nothing structural
     * stopped any writer (or a future code path) from flipping `accepted` alone, which is exactly what
     * the pre-gate writer did with `result_origin`; the schema enforces it now.
     */
    @Test
    void schemaRejectsAcceptedArtifactWhoseAcceptanceStateIsNotAccepted() {
        Timestamp now = Timestamp.from(Instant.parse("2026-07-03T10:00:00Z"));

        assertThatThrownBy(() -> insertArtifact("schema-neg-accepted", true, "PENDING_HUMAN_CONFIRMATION", now))
            .as("accepted=true with a non-ACCEPTED state must be rejected")
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertArtifact("schema-neg-blocked", true, "BLOCKED", now))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        // A writer that never states an origin must not inherit a model-generation claim.
        String originDefault = jdbcTemplate.queryForObject(
            "select column_default from information_schema.columns where lower(table_schema) = 'public' "
                + "and lower(table_name) = 'ai_web_case_agent_artifacts' and lower(column_name) = 'result_origin'",
            String.class);
        assertThat(originDefault).isNotNull();
        assertThat(originDefault.replace("'", "")).isEqualTo("BLOCKED");

        insertArtifact("schema-ok-pending", false, "PENDING_HUMAN_CONFIRMATION", now);
        insertArtifact("schema-ok-accepted", true, "ACCEPTED", now);

        assertEquals(1, jdbcTemplate.queryForObject(
            "select count(*) from ai_web_case_agent_artifacts where artifact_id = 'schema-ok-accepted' and accepted = true and acceptance_state = 'ACCEPTED'",
            Integer.class));
    }

    private void insertArtifact(String artifactId, boolean accepted, String acceptanceState, Timestamp now) {
        jdbcTemplate.update("""
            insert into ai_web_case_agent_artifacts(
              artifact_id, run_id, point_id, artifact_type, candidate_json, evidence_ids_json,
              validation_errors_json, accepted, repair_round, revision, parent_artifact_id,
              idempotency_key, result_origin, acceptance_state, created_at, updated_at
            ) values (?, 'schema-run', 'point-1', 'web_case', '{}', '[]', '[]', ?, 0, 1, '', ?, 'MODEL_GENERATED', ?, ?, ?)
            """, artifactId, accepted, artifactId, acceptanceState, now, now);
    }

    /**
     * The artifact-level origin vocabulary is closed: MODEL_PROPOSED is a field-level label and must not
     * be storable as an artifact origin (it was the pre-V32 default and leaked into shipped rows).
     */
    @Test
    void schemaRejectsArtifactOriginOutsideTheVocabulary() {
        Timestamp now = Timestamp.from(Instant.parse("2026-07-03T12:00:00Z"));

        assertThatThrownBy(() -> insertArtifactWithOrigin("schema-neg-proposed", "MODEL_PROPOSED", now))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertArtifactWithOrigin("schema-neg-random", "SOMETHING_ELSE", now))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        for (String origin : List.of("MODEL_GENERATED", "RULE_EXTRACTED", "TOOL_OBSERVED", "HUMAN_EDITED", "BLOCKED")) {
            insertArtifactWithOrigin("schema-vocab-" + origin.toLowerCase(), origin, now);
        }
        assertEquals(5, jdbcTemplate.queryForObject(
            "select count(*) from ai_web_case_agent_artifacts where artifact_id like 'schema-vocab-%'", Integer.class));
    }

    private void insertArtifactWithOrigin(String artifactId, String origin, Timestamp now) {
        jdbcTemplate.update("""
            insert into ai_web_case_agent_artifacts(
              artifact_id, run_id, point_id, artifact_type, candidate_json, evidence_ids_json,
              validation_errors_json, accepted, repair_round, revision, parent_artifact_id,
              idempotency_key, result_origin, acceptance_state, created_at, updated_at
            ) values (?, 'schema-run', 'point-1', 'web_case', '{}', '[]', '[]', false, 0, 1, '', ?, ?, 'PENDING_HUMAN_CONFIRMATION', ?, ?)
            """, artifactId, artifactId, origin, now, now);
    }

    /**
     * Web plan entry against the real schema: the artifact records MODEL_GENERATED only when the model
     * call left an auditable provider request id; the identical write without that evidence is BLOCKED,
     * and the Gate audit must agree with the row it audits.
     */
    @Test
    void planArtifactsCarryModelOriginOnlyWithProviderEvidence() {
        Instant now = Instant.parse("2026-07-03T11:00:00Z");
        insertProjectAndPlanRun("plan-evidence-run", now);
        insertProjectAndPlanRun("plan-no-evidence-run", now);

        runService.saveNaturalPlanArtifacts("plan-evidence-run", List.of(naturalPlan("point-evidence")), "plan-req-1");
        runService.saveNaturalPlanArtifacts("plan-no-evidence-run", List.of(naturalPlan("point-no-evidence")), "");

        assertEquals("MODEL_GENERATED", artifactColumn("plan-evidence-run", "point-evidence", "result_origin"));
        assertEquals("PENDING_HUMAN_CONFIRMATION", artifactColumn("plan-evidence-run", "point-evidence", "acceptance_state"));
        assertEquals("BLOCKED", artifactColumn("plan-no-evidence-run", "point-no-evidence", "result_origin"));
        assertEquals("BLOCKED", artifactColumn("plan-no-evidence-run", "point-no-evidence", "acceptance_state"));

        assertEquals(1, jdbcTemplate.queryForObject(
            "select count(*) from ai_artifact_gate_audits a join ai_web_case_agent_artifacts w on a.artifact_id = w.artifact_id "
                + "where w.run_id = 'plan-evidence-run' and a.result_origin = w.result_origin and a.acceptance_state = w.acceptance_state",
            Integer.class));
        assertEquals(1, jdbcTemplate.queryForObject(
            "select count(*) from ai_artifact_gate_audits a join ai_web_case_agent_artifacts w on a.artifact_id = w.artifact_id "
                + "where w.run_id = 'plan-no-evidence-run' and a.result_origin = 'BLOCKED' and a.acceptance_state = 'BLOCKED'",
            Integer.class));
        assertEquals(0, jdbcTemplate.queryForObject(
            "select count(*) from ai_artifact_gate_audits where result_origin = 'MODEL_PROPOSED'", Integer.class));
    }

    private AgentWebCaseGenerationFacade.NaturalWebCasePlan naturalPlan(String pointId) {
        return new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
            pointId, "标题", "模块", "页面", "/x", List.of("步骤"), "预期", "目标");
    }

    private String artifactColumn(String runId, String pointId, String column) {
        return jdbcTemplate.queryForObject(
            "select " + column + " from ai_web_case_agent_artifacts where run_id = ? and point_id = ?",
            String.class, runId, pointId);
    }

    private void insertProjectAndPlanRun(String runId, Instant now) {
        String code = "SCHEMA_" + runId.replace('-', '_').toUpperCase();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            code, "Schema Fixture " + runId, "V32 fixture", true, Timestamp.from(now), Timestamp.from(now));
        long projectId = jdbcTemplate.queryForObject("select id from projects where code = ?", Long.class, code);
        jdbcTemplate.update("""
            insert into ai_web_case_agent_runs(
              run_id, project_id, analysis_run_id, status, current_stage, requested_point_ids_json,
              completed_point_ids_json, blocked_point_ids_json, rules_snapshot_json, input_summary_json,
              output_summary_json, last_heartbeat_at, cancel_requested, error_summary, mode, plan_run_id,
              created_at, updated_at, started_at, finished_at
            ) values (?, ?, '', 'waiting_review', 'delivery', '[]', '[]', '[]', '{}', '{}', '{}', ?, false, '', 'PLAN', '', ?, ?, ?, null)
            """, runId, projectId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }
}
