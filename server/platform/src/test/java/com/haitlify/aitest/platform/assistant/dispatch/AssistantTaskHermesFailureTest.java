package com.haitlify.aitest.platform.assistant.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import com.haitlify.aitest.platform.assistant.compile.AssistantScriptCompiler;
import com.haitlify.aitest.platform.assistant.draft.AssistantDraftService;
import com.haitlify.aitest.platform.assistant.hermes.HermesGateway;
import com.haitlify.aitest.platform.assistant.ledger.AssistantLedgerRepository;

/**
 * P0-4 回归用例：Hermes 在受理之后失败时，operation 必须冻结为 UNKNOWN（而不是永久 PENDING），
 * 且请求轴同步冻结、留一条 UNKNOWN_RAISED 对账事件。
 */
@SpringBootTest(properties = {
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "app.assistant.hermes.enabled=true"
})
@ActiveProfiles("test")
class AssistantTaskHermesFailureTest {

    private static String rejectedReceipt;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "{\"status\":\"failed\",\"accepted\":true}",
        "{\"status\":\"streaming\",\"accepted\":false}",
        "{\"accepted\":\"true\"}"
    })
    void rejectedReceiptFreezesBothAxesWithoutRunnerDispatch(String raw) {
        rejectedReceipt = raw;
        try {
            hermesFailureFreezesBothAxes();
        } finally {
            rejectedReceipt = null;
        }
    }

    @TestConfiguration
    static class FailingGateway {
        @Bean
        @Primary
        HermesGateway failingHermesGateway() {
            return new HermesGateway() {
                @Override
                public boolean available() {
                    return true;
                }

                @Override
                public Session createSession(String cwd) {
                    if (rejectedReceipt != null) {
                        return new Session("live-1", "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}");
                    }
                    throw new IllegalStateException("boom: gateway died");
                }

                @Override
                public Turn submitPrompt(String sessionId, String prompt) {
                    if (rejectedReceipt != null) {
                        return new Turn(null, rejectedReceipt);
                    }
                    throw new IllegalStateException("boom: gateway died");
                }

                @Override
                public boolean interrupt(String sessionId) {
                    return false;
                }
            };
        }
    }

    @Autowired
    private AssistantTaskService tasks;

    @Autowired
    private AssistantDraftService drafts;

    @Autowired
    private AssistantLedgerRepository repository;

    @Test
    void hermesFailureFreezesBothAxes() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String requestId = UUID.randomUUID().toString();
        AssistantScriptCompiler.CaseSpec spec = new AssistantScriptCompiler.CaseSpec("case-hfail", "Hermes 失败夹具",
            "http://127.0.0.1:19399/f.html",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "/f.html", null),
                new AssistantScriptCompiler.Step("s2", "assertVisible", "#summary", null)));
        String draftId = drafts.confirm(drafts.create(new AssistantDraftService.CreateDraftCommand(
            tenant, 1L, 7L, "hermes 失败回归", "夹具", spec,
            "web.case.execute", "project:1/case-hfail", "rev-1", null)).draftId(), tenant, 7L).draftId();

        assertThatThrownBy(() -> tasks.run(new AssistantTaskService.TaskRequest(tenant, 7L, 1L, requestId, draftId,
            "web.case.execute", "project:1/case-hfail", "rev-1", spec, Map.of())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Hermes");

        String operationId = repository.findRequest(tenant, requestId).orElseThrow().operationId();
        assertThat(repository.findOperation(operationId).orElseThrow().executionState()).isEqualTo("UNKNOWN");
        assertThat(repository.findRequest(tenant, requestId).orElseThrow().admissionState()).isEqualTo("UNKNOWN");
        assertThat(repository.countReconciliation(operationId, "UNKNOWN_RAISED")).isEqualTo(1);
        assertThat(repository.listAttempts(operationId)).isEmpty();
        assertThat(repository.listResources(operationId)).isEmpty();

        // 同 requestId 重放：看到 UNKNOWN，而不是假装还在 PENDING。
        AssistantTaskService.TaskResult replay = tasks.run(new AssistantTaskService.TaskRequest(tenant, 7L, 1L,
            requestId, draftId, "web.case.execute", "project:1/case-hfail", "rev-1", spec, Map.of()));
        assertThat(replay.created()).isFalse();
        assertThat(replay.executionState()).isEqualTo("UNKNOWN");
    }
}
