package com.haitlify.aitest.platform.ai.runtime.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AiArtifactGateAuditServiceTest {
    @Test
    void recordsPendingGateDecisionWithRevisionAndEvidence() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiArtifactGateAuditService service = new AiArtifactGateAuditService(jdbc);

        var result = service.record(new AiArtifactGateAuditService.GateRecord(
            "artifact-1", "run-1", 2L, "MODEL_PROPOSED", "PENDING_HUMAN_CONFIRMATION",
            1, "", "idem-1", List.of("evidence-1"), List.of(), "system-dev"
        ));

        assertThat(result.gateId()).startsWith("gate-");
        assertThat(result.decision()).isEqualTo("PENDING_HUMAN_CONFIRMATION");
        verify(jdbc).update(any(String.class), eq(result.gateId()), eq("artifact-1"), eq("run-1"), eq(2L), any(), any(), any(), eq(1), eq(""), eq("idem-1"), any(), any(), eq("system-dev"), any());
    }

    @Test
    void persistsBlockedDecisionForAuditEvenWhenIdentityValidationFails() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AiArtifactGateAuditService service = new AiArtifactGateAuditService(jdbc);

        var result = service.record(new AiArtifactGateAuditService.GateRecord(
            "", "run-1", 2L, "MODEL_PROPOSED", "PENDING_HUMAN_CONFIRMATION",
            0, "", "", List.of(), List.of(), "system-dev"
        ));

        assertThat(result.decision()).isEqualTo("BLOCKED");
        verify(jdbc).update(any(String.class), eq(result.gateId()), eq(""), eq("run-1"), eq(2L), eq("MODEL_PROPOSED"), eq("PENDING_HUMAN_CONFIRMATION"), eq("BLOCKED"), eq(0), eq(""), eq(""), any(), any(), eq("system-dev"), any());
    }
}