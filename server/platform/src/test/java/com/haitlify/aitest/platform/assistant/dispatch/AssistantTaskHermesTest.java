package com.haitlify.aitest.platform.assistant.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.haitlify.aitest.platform.assistant.compile.AssistantScriptCompiler;
import com.haitlify.aitest.platform.assistant.draft.AssistantDraftService;
import com.haitlify.aitest.platform.assistant.ledger.AssistantLedgerRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Hermes 接入：开启后必须真的建会话并提交一轮，把 stored_session_id / turn_ref 绑到 attempt；
 * 用 node 假网关（与 A0 探针同协议）跑真实子进程，不 mock 客户端。
 */
@SpringBootTest(properties = {
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "app.assistant.hermes.enabled=true",
    "app.assistant.hermes.timeout-seconds=10"
})
@ActiveProfiles("test")
class AssistantTaskHermesTest {

    private static final HttpServer STUB = startStub();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        Path script = Path.of(AssistantTaskHermesTest.class.getResource("/assistant/fake-tui-gateway.mjs").toURI());
        registry.add("app.assistant.hermes.command", () -> "node " + script);
        registry.add("app.assistant.hermes.cwd", () -> System.getProperty("java.io.tmpdir"));
        registry.add("app.web-automation.runner.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/runs", AssistantTaskHermesTest::handle);
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("桩 Runner 启动失败", ex);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        boolean isCreate = "POST".equals(exchange.getRequestMethod());
        String body = isCreate ? "{\"runId\":\"run-hermes-1\",\"status\":\"queued\"}" : "{\"status\":\"running\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(isCreate ? 202 : 200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Autowired
    private org.springframework.context.ApplicationContext context;

    @Test
    void applicationUsesHermesRuntimeWithoutRegisteringLegacyRuntime() {
        assertThat(context.getBeansOfType(com.haitlify.aitest.platform.assistant.runtime.HermesAssistantRuntime.class))
            .hasSize(1);
        assertThat(java.util.Arrays.stream(context.getBeanDefinitionNames())
            .map(context::getType).filter(java.util.Objects::nonNull)
            .map(Class::getName).filter(name -> name.startsWith("com.haitlify.aitest.platform.ai.")))
            .isEmpty();
    }

    @Autowired
    private AssistantTaskService tasks;

    @Autowired
    private AssistantLedgerRepository repository;

    @Autowired
    private AssistantDraftService drafts;

    private long tenant;

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(nodeAvailable(), "缺少 node，跳过 Hermes 接入测试");
        tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
    }

    private static boolean nodeAvailable() {
        try {
            return new ProcessBuilder("node", "--version").start().waitFor() == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    @Test
    void bindsHermesSessionAndTurnToAttempt() {
        AssistantScriptCompiler.CaseSpec spec = new AssistantScriptCompiler.CaseSpec("case-a1", "A1 只读夹具",
            "http://127.0.0.1:19399/fixture.html",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "/fixture.html", null),
                new AssistantScriptCompiler.Step("s2", "assertVisible", "#summary", null)));
        String draftId = drafts.confirm(drafts.create(new AssistantDraftService.CreateDraftCommand(
            tenant, 1L, 3L, "hermes 测试需求", "只读夹具", spec, "web.case.execute", "project:1/case-a1", "rev-1", null)).draftId(), tenant, 3L).draftId();
        AssistantTaskService.TaskResult result = tasks.run(new AssistantTaskService.TaskRequest(tenant, 3L, 1L,
            UUID.randomUUID().toString(), draftId, "web.case.execute", "project:1/case-a1", "rev-1", spec, null));

        assertThat(result.hermesSessionId()).isEqualTo("stored-abc-123");
        assertThat(result.hermesTurnRef()).isEqualTo("turn-1");

        AssistantLedgerRepository.AttemptRecord attempt = repository.listAttempts(result.operationId()).get(0);
        assertThat(attempt.hermesStoredSessionId()).isEqualTo("stored-abc-123");
        assertThat(attempt.hermesTurnRef()).isEqualTo("turn-1");
        assertThat(attempt.runnerRunId()).isEqualTo("run-hermes-1");
    }
}
