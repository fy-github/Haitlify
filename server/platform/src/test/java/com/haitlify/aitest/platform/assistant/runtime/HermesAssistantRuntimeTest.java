package com.haitlify.aitest.platform.assistant.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.haitlify.aitest.platform.assistant.hermes.HermesGateway;
import tools.jackson.databind.ObjectMapper;

class HermesAssistantRuntimeTest {
    @Test
    void submissionAcknowledgementIsNotBusinessCompletion() {
        ProbeGateway gateway = new ProbeGateway();
        var runtime = new HermesAssistantRuntime(gateway, new ObjectMapper());
        var receipt = runtime.submit("/state/workspace", "已确认任务");
        assertThat(receipt.sessionId()).isEqualTo("live-1");
        assertThat(receipt.storedSessionId()).isEqualTo("stored-1");
        assertThat(receipt.turnRef()).isNull();
        assertThat(receipt.state()).isEqualTo(HermesAssistantRuntime.State.SUBMITTED);
        assertThat(gateway.promptSession).isEqualTo("live-1");
        assertThat(gateway.promptCalls).isEqualTo(1);
    }

    @Test
    void unavailableRuntimeNeverCreatesASession() {
        var gateway = new ProbeGateway() {
            public boolean available() { return false; }
            public Session createSession(String cwd) { throw new AssertionError("must not create"); }
        };
        assertThatThrownBy(() -> new HermesAssistantRuntime(gateway, new ObjectMapper()).submit(null, "任务"))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("不可用");
        assertThat(gateway.promptCalls).isZero();
    }

