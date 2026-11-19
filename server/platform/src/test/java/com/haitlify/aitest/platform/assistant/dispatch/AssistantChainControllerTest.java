package com.haitlify.aitest.platform.assistant.dispatch;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** HTTP 层：只做状态码映射，判定仍在服务层（幂等冲突 409、冻结 409、参数 400）。 */
@SpringBootTest(properties = "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AssistantChainControllerTest {

    private static final HttpServer STUB = startStub();

    @DynamicPropertySource
    static void runnerProps(DynamicPropertyRegistry registry) {
        registry.add("app.web-automation.runner.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/runs", AssistantChainControllerTest::handle);
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("桩 Runner 启动失败", ex);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = "POST".equals(exchange.getRequestMethod()) && "/runs".equals(path)
            ? "{\"runId\":\"run-http-1\",\"status\":\"queued\"}"
            : "{\"status\":\"running\"}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders("POST".equals(exchange.getRequestMethod()) ? 202 : 200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private String admissionBody(long tenant, String requestId, String digest) {
        return """
            {"tenantId":%d,"actorId":7,"projectId":1,"requestId":"%s","inputDigest":"%s",
             "actionCode":"web.contact.create","targetRef":"project:1/contacts","targetRevision":"rev-1"}
            """.formatted(tenant, requestId, digest);
    }

    @Test
    void admitsThenRunsTaskAndReadsOperation() throws Exception {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String requestId = UUID.randomUUID().toString();
        String specJson = """
            {"id":"case-http","name":"HTTP 夹具","startUrl":"http://127.0.0.1:19399/f.html",
             "steps":[{"id":"s1","action":"goto","target":"/f.html","value":null},
                      {"id":"s2","action":"assertVisible","target":"#summary","value":null}]}
            """;
        String draftBody = """
            {"tenantId":%d,"projectId":1,"createdBy":3,"requirementRef":"req-http","requirementText":"夹具需求",
             "actionCode":"web.case.execute","targetRef":"project:1/case-http","targetRevision":"rev-1","spec":%s}
            """.formatted(tenant, specJson);
        String created = mockMvc.perform(post("/api/assistant/drafts")
                .contentType(MediaType.APPLICATION_JSON).content(draftBody))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String draftId = created.replaceAll(".*\"draftId\":\"([^\"]+)\".*", "$1");
        mockMvc.perform(post("/api/assistant/drafts/" + draftId + "/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":%d,\"actorId\":3}".formatted(tenant)))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/assistant/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"tenantId":%d,"actorId":3,"projectId":1,"requestId":"%s","draftId":"%s",
                     "actionCode":"web.case.execute","targetRef":"project:1/case-http","targetRevision":"rev-1","spec":%s}
                    """.formatted(tenant, requestId, draftId, specJson)))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.runId").value("run-http-1"))
            .andExpect(jsonPath("$.executionState").value("DISPATCHED"))
            .andExpect(jsonPath("$.operationId").isNotEmpty());
    }

    @Test
    void unconfirmedDraftBlocksExecutionUntilConfirmed() throws Exception {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String specJson = """
            {"id":"case-http","name":"HTTP 夹具","startUrl":"http://127.0.0.1:19399/f.html",
             "steps":[{"id":"s1","action":"goto","target":"/f.html","value":null},
                      {"id":"s2","action":"assertVisible","target":"#summary","value":null}]}
            """;
        String draftBody = """
            {"tenantId":%d,"projectId":1,"createdBy":3,"requirementRef":"req-http","requirementText":"夹具需求",
             "actionCode":"web.case.execute","targetRef":"project:1/case-http","targetRevision":"rev-1","spec":%s}
            """.formatted(tenant, specJson);

        String created = mockMvc.perform(post("/api/assistant/drafts")
                .contentType(MediaType.APPLICATION_JSON).content(draftBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("DRAFT"))
            .andReturn().getResponse().getContentAsString();
        String draftId = created.replaceAll(".*\"draftId\":\"([^\"]+)\".*", "$1");

        String taskBody = """
            {"tenantId":%d,"actorId":3,"projectId":1,"requestId":"%s","draftId":"%s",
             "actionCode":"web.case.execute","targetRef":"project:1/case-http","targetRevision":"rev-1","spec":%s}
            """.formatted(tenant, UUID.randomUUID(), draftId, specJson);

        mockMvc.perform(post("/api/assistant/tasks").contentType(MediaType.APPLICATION_JSON).content(taskBody))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("draft_not_confirmed"));

        mockMvc.perform(post("/api/assistant/drafts/" + draftId + "/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":%d,\"actorId\":3}".formatted(tenant)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("CONFIRMED"))
            .andExpect(jsonPath("$.confirmedBy").value(3));

        mockMvc.perform(post("/api/assistant/tasks").contentType(MediaType.APPLICATION_JSON).content(taskBody))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.runId").value("run-http-1"));
    }

    @Test
    void sameRequestIdWithAnotherDigestReturns409() throws Exception {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String requestId = UUID.randomUUID().toString();

        mockMvc.perform(post("/api/assistant/requests")
                .contentType(MediaType.APPLICATION_JSON).content(admissionBody(tenant, requestId, "a".repeat(64))))
            .andExpect(status().isCreated());

        mockMvc.perform(post("/api/assistant/requests")
                .contentType(MediaType.APPLICATION_JSON).content(admissionBody(tenant, requestId, "b".repeat(64))))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("idempotency_conflict"));
    }

    @Test
    void malformedAdmissionReturns400() throws Exception {
        mockMvc.perform(post("/api/assistant/requests")
                .contentType(MediaType.APPLICATION_JSON)
                .content(admissionBody(1L, "not-a-uuid", "a".repeat(64))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void unknownOperationReturns400() throws Exception {
        mockMvc.perform(get("/api/assistant/operations/" + UUID.randomUUID()).param("tenantId", "1"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void rawDispatchEndpointIsNotExposed() throws Exception {
        mockMvc.perform(post("/api/assistant/operations/" + UUID.randomUUID() + "/dispatch")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":1,\"requestId\":\"x\",\"payload\":\"{}\"}"))
            .andExpect(status().isNotFound());
    }

    /** 等待用户四个 HTTP 入口的状态码与语义（复核 D8 要求覆盖新端点）。 */
    @Test
    void waitingUserEndpointsMoveStateAndMapConflictsTo409() throws Exception {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        String requestId = UUID.randomUUID().toString();
        String admitted = mockMvc.perform(post("/api/assistant/requests")
                .contentType(MediaType.APPLICATION_JSON).content(admissionBody(tenant, requestId, "c".repeat(64))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        String operationId = admitted.replaceAll(".*\"operationId\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(post("/api/assistant/operations/" + operationId + "/waiting-user")
                .param("tenantId", String.valueOf(tenant))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"需要本人完成 MFA\",\"timeoutMinutes\":15}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.executionState").value("WAITING_USER"))
            .andExpect(jsonPath("$.reason").value("需要本人完成 MFA"));

        mockMvc.perform(get("/api/assistant/operations/" + operationId + "/waiting-user")
                .param("tenantId", String.valueOf(tenant)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reason").value("需要本人完成 MFA"));

        mockMvc.perform(post("/api/assistant/operations/" + operationId + "/waiting-user/resume")
                .param("tenantId", String.valueOf(tenant)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.executionState").value("PENDING"));

        // 不在等待态再恢复：状态冲突必须是 409，不能是 500（复核 D10）。
        mockMvc.perform(post("/api/assistant/operations/" + operationId + "/waiting-user/resume")
                .param("tenantId", String.valueOf(tenant)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("state_conflict"));

        // 取消等待：不在等待态 → 409（专用冲突异常，不能是 500）。
        mockMvc.perform(post("/api/assistant/operations/" + operationId + "/waiting-user/cancel")
                .param("tenantId", String.valueOf(tenant))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"放弃等待\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("state_conflict"));

        // 手动清扫按租户隔离且要求操作人。
        mockMvc.perform(post("/api/assistant/operations/waiting-user/expire-overdue")
                .param("tenantId", String.valueOf(tenant))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"actorId\":7}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.expired").isArray());
    }
}
