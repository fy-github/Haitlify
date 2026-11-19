package com.haitlify.aitest.platform.assistant.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import com.haitlify.aitest.platform.assistant.hermes.HermesGateway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class HermesCompletionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptedWithoutFinalReplyNeverCountsAsCompleted() {
        var runtime = runtime(List.of());
        var submitted = runtime.submit("/state", "test");
        assertThatThrownBy(() -> await(runtime, submitted))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("终态");
    }

    @Test
    void returnsOnlyAuthoritativeFinalTextNotProgressOrOtherSessions() {
        var runtime = runtime(List.of(
            event("other", "message.complete", "complete", "wrong"),
            event("live-1", "tool.complete", "complete", "tool-output"),
            event("live-1", "message.delta", null, "partial"),
            event("live-1", "message.complete", "complete", "model-final")));
        assertThat(await(runtime, runtime.submit("/state", "test"))).isEqualTo("model-final");
    }

    @Test
    void interruptedErrorAndMalformedFinalRepliesFailClosed() {
        for (String status : List.of("error", "interrupted", "unknown", "")) {
            var runtime = runtime(List.of(event("live-1", "message.complete", status, "not-success")));
            assertThatThrownBy(() -> await(runtime, runtime.submit("/state", "test")))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void explicitErrorInSameBatchCannotBeHiddenByCompletedMessage() {
        var runtime = runtime(List.of(
            event("live-1", "message.complete", "complete", "candidate"),
            event("live-1", "error", null, "failure")));
        assertThatThrownBy(() -> await(runtime, runtime.submit("/state", "test")))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void preInterruptedWaitRejectsQueuedFinalAndPreservesFlag() {
        var runtime = runtime(List.of(event("live-1", "message.complete", "complete", "final")));
        var submitted = runtime.submit("/state", "test");
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> await(runtime, submitted))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("中断");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        // The interrupted entry must not consume the buffered reply.
        assertThat(await(runtime, submitted)).isEqualTo("final");
    }

    @Test
    void interruptionDuringDrainCannotReturnSuccess() {
        var runtime = runtime(List.of(event("live-1", "message.complete", "complete", "final")),
            () -> Thread.currentThread().interrupt());
        var submitted = runtime.submit("/state", "test");
        try {
            assertThatThrownBy(() -> await(runtime, submitted))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("中断");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private JsonNode event(String session, String type, String status, String text) {
        var envelope = mapper.createObjectNode().put("jsonrpc", "2.0").put("method", "event");
        var params = envelope.putObject("params").put("session_id", session).put("type", type);
        var payload = params.putObject("payload").put("text", text);
        if (status != null) payload.put("status", status);
        return envelope;
    }

    private HermesAssistantRuntime runtime(List<JsonNode> batch) {
        return runtime(batch, () -> {});
    }

    private HermesAssistantRuntime runtime(List<JsonNode> batch, Runnable duringDrain) {
        return new HermesAssistantRuntime(new HermesGateway() {
            private boolean consumed;
            public boolean available() { return true; }
            public Session createSession(String cwd) {
                return new Session("live-1", "{\"session_id\":\"live-1\",\"stored_session_id\":\"stored-1\"}");
            }
            public Turn submitPrompt(String id, String prompt) { return new Turn(null, "{\"status\":\"streaming\"}"); }
            public boolean interrupt(String id) { return true; }
            public List<JsonNode> drainEvents(String id) {
                if (consumed) return List.of();
                consumed = true;
                duringDrain.run();
                return batch;
            }
        }, mapper);
    }

    // Reflection lets RED exercise the missing runtime capability without a test compilation error.
    private String await(HermesAssistantRuntime runtime, HermesAssistantRuntime.Submission submission) {
        try {
            return (String) HermesAssistantRuntime.class
                .getMethod("awaitFinalText", HermesAssistantRuntime.Submission.class, Duration.class)
                .invoke(runtime, submission, Duration.ofMillis(30));
        } catch (NoSuchMethodException ex) {
            throw new AssertionError("Runtime cannot consume a final model reply yet", ex);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof RuntimeException cause) throw cause;
            throw new AssertionError(ex.getCause());
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }
}