    @Test
    void failedSubmissionIsNotRetriedOrConvertedToSuccess() {
        var gateway = new ProbeGateway() {
            public Turn submitPrompt(String sessionId, String prompt) {
                promptCalls++;
                throw new IllegalStateException("connection lost");
            }
        };
        assertThatThrownBy(() -> new HermesAssistantRuntime(gateway, new ObjectMapper()).submit(null, "任务"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(gateway.promptCalls).isEqualTo(1);
    }

    @Test
    void malformedOrRejectedReceiptsFailClosed() {
        for (String raw : java.util.List.of("{}", "null", "[]", "not-json",
                "{\"status\":\"failed\"}", "{\"error\":{\"code\":4001}}",
                "{\"result\":{\"error\":\"refused\",\"accepted\":true}}")) {
            var gateway = new ProbeGateway() {
                public Turn submitPrompt(String sessionId, String prompt) { return new Turn("x", raw); }
            };
            assertThatThrownBy(() -> new HermesAssistantRuntime(gateway, new ObjectMapper()).submit(null, "任务"))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void missingStoredIdStaysUnknownRatherThanBecomingLiveId() {
        var gateway = new ProbeGateway() {
            public Session createSession(String cwd) { return new Session("live-1", "{\"session_id\":\"live-1\"}"); }
        };
        assertThat(new HermesAssistantRuntime(gateway, new ObjectMapper()).submit(null, "任务").storedSessionId()).isNull();
    }

    @Test
    void invalidSessionReceiptPreventsPromptSubmission() {
        var gateway = new ProbeGateway() {
            public Session createSession(String cwd) { return new Session("live-1", "{\"session_id\":\"other\"}"); }
        };
        assertThatThrownBy(() -> new HermesAssistantRuntime(gateway, new ObjectMapper()).submit(null, "任务"))
            .isInstanceOf(IllegalStateException.class);
        assertThat(gateway.promptCalls).isZero();
    }

    @Test
    void emptyPromptIsRejectedBeforeGatewayAccess() {
        var gateway = new ProbeGateway() {
            public boolean available() { throw new AssertionError("must not access"); }
        };
        assertThatThrownBy(() -> new HermesAssistantRuntime(gateway, new ObjectMapper()).submit(null, " "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "{\"status\":\"failed\",\"accepted\":true}",
        "{\"status\":\"streaming\",\"accepted\":false}",
        "{\"accepted\":\"true\"}",
        "{\"status\":null,\"accepted\":true}",
        "{\"status\":42,\"accepted\":true}",
        "{\"status\":\"unknown\",\"accepted\":true}",
        "{\"status\":\"streaming\",\"accepted\":null}",
        "{\"status\":\"streaming\",\"accepted\":1}"
    })
    void contradictoryOrMistypedReceiptsAreRejected(String raw) {
        var gateway = new ProbeGateway() {
            public Turn submitPrompt(String sessionId, String prompt) { return new Turn(null, raw); }
        };
        assertThatThrownBy(() -> new HermesAssistantRuntime(gateway, new ObjectMapper()).submit(null, "任务"))
            .isInstanceOf(IllegalStateException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "{\"status\":\"streaming\"}", "{\"status\":\"accepted\"}", "{\"accepted\":true}",
        "{\"status\":\"streaming\",\"accepted\":true}",
        "{\"result\":{\"status\":\"accepted\",\"accepted\":true}}"
    })
    void consistentTypedReceiptsAreSubmitted(String raw) {
        var gateway = new ProbeGateway() {
            public Turn submitPrompt(String sessionId, String prompt) { return new Turn(null, raw); }
        };
        assertThat(new HermesAssistantRuntime(gateway, new ObjectMapper()).submit(null, "任务").state())
            .isEqualTo(HermesAssistantRuntime.State.SUBMITTED);
    }

    /**
     * 缺陷 B：pin 的 {@code PromptSubmitResult.status} 在忙会话上是 queued/steered/redirected
     * （{@code tui_gateway/contracts/prompt_voice.py:57-60}，{@code methods_prompt.py} 忙时返回），
     * 它们都是「已受理」，不是失败，也不是完成。
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        "{\"status\":\"queued\"}",
        "{\"status\":\"steered\"}",
        "{\"status\":\"redirected\"}",
        "{\"status\":\"queued\",\"accepted\":true}",
        "{\"result\":{\"status\":\"steered\",\"accepted\":true}}"
    })
    void busySessionReceiptsAreAcceptancesNotFailures(String raw) {
        var gateway = new ProbeGateway() {
            public Turn submitPrompt(String sessionId, String prompt) { return new Turn(null, raw); }
        };
        org.assertj.core.api.Assertions.assertThatCode(
            () -> new HermesAssistantRuntime(gateway, new ObjectMapper()).submit(null, "任务"))
            .doesNotThrowAnyException();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
        // 忙回执 + 明确的拒绝/错类型 accepted：矛盾，不是受理
        "{\"status\":\"queued\",\"accepted\":false}",
        "{\"status\":\"steered\",\"accepted\":\"true\"}",
        "{\"status\":\"redirected\",\"accepted\":0}",
        // pin 的 typed-stop-phrase 回执（status 缺失、voice_stopped=true）不是一次提交
        "{\"voice_stopped\":true}",
        "{\"voice_stopped\":true,\"accepted\":true}",
        "{\"voice_stopped\":\"true\",\"status\":\"streaming\"}",
        // 未知状态 / 非文本状态
        "{\"status\":\"interrupted\"}",
        "{\"status\":\"cancelled\",\"accepted\":true}",
        // turn_id 类型非法：不得伪造引用
        "{\"status\":\"queued\",\"turn_id\":7}"
    })
    void busyReceiptsThatContradictAcceptanceAreRejected(String raw) {
        var gateway = new ProbeGateway() {
            public Turn submitPrompt(String sessionId, String prompt) { return new Turn(null, raw); }
        };
        assertThatThrownBy(() -> new HermesAssistantRuntime(gateway, new ObjectMapper()).submit(null, "任务"))
            .isInstanceOf(IllegalStateException.class);
    }

    @org.junit.jupiter.api.Test
    void busyRepliesCarryTheirOwnAcceptanceClassAndNeverAFabricatedTurnReference() {
        record Case(String raw, HermesAssistantRuntime.Acceptance acceptance) {}
        for (Case c : java.util.List.of(
                new Case("{\"status\":\"streaming\"}", HermesAssistantRuntime.Acceptance.ACCEPTED_NOW),
                new Case("{\"status\":\"accepted\"}", HermesAssistantRuntime.Acceptance.ACCEPTED_NOW),
                new Case("{\"accepted\":true}", HermesAssistantRuntime.Acceptance.ACCEPTED_NOW),
                new Case("{\"status\":\"queued\"}", HermesAssistantRuntime.Acceptance.QUEUED),
                new Case("{\"status\":\"queued\",\"accepted\":true}", HermesAssistantRuntime.Acceptance.QUEUED),
                new Case("{\"status\":\"steered\"}", HermesAssistantRuntime.Acceptance.STEERED),
                new Case("{\"status\":\"steered\",\"accepted\":true}", HermesAssistantRuntime.Acceptance.STEERED),
                new Case("{\"status\":\"redirected\"}", HermesAssistantRuntime.Acceptance.REDIRECTED))) {
            var gateway = new ProbeGateway() {
                public Turn submitPrompt(String sessionId, String prompt) { return new Turn(null, c.raw()); }
            };
            var receipt = new HermesAssistantRuntime(gateway, new ObjectMapper()).submit(null, "任务");
            assertThat(receipt.acceptance()).isEqualTo(c.acceptance());
            // 受理态分类不改变「已受理、未完成」的协议语义，也不伪造 turn_id。
            assertThat(receipt.state()).isEqualTo(HermesAssistantRuntime.State.SUBMITTED);
            assertThat(receipt.turnRef()).isNull();
        }
    }

    static class ProbeGateway implements HermesGateway {
        int promptCalls;
        String promptSession;
        public boolean available() { return true; }
        public Session createSession(String cwd) {
            return new Session("live-1", "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}");
        }
        public Turn submitPrompt(String sessionId, String prompt) {
            promptCalls++;
            promptSession = sessionId;
            return new Turn("invented-local-id", "{\"status\":\"streaming\"}");
        }
        public boolean interrupt(String sessionId) { return false; }
    }
}
