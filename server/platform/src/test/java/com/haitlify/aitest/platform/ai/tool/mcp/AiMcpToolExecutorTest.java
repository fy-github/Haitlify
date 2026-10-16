package com.haitlify.aitest.platform.ai.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AiMcpToolExecutorTest {

    @Autowired
    private AiMcpToolExecutor executor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearToolState() {
        jdbcTemplate.update("delete from ai_tool_call_records");
        jdbcTemplate.update("delete from ai_mcp_servers");
    }

    @Test
    void callsRemoteHttpToolAndAuditsObservedResult() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/mcp", exchange -> {
            JsonNode requestBody = objectMapper.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String method = requestBody.path("method").asText();
            String result = switch (method) {
                case "initialize" -> "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}";
                case "tools/list" -> "{\"tools\":[{\"name\":\"browser_snapshot\",\"inputSchema\":{\"type\":\"object\"}}]}";
                case "tools/call" -> "{\"content\":[{\"type\":\"text\",\"text\":\"observed\"}],\"isError\":false}";
                default -> "{}";
            };
            String body = "{\"jsonrpc\":\"2.0\",\"id\":" + objectMapper.writeValueAsString(requestBody.path("id").asText()) + ",\"result\":" + result + "}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            insertServer("playwright-local", true, "[{\"name\":\"browser_snapshot\",\"readOnly\":true}]", "{\"projectIds\":[1001]}", "{\"maxCallsPerRun\":3}", "http", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
            AiMcpToolExecutionResult result = executor.execute(new AiMcpToolExecutionRequest(
                "run-1", 1001L, "playwright-local", "browser_snapshot", true,
                objectMapper.createObjectNode().put("url", "https://example.test"), "qa-user"));

            assertEquals("success", result.status());
            assertTrue(result.response().path("executed").asBoolean());
            assertTrue(result.isToolObserved());
            assertEquals("observed", result.response().path("result").path("content").path(0).path("text").asText());
            assertEquals("TOOL_OBSERVED", result.resultOrigin());
            assertEquals("PENDING_HUMAN_CONFIRMATION", result.acceptanceState());
            assertFalse(String.valueOf(auditValue("run-1", "provider_call_id")).isBlank());
            assertFalse(String.valueOf(auditValue("run-1", "parameter_hash")).isBlank());
            assertEquals("mcp:run-1:browser_snapshot", auditValue("run-1", "evidence_id"));
            assertEquals("success", auditValue("run-1", "status"));
            assertEquals("TOOL_OBSERVED", auditValue("run-1", "result_origin"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void blocksNonAllowlistedToolAndAuditsBlockedCall() {
        insertServer("playwright-local", true, "[{\"name\":\"browser_snapshot\",\"readOnly\":true}]", "{\"projectIds\":[1001]}", "{\"maxCallsPerRun\":3}");

        AiMcpToolExecutionResult result = executor.execute(new AiMcpToolExecutionRequest(
            "run-2",
            1001L,
            "playwright-local",
            "browser_navigate",
            true,
            emptyArguments(),
            "qa-user"
        ));

        assertEquals("blocked", result.status());
        assertEquals("BLOCKED", result.resultOrigin());
        assertEquals("MCP tool is not allowlisted: browser_navigate", result.errorMessage());
        assertEquals(1, auditCount("run-2"));
        assertEquals("blocked", auditValue("run-2", "status"));
    }

    @Test
    void blocksWriteToolEvenWhenServerExists() {
        insertServer("playwright-local", true, "[{\"name\":\"browser_click\",\"readOnly\":false}]", "{\"projectIds\":[1001]}", "{\"maxCallsPerRun\":3}");

        AiMcpToolExecutionResult result = executor.execute(new AiMcpToolExecutionRequest(
            "run-3",
            1001L,
            "playwright-local",
            "browser_click",
            false,
            objectMapper.createObjectNode().put("_idempotencyKey", "idem-run-3"),
            "qa-user"
        ));

        assertEquals("blocked", result.status());
        assertEquals("BLOCKED", result.resultOrigin());
        // 服务端策略：write_operations_allowed=false ⇒ 写工具被管理员策略否决（不信任请求体）。
        assertEquals("MCP server policy does not allow write operations: browser_click", result.errorMessage());
        assertFalse(Boolean.TRUE.equals(auditValue("run-3", "read_only")));
    }

    @Test
    void legacyStringAllowlistEntriesCannotAuthorizeCalls() {
        insertServer("playwright-local", true, "[\"browser_snapshot\"]", "{\"projectIds\":[1001]}", "{\"maxCallsPerRun\":3}");

        AiMcpToolExecutionResult result = executor.execute(new AiMcpToolExecutionRequest(
            "run-legacy", 1001L, "playwright-local", "browser_snapshot", true, emptyArguments(), "qa-user"));

        assertEquals("blocked", result.status());
        assertEquals("BLOCKED", result.resultOrigin());
        assertTrue(result.errorMessage().contains("lacks server-side read-only metadata"));
    }

    @Test
    void writeOperationsAllowedStillRequiresHumanReview() {
        insertServer("playwright-local", true, "[{\"name\":\"browser_click\",\"readOnly\":false}]",
            "{\"projectIds\":[1001]}", "{\"maxCallsPerRun\":3}", true);

        AiMcpToolExecutionResult result = executor.execute(new AiMcpToolExecutionRequest(
            "run-write-policy", 1001L, "playwright-local", "browser_click", false,
            objectMapper.createObjectNode().put("_idempotencyKey", "idem-policy"), "qa-user"));

        assertEquals("blocked", result.status());
        assertTrue(result.errorMessage().contains("require human review"));
    }

    @Test
    void oversizedResultsAreTruncatedAndMarked() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/mcp", exchange -> {
            JsonNode requestBody = objectMapper.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String method = requestBody.path("method").asText();
            String result = switch (method) {
                case "initialize" -> "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}";
                case "tools/list" -> "{\"tools\":[{\"name\":\"browser_snapshot\",\"inputSchema\":{\"type\":\"object\"}}]}";
                case "tools/call" -> "{\"content\":[{\"type\":\"text\",\"text\":\"" + "x".repeat(10_000) + "\"}],\"isError\":false}";
                default -> "{}";
            };
            String body = "{\"jsonrpc\":\"2.0\",\"id\":" + objectMapper.writeValueAsString(requestBody.path("id").asText()) + ",\"result\":" + result + "}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            insertServer("playwright-local", true, "[{\"name\":\"browser_snapshot\",\"readOnly\":true}]",
                "{\"projectIds\":[1001]}", "{\"maxCallsPerRun\":3,\"maxResultBytes\":512}",
                "http", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");

            AiMcpToolExecutionResult result = executor.execute(new AiMcpToolExecutionRequest(
                "run-trunc", 1001L, "playwright-local", "browser_snapshot", true, emptyArguments(), "qa-user"));

            assertEquals("success", result.status());
            assertTrue(result.response().path("resultTruncated").asBoolean());
            assertTrue(result.response().path("resultBytes").asInt() > 512);
            assertTrue(result.response().path("result").path("truncated").asBoolean());
            assertTrue(result.response().path("result").path("preview").asText().length() <= 4096);
            assertEquals("TOOL_OBSERVED", result.resultOrigin());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void blocksProjectScopeMismatchAndAuditsIt() {
        insertServer("playwright-local", true, "[{\"name\":\"browser_snapshot\",\"readOnly\":true}]", "{\"projectIds\":[1001]}", "{\"maxCallsPerRun\":3}");

        AiMcpToolExecutionResult result = executor.execute(new AiMcpToolExecutionRequest(
            "run-4",
            2002L,
            "playwright-local",
            "browser_snapshot",
            true,
            emptyArguments(),
            "qa-user"
        ));

        assertEquals("blocked", result.status());
        assertEquals("BLOCKED", result.resultOrigin());
        assertEquals("MCP server is not scoped to project: 2002", result.errorMessage());
        assertEquals("blocked", auditValue("run-4", "status"));
    }

    @Test
    void blocksWhenRunCallLimitIsExceeded() {
        insertServer("playwright-local", true, "[{\"name\":\"browser_snapshot\",\"readOnly\":true}]", "{\"projectIds\":[1001]}", "{\"maxCallsPerRun\":1}");
        AiMcpToolExecutionRequest request = new AiMcpToolExecutionRequest(
            "run-5",
            1001L,
            "playwright-local",
            "browser_snapshot",
            true,
            emptyArguments(),
            "qa-user"
        );

        assertEquals("blocked", executor.execute(request).status());
        AiMcpToolExecutionResult blocked = executor.execute(new AiMcpToolExecutionRequest(
            "run-5", 1001L, "playwright-local", "browser_snapshot", true, emptyArguments(), "qa-user"
        ));

        assertEquals("blocked", blocked.status());
        assertEquals("MCP tool call limit exceeded for run: run-5", blocked.errorMessage());
        assertEquals(2, auditCount("run-5"));
    }

    @Test
    void schemaViolationsBlockWithReadablePath() throws Exception {
        int port = serve("""
            {"tools":[{"name":"browser_snapshot","inputSchema":{"type":"object","required":["url"],"properties":{"url":{"type":"string"}},"additionalProperties":false}}]}
            """, "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}");
        insertServer("playwright-local", true, "[{\"name\":\"browser_snapshot\",\"readOnly\":true}]",
            "{\"projectIds\":[1001]}", "{\"maxCallsPerRun\":3}", "http", "http://127.0.0.1:" + port + "/mcp");

        AiMcpToolExecutionResult missing = executor.execute(new AiMcpToolExecutionRequest(
            "run-schema-1", 1001L, "playwright-local", "browser_snapshot", true,
            objectMapper.createObjectNode(), "qa-user"));
        assertEquals("blocked", missing.status());
        assertTrue(missing.errorMessage().contains("$.url: required"));

        AiMcpToolExecutionResult wrongType = executor.execute(new AiMcpToolExecutionRequest(
            "run-schema-2", 1001L, "playwright-local", "browser_snapshot", true,
            objectMapper.createObjectNode().put("url", 42), "qa-user"));
        assertEquals("blocked", wrongType.status());
        assertTrue(wrongType.errorMessage().contains("$.url: expected string"));

        AiMcpToolExecutionResult unknown = executor.execute(new AiMcpToolExecutionRequest(
            "run-schema-3", 1001L, "playwright-local", "browser_snapshot", true,
            objectMapper.createObjectNode().put("url", "https://example.test").put("extra", "x"), "qa-user"));
        assertEquals("blocked", unknown.status());
        assertTrue(unknown.errorMessage().contains("$.extra: unknown property"));
    }

    @Test
    void networkAllowlistViolationsBlock() throws Exception {
        int port = serve("{\"tools\":[{\"name\":\"browser_snapshot\",\"inputSchema\":{\"type\":\"object\"}}]}",
            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}");
        insertServer("playwright-local", true, "[{\"name\":\"browser_snapshot\",\"readOnly\":true}]",
            "{\"projectIds\":[1001]}",
            "{\"maxCallsPerRun\":3,\"networkAllowlist\":[\"only.allowed.host\"]}",
            "http", "http://127.0.0.1:" + port + "/mcp");

        AiMcpToolExecutionResult result = executor.execute(new AiMcpToolExecutionRequest(
            "run-net", 1001L, "playwright-local", "browser_snapshot", true, emptyArguments(), "qa-user"));

        assertEquals("blocked", result.status());
        assertTrue(result.errorMessage().contains("network allowlist"));
    }

    @Test
    void secretsInToolOutputAreRedactedBeforeDeliveryAndAudit() throws Exception {
        int port = serve("{\"tools\":[{\"name\":\"browser_snapshot\",\"inputSchema\":{\"type\":\"object\"}}]}",
            "{\"content\":[{\"type\":\"text\",\"text\":\"headers: Authorization: Bearer abcdef1234567890 done\"}],\"isError\":false}");
        insertServer("playwright-local", true, "[{\"name\":\"browser_snapshot\",\"readOnly\":true}]",
            "{\"projectIds\":[1001]}", "{\"maxCallsPerRun\":3}", "http", "http://127.0.0.1:" + port + "/mcp");

        AiMcpToolExecutionResult result = executor.execute(new AiMcpToolExecutionRequest(
            "run-redact", 1001L, "playwright-local", "browser_snapshot", true, emptyArguments(), "qa-user"));

        assertEquals("success", result.status());
        String delivered = result.response().path("result").path("content").path(0).path("text").asText();
        assertFalse(delivered.contains("abcdef1234567890"));
        assertTrue(delivered.contains("[REDACTED]"));
        assertTrue(result.response().path("resultSanitized").asBoolean());
        assertTrue(result.response().path("resultRedactions").asInt() >= 1);
        // 审计落库的也是脱敏后的结果，原始密钥不外泄。
        assertFalse(String.valueOf(auditValue("run-redact", "response_summary_json")).contains("abcdef1234567890"));
    }

    @Test
    void toolsAbsentFromLiveListAreBlockedEvenIfPolicyAllowsThem() throws Exception {
        int port = serve("{\"tools\":[{\"name\":\"other_tool\",\"inputSchema\":{\"type\":\"object\"}}]}",
            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}");
        insertServer("playwright-local", true, "[{\"name\":\"browser_snapshot\",\"readOnly\":true}]",
            "{\"projectIds\":[1001]}", "{\"maxCallsPerRun\":3}", "http", "http://127.0.0.1:" + port + "/mcp");

        AiMcpToolExecutionResult result = executor.execute(new AiMcpToolExecutionRequest(
            "run-live-list", 1001L, "playwright-local", "browser_snapshot", true, emptyArguments(), "qa-user"));

        assertEquals("blocked", result.status());
        assertTrue(result.errorMessage().contains("not present in the live tools/list"));
    }

    @Test
    void repeatedIdenticalParametersAreBlockedAfterTheLoopLimit() throws Exception {
        int port = serve("{\"tools\":[{\"name\":\"browser_snapshot\",\"inputSchema\":{\"type\":\"object\"}}]}",
            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}");
        insertServer("playwright-local", true, "[{\"name\":\"browser_snapshot\",\"readOnly\":true}]",
            "{\"projectIds\":[1001]}", "{\"maxCallsPerRun\":10,\"maxRepeatedParameterCalls\":3}",
            "http", "http://127.0.0.1:" + port + "/mcp");
        ObjectNode args = objectMapper.createObjectNode().put("url", "https://same.example");

        for (int attempt = 1; attempt <= 3; attempt++) {
            AiMcpToolExecutionResult allowed = executor.execute(new AiMcpToolExecutionRequest(
                "run-loop", 1001L, "playwright-local", "browser_snapshot", true, args, "qa-user"));
            assertEquals("success", allowed.status(), "attempt " + attempt + " should pass");
        }
        AiMcpToolExecutionResult looped = executor.execute(new AiMcpToolExecutionRequest(
            "run-loop", 1001L, "playwright-local", "browser_snapshot", true, args, "qa-user"));

        assertEquals("blocked", looped.status());
        assertTrue(looped.errorMessage().contains("repeated parameter loop"));
    }

    @Test
    void toolCallAuditCarriesNodeAndAttemptLinks() throws Exception {
        int port = serve("{\"tools\":[{\"name\":\"browser_snapshot\",\"inputSchema\":{\"type\":\"object\"}}]}",
            "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"isError\":false}");
        insertServer("playwright-local", true, "[{\"name\":\"browser_snapshot\",\"readOnly\":true}]",
            "{\"projectIds\":[1001]}", "{\"maxCallsPerRun\":3}", "http", "http://127.0.0.1:" + port + "/mcp");

        executor.execute(new AiMcpToolExecutionRequest(
            "run-links", 1001L, "playwright-local", "browser_snapshot", true, emptyArguments(), "qa-user",
            "node-77", "attempt-88"));

        assertEquals("node-77", auditValue("run-links", "node_id"));
        assertEquals("attempt-88", auditValue("run-links", "attempt_id"));
    }

    @Test
    void sideEffectToolsRequireAnIdempotencyKeyBeforeAnythingElse() throws Exception {
        insertServer("playwright-local", true, "[{\"name\":\"browser_click\",\"readOnly\":false}]",
            "{\"projectIds\":[1001]}", "{\"maxCallsPerRun\":3}");

        AiMcpToolExecutionResult noKey = executor.execute(new AiMcpToolExecutionRequest(
            "run-idem-1", 1001L, "playwright-local", "browser_click", false, emptyArguments(), "qa-user"));
        assertEquals("blocked", noKey.status());
        assertTrue(noKey.errorMessage().contains("requires an idempotency key"));

        AiMcpToolExecutionResult withKey = executor.execute(new AiMcpToolExecutionRequest(
            "run-idem-2", 1001L, "playwright-local", "browser_click", false,
            objectMapper.createObjectNode().put("_idempotencyKey", "idem-123"), "qa-user"));
        assertEquals("blocked", withKey.status());
        // 带键仍需人审放行：不会因为带键就绕过管理员策略。
        assertTrue(withKey.errorMessage().contains("does not allow write operations"));
    }

    /** 起一个仅服务 tools/list 与 tools/call 的最小 MCP fixture，返回端口。 */
    private int serve(String toolsResultJson, String callResultJson) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/mcp", exchange -> {
            JsonNode requestBody = objectMapper.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String method = requestBody.path("method").asText();
            String result = switch (method) {
                case "initialize" -> "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"fixture\",\"version\":\"1\"}}";
                case "tools/list" -> toolsResultJson;
                case "tools/call" -> callResultJson;
                default -> "{}";
            };
            String body = "{\"jsonrpc\":\"2.0\",\"id\":" + objectMapper.writeValueAsString(requestBody.path("id").asText()) + ",\"result\":" + result + "}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        FIXTURE_SERVERS.add(server);
        return server.getAddress().getPort();
    }

    private static final java.util.List<HttpServer> FIXTURE_SERVERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void stopFixtures() {
        for (HttpServer server : FIXTURE_SERVERS) {
            server.stop(0);
        }
        FIXTURE_SERVERS.clear();
    }

    private ObjectNode emptyArguments() {
        return objectMapper.createObjectNode();
    }

    private void insertServer(String serverId, boolean enabled, String allowlistJson, String projectScopeJson, String sandboxConfigJson) {
        insertServer(serverId, enabled, allowlistJson, projectScopeJson, sandboxConfigJson, false);
    }

    private void insertServer(String serverId, boolean enabled, String allowlistJson, String projectScopeJson, String sandboxConfigJson, boolean writeOperationsAllowed) {
        insertServer(serverId, enabled, allowlistJson, projectScopeJson, sandboxConfigJson, "stdio", "local", writeOperationsAllowed);
    }

    private void insertServer(String serverId, boolean enabled, String allowlistJson, String projectScopeJson, String sandboxConfigJson, String serverType, String endpoint) {
        insertServer(serverId, enabled, allowlistJson, projectScopeJson, sandboxConfigJson, serverType, endpoint, false);
    }

    private void insertServer(String serverId, boolean enabled, String allowlistJson, String projectScopeJson, String sandboxConfigJson, String serverType, String endpoint, boolean writeOperationsAllowed) {
        jdbcTemplate.update("""
            insert into ai_mcp_servers (
                server_id, project_id, name, server_type, endpoint, auth_mode,
                available_tools_json, tool_allowlist_json, project_scope_json,
                write_operations_allowed, timeout_ms, enabled, audit_enabled,
                sandbox_config_json, actor_id, actor_name, created_at, updated_at
            )
            values (?, null, ?, ?, ?, 'none', ?, ?, ?, ?, 30000, ?, true, ?, 'qa-user', 'QA User', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """,
            serverId,
            serverId,
            serverType,
            endpoint,
            allowlistJson,
            allowlistJson,
            projectScopeJson,
            writeOperationsAllowed,
            enabled,
            sandboxConfigJson
        );
    }

    private int auditCount(String runId) {
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from ai_tool_call_records where run_id = ?",
            Integer.class,
            runId
        );
        return count == null ? 0 : count;
    }

    private Object auditValue(String runId, String column) {
        return jdbcTemplate.queryForObject(
            "select " + column + " from ai_tool_call_records where run_id = ? order by id desc limit 1",
            Object.class,
            runId
        );
    }
}
