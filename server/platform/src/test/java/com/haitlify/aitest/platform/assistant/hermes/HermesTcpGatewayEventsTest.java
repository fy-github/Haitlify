package com.haitlify.aitest.platform.assistant.hermes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real loopback transport, pinned tui_gateway wire fixtures; NOT a model/business E2E gate. */
class HermesTcpGatewayEventsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void peerEofWakesPendingCallInsteadOfWaitingForRpcTimeout() throws Exception {
        try (Peer peer = new Peer((reader, writer) -> reader.readLine())) {
            HermesTcpGateway gateway = peer.gateway();
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> gateway.submitPrompt("live-a", "hello"))
                    .isInstanceOf(IllegalStateException.class));
            peer.finished.get(2, TimeUnit.SECONDS);
            assertThat(gateway.available()).isFalse();
        }
    }

    @Test
    void drainsActualEventEnvelopesByLiveSessionWithoutConfusingToolsOrReceipts() throws Exception {
        try (Peer peer = new Peer((reader, writer) -> {
            JsonNode request = mapper.readTree(reader.readLine());
            send(writer, event("live-a", "tool.complete", "{\"tool_id\":\"tool-1\",\"name\":\"read\",\"result\":\"not final\"}"));
            send(writer, event("live-b", "error", "{\"message\":\"failed\"}"));
            send(writer, event("live-a", "message.complete", "{\"text\":\"final answer\",\"status\":\"complete\"}"));
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asLong() + ",\"result\":{\"status\":\"streaming\"}}");
            reader.readLine(); // keep transport alive until test teardown
        })) {
            HermesGateway gateway = peer.gateway();
            assertThat(mapper.readTree(gateway.submitPrompt("live-a", "hello").rawJson())
                .path("result").path("status").asString()).isEqualTo("streaming");
            var a = gateway.drainEvents("live-a");
            assertThat(a).hasSize(2);
            assertThat(a.get(0).path("params").path("type").asString()).isEqualTo("tool.complete");
            assertThat(a.get(1).path("method").asString()).isEqualTo("event");
            assertThat(a.get(1).path("params").path("payload").path("text").asString()).isEqualTo("final answer");
            assertThat(a.get(1).path("params").path("payload").path("status").asString()).isEqualTo("complete");
            assertThat(gateway.drainEvents("live-a")).isEmpty();
            assertThat(gateway.drainEvents("stored-a")).isEmpty();
            var b = gateway.drainEvents("live-b");
            assertThat(b).hasSize(1);
            assertThat(b.getFirst().path("params").path("type").asString()).isEqualTo("error");
        }
    }

    @Test
    void boundedProgressBacklogNeverEvictsTerminalEvents() throws Exception {
        try (Peer peer = new Peer((reader, writer) -> {
            JsonNode request = mapper.readTree(reader.readLine());
            send(writer, event("live-a", "message.complete", "{\"text\":\"keep final\",\"status\":\"complete\"}"));
            send(writer, event("live-a", "error", "{\"message\":\"keep failure\"}"));
            for (int i = 0; i < 300; i++) {
                send(writer, event("live-a", "message.delta", "{\"text\":\"" + i + "\"}"));
            }
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asLong() + ",\"result\":{\"status\":\"streaming\"}}");
            reader.readLine();
        })) {
            HermesGateway gateway = peer.gateway();
            gateway.submitPrompt("live-a", "hello");
            var events = gateway.drainEvents("live-a");
            assertThat(events).hasSizeLessThanOrEqualTo(256);
            assertThat(events.get(0).path("params").path("payload").path("text").asString()).isEqualTo("keep final");
            assertThat(events.get(1).path("params").path("payload").path("message").asString()).isEqualTo("keep failure");
            assertThat(events.getLast().path("params").path("payload").path("text").asString()).isEqualTo("299");
        }
    }

    @Test
    void unsupportedServerRequestGetsErrorAndCannotStealPendingRpc() throws Exception {
        try (Peer peer = new Peer((reader, writer) -> {
            JsonNode request = mapper.readTree(reader.readLine());
            // Real server ids are strings. Also exercise a hostile numeric collision.
            for (String id : new String[]{"\"srq-0123456789ab\"", request.path("id").toString()}) {
                send(writer, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"preview.act\",\"params\":{\"session_id\":\"live-a\",\"action\":\"click\"}}");
                JsonNode rejection = mapper.readTree(reader.readLine());
                assertThat(rejection.path("id")).isEqualTo(mapper.readTree(id));
                assertThat(rejection.path("error").path("code").asInt()).isEqualTo(-32601);
                assertThat(rejection.has("result")).isFalse();
            }
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asLong() + ",\"result\":{\"status\":\"streaming\"}}");
        })) {
            HermesGateway gateway = peer.gateway();
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThat(mapper.readTree(gateway.submitPrompt("live-a", "hello").rawJson())
                    .path("result").path("status").asString()).isEqualTo("streaming"));
            peer.finished.get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void byteBudgetEvictsProgressRatherThanFinalReply() throws Exception {
        try (Peer peer = new Peer((reader, writer) -> {
            JsonNode request = mapper.readTree(reader.readLine());
            send(writer, event("live-a", "message.complete", "{\"text\":\"keep\",\"status\":\"error\"}"));
            for (int i = 0; i < 12; i++) send(writer, event("live-a", "message.delta", "{\"text\":\"" + "x".repeat(100_000) + "\"}"));
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asLong() + ",\"result\":{\"status\":\"streaming\"}}");
            reader.readLine();
        })) {
            HermesGateway gateway = peer.gateway();
            gateway.submitPrompt("live-a", "hello");
            var events = gateway.drainEvents("live-a");
            assertThat(events.stream().mapToInt(e -> e.toString().getBytes(StandardCharsets.UTF_8).length).sum()).isLessThanOrEqualTo(1_048_576);
            assertThat(events.getFirst().path("params").path("payload").path("status").asString()).isEqualTo("error");
        }
    }

    @Test
    void tooManyBufferedSessionsFailsExplicitlyWithoutEvictingTheirFinals() throws Exception {
        try (Peer peer = new Peer((reader, writer) -> {
            reader.readLine();
            for (int i = 0; i < 65; i++) send(writer, event("live-" + i, "message.complete", "{\"text\":\"keep\",\"status\":\"complete\"}"));
            reader.readLine();
        })) {
            HermesGateway gateway = peer.gateway();
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> gateway.submitPrompt("live-0", "hello")).isInstanceOf(IllegalStateException.class));
            assertThat(gateway.drainEvents("live-0")).hasSize(1);
            assertThatThrownBy(() -> gateway.drainEvents("live-64")).isInstanceOf(IllegalStateException.class);
            assertThat(gateway.available()).isFalse();
        }
    }

    @Test
    void oversizedFrameFailsClosedBeforeUnboundedReadLineAllocation() throws Exception {
        try (Peer peer = new Peer((reader, writer) -> {
            reader.readLine();
            writer.write(event("live-a", "message.complete", "{\"text\":\"" + "x".repeat(1_048_576) + "\"}"));
            writer.flush(); // deliberately no newline or EOF: reader must enforce its own bound
            reader.readLine();
        })) {
            HermesGateway gateway = peer.gateway();
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> gateway.submitPrompt("live-a", "hello")).isInstanceOf(IllegalStateException.class));
            assertThatThrownBy(() -> gateway.drainEvents("live-a")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void terminalOnlyOverflowIsObservableAndRetainedFinalsRemainDrainable() throws Exception {
        try (Peer peer = new Peer((reader, writer) -> {
            reader.readLine();
            for (int i = 0; i < 257; i++) send(writer, event("live-a", "message.complete", "{\"text\":\"" + i + "\",\"status\":\"complete\"}"));
        })) {
            HermesGateway gateway = peer.gateway();
            assertThatThrownBy(() -> gateway.submitPrompt("live-a", "hello")).isInstanceOf(IllegalStateException.class);
            assertThat(gateway.drainEvents("live-a")).hasSize(256);
            assertThatThrownBy(() -> gateway.drainEvents("live-a")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> gateway.submitPrompt("live-a", "no reconnect/replay")).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void interruptedCallerDoesNotLeakPendingFuture() throws Exception {
        CompletableFuture<Void> received = new CompletableFuture<>();
        try (Peer peer = new Peer((reader, writer) -> {
            reader.readLine();
            received.complete(null);
            reader.readLine();
        })) {
            HermesTcpGateway gateway = peer.gateway();
            CompletableFuture<Void> callerFinished = new CompletableFuture<>();
            Thread caller = new Thread(() -> {
                try {
                    assertThatThrownBy(() -> gateway.submitPrompt("live-a", "hello")).isInstanceOf(IllegalStateException.class);
                    assertThat(Thread.currentThread().isInterrupted()).isTrue();
                    callerFinished.complete(null);
                } catch (Throwable ex) {
                    callerFinished.completeExceptionally(ex);
                }
            });
            caller.start();
            received.get(2, TimeUnit.SECONDS);
            caller.interrupt();
            caller.join(2000);
            callerFinished.get(2, TimeUnit.SECONDS);
            assertThat(caller.isAlive()).isFalse();
            var field = HermesTcpGateway.class.getDeclaredField("pending");
            field.setAccessible(true);
            assertThat((java.util.Map<?, ?>) field.get(gateway)).isEmpty();
        }
    }

    @Test
    void timeoutFailsEventConsumptionRatherThanPretendingTheStreamIsHealthy() throws Exception {
        try (Peer peer = new Peer((reader, writer) -> {
            reader.readLine();
            // Keep reading RPCs but don't acknowledge: no real model or business side effects.
            reader.readLine();
        })) {
            HermesTcpGateway gateway = new HermesTcpGateway("127.0.0.1", peer.server.getLocalPort(), 1, mapper);
            assertThatThrownBy(() -> gateway.submitPrompt("live-a", "hello")).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> gateway.drainEvents("live-a")).isInstanceOf(IllegalStateException.class);
            assertThat(gateway.available()).isFalse();
        }
    }

    @Test
    void unsupportedTransportMustNotSilentlyClaimAnEmptyEventStream() {
        HermesGateway gateway = new HermesGateway() {
            public boolean available() { return false; }
            public Session createSession(String cwd) { throw new UnsupportedOperationException(); }
            public Turn submitPrompt(String sid, String prompt) { throw new UnsupportedOperationException(); }
            public boolean interrupt(String sid) { return false; }
        };
        assertThatThrownBy(() -> gateway.drainEvents("live-a")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void consumesPostAcknowledgementEventsOnceAcrossConcurrentPollers() throws Exception {
        CompletableFuture<Void> emit = new CompletableFuture<>();
        try (Peer peer = new Peer((reader, writer) -> {
            JsonNode request = mapper.readTree(reader.readLine());
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":" + request.path("id").asLong() + ",\"result\":{\"status\":\"streaming\"}}");
            emit.get(2, TimeUnit.SECONDS);
            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{\"session_id\":\"\",\"type\":\"gateway.ready\"}}");
            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"unrelated\",\"params\":{\"session_id\":\"live-a\"}}");
            var finalEvent = (tools.jackson.databind.node.ObjectNode) mapper.readTree(
                event("live-a", "message.complete", "{\"text\":\"partial answer\",\"status\":\"interrupted\"}"));
            ((tools.jackson.databind.node.ObjectNode) finalEvent.path("params")).put("seq", 17);
            send(writer, finalEvent.toString());
            // An explicit RPC barrier proves every preceding event was read.
            JsonNode barrier = mapper.readTree(reader.readLine());
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":" + barrier.path("id").asLong() + ",\"result\":{\"session_id\":\"live-b\",\"stored_session_id\":\"stored-b\"}}");
            reader.readLine();
        })) {
            HermesGateway gateway = peer.gateway();
            gateway.submitPrompt("live-a", "hello");
            assertThat(gateway.drainEvents("live-a")).isEmpty();
            emit.complete(null);
            assertThat(gateway.createSession(".").sessionId()).isEqualTo("live-b");
            var first = CompletableFuture.supplyAsync(() -> gateway.drainEvents("live-a"));
            var second = CompletableFuture.supplyAsync(() -> gateway.drainEvents("live-a"));
            var combined = new java.util.ArrayList<>(first.get(2, TimeUnit.SECONDS));
            combined.addAll(second.get(2, TimeUnit.SECONDS));
            assertThat(combined).hasSize(1);
            assertThat(combined.getFirst().path("params").path("seq").asInt()).isEqualTo(17);
            assertThat(combined.getFirst().path("params").path("payload").path("status").asString()).isEqualTo("interrupted");
            assertThat(gateway.drainEvents("live-b")).isEmpty();
            assertThatThrownBy(() -> gateway.drainEvents(" ")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private String event(String session, String type, String payload) {
        return "{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{\"session_id\":\"" + session
            + "\",\"type\":\"" + type + "\",\"payload\":" + payload + "}}";
    }

    private void send(BufferedWriter writer, String frame) throws Exception {
        writer.write(frame + "\n");
        writer.flush();
    }

    @FunctionalInterface
    interface Script {
        void run(BufferedReader reader, BufferedWriter writer) throws Exception;
    }

    private class Peer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final CompletableFuture<Void> finished = new CompletableFuture<>();
        volatile Socket accepted;
        final Thread thread;

        Peer(Script script) throws Exception {
            thread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    accepted = socket;
                    socket.setSoTimeout(3000);
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    var writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                    // 真实的 pin 网关会应答 client.capabilities（methods_voice.py:442-450）；
                    // 脚本只看得到自己在意的业务帧。
                    advertiseCapabilities(reader, writer);
                    script.run(reader, writer);
                    finished.complete(null);
                } catch (Exception ex) {
                    finished.completeExceptionally(ex);
                }
            }, "hermes-event-test-peer");
            thread.setDaemon(true);
            thread.start();
        }

        private void advertiseCapabilities(BufferedReader reader, BufferedWriter writer) throws Exception {
            JsonNode frame = mapper.readTree(reader.readLine());
            if (!HermesWireStub.CAPABILITIES_METHOD.equals(frame.path("method").asString(""))) {
                throw new AssertionError("网关必须先宣告 client.capabilities，实际第一帧：" + frame);
            }
            assertThat(frame.path("params").path("server_requests").asBoolean(false)).isTrue();
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":" + frame.path("id").asLong()
                + ",\"result\":" + HermesWireStub.ADVERTISED_SERVER_REQUESTS + "}");
        }


        HermesTcpGateway gateway() {
            return new HermesTcpGateway("127.0.0.1", server.getLocalPort(), 10, mapper);
        }

        @Override
        public void close() throws Exception {
            server.close();
            if (accepted != null) accepted.close();
            thread.join(2000);
        }
    }
}
