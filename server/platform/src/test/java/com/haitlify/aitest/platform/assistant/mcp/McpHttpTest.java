package com.haitlify.aitest.platform.assistant.mcp;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.haitlify.aitest.platform.assistant.execution.ExecutionBindingService;
import com.haitlify.aitest.platform.security.SecretCipher;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {"app.assistant.mcp.enabled=true", "app.security.enabled=true",
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "spring.datasource.url=jdbc:h2:mem:mcp-http;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class McpHttpTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExecutionBindingService execution;
    @Autowired SecretCipher cipher;
    @Autowired ObjectMapper json;
    long actor, tenant, project;
    String generation, token;
    ExecutionBindingService.Binding binding;
    static final String INIT = """
        {"jsonrpc":"2.0","id":"init-1","method":"initialize","params":{
        "protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
        """;

    @BeforeEach void seed() throws Exception {
        var now = Instant.now();
        String suffix = UUID.randomUUID().toString();
        jdbc.update("insert into platform_users(username,password_hash,role,enabled,created_at,updated_at) values (?,'unused','EDITOR',true,?,?)", suffix, now, now);
        actor = jdbc.queryForObject("select id from platform_users where username=?", Long.class, suffix);
        jdbc.update("insert into tenants(code,name,active,created_at,updated_at) values (?,'MCP',true,?,?)", suffix, now, now);
        tenant = jdbc.queryForObject("select id from tenants where code=?", Long.class, suffix);
        jdbc.update("insert into projects(code,name,description,active,tenant_id,created_at,updated_at) values (?,'MCP','',true,?,?,?)", suffix, tenant, now, now);
        project = jdbc.queryForObject("select id from projects where code=?", Long.class, suffix);
        jdbc.update("insert into tenant_members(tenant_id,username,created_at) values (?,?,?)", tenant, suffix, now);
        jdbc.update("insert into project_members(project_id,username,created_at) values (?,?,?)", project, suffix, now);
        generation = UUID.randomUUID().toString();
        String snapshot = json.writeValueAsString(new TreeMap<>(Map.of("requestId", suffix, "projectId", project,
            "requirementRef", "requirement:immutable", "requirementText", "Immutable requirement",
            "targetUrl", "https://example.com/app", "targetRevision", "v1")));
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("insert into assistant_generations(id,actor_id,project_id,tenant_id,request_id,payload_digest,snapshot_ciphertext,state,created_at) values (?,?,?,?,?,?,?,'WAITING_WORKER',?)",
            generation, actor, project, tenant, suffix, hash, cipher.encrypt(snapshot), now);
        String unit = "unit-" + suffix;
        execution.confirmUnitCleaned(execution.registerUnit(new ExecutionBindingService.WorkerUnit(unit, "sha256:" + "a".repeat(64), "policy-v1")));
        binding = execution.allocate(new ExecutionBindingService.AllocationRequest(generation, unit, Duration.ofMinutes(5)));
        token = execution.issueGrant(new ExecutionBindingService.IssueRequest(binding.bindingId(), actor)).rawToken().reveal();
    }
    MockHttpServletRequestBuilder postRpc(String body) {
        return post("/mcp").header("Authorization", "Bearer " + token)
            .header("Accept", "application/json, text/event-stream").contentType("application/json").content(body);
    }
    String initialize() throws Exception {
        return mvc.perform(postRpc(INIT)).andExpect(status().isOk()).andReturn().getResponse().getHeader("Mcp-Session-Id");
    }
    String ready() throws Exception {
        String session = initialize();
        mvc.perform(postRpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").header("Mcp-Session-Id", session))
            .andExpect(status().isAccepted()).andExpect(content().string(""));
        return session;
    }
    String rpc(String method, Object params) {
        return json.writeValueAsString(Map.of("jsonrpc", "2.0", "id", 9007199254740993L, "method", method, "params", params));
    }
    @Test void readyLifecycleListsOnlyThreeClosedToolsAndCallsInternalDraftService() throws Exception {
        String session = initialize();
        mvc.perform(postRpc(rpc("tools/list", Map.of())).header("Mcp-Session-Id", session)).andExpect(status().isBadRequest());
        mvc.perform(postRpc("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").header("Mcp-Session-Id", session))
            .andExpect(status().isAccepted()).andExpect(content().string(""));
        var listed = mvc.perform(postRpc(rpc("tools/list", Map.of())).header("Mcp-Session-Id", session))
            .andExpect(status().isOk()).andReturn().getResponse();
        var result = json.readTree(listed.getContentAsString());
        assertThat(result.path("id").asLong()).isEqualTo(9007199254740993L);
        var tools = result.path("result").path("tools");
        assertThat(tools.size()).isEqualTo(3);
        assertThat(tools.valueStream().map(t -> t.path("name").asString()).toList())
            .containsExactly("generation_context", "draft_create", "draft_get");
        for (var tool : tools) assertClosedSchema(tool.path("inputSchema"));
        mvc.perform(postRpc(rpc("tools/call", Map.of("name", "generation_context", "arguments", Map.of()))).header("Mcp-Session-Id", session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(false))
            .andExpect(jsonPath("$.result.structuredContent.requirementText").value("Immutable requirement"));
        var spec = json.readTree("""
            {"id":"case-1","name":"Visible content","startUrl":"https://example.com/app","steps":[
            {"id":"check","action":"assertVisible","target":"#content","value":null}]}
            """);
        String create = rpc("tools/call", Map.of("name", "draft_create", "arguments", Map.of("spec", spec)));
        var first = mvc.perform(postRpc(create).header("Mcp-Session-Id", session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.result.isError").value(false)).andReturn().getResponse();
        var second = mvc.perform(postRpc(create).header("Mcp-Session-Id", session)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(json.readTree(first.getContentAsString())).isEqualTo(json.readTree(second.getContentAsString()));
        String draftId = json.readTree(first.getContentAsString()).path("result").path("structuredContent").path("draftId").asString();
        assertThat(jdbc.queryForObject("select count(*) from assistant_generation_drafts where generation_id=?", Integer.class, generation)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select state from assistant_draft where draft_id=?", String.class, draftId)).isEqualTo("DRAFT");
        mvc.perform(postRpc(rpc("tools/call", Map.of("name", "draft_get", "arguments", Map.of()))).header("Mcp-Session-Id", session))
            .andExpect(jsonPath("$.result.structuredContent.draftId").value(draftId));
    }
    void assertClosedSchema(tools.jackson.databind.JsonNode schema) {
        if ("object".equals(schema.path("type").asString())) assertThat(schema.path("additionalProperties").isBoolean() && !schema.path("additionalProperties").asBoolean()).isTrue();
        if (schema.isContainer()) for (var child : schema) assertClosedSchema(child);
    }
    @Test void errorsAreProtocolOrBusinessAndNeverLeakInput() throws Exception {
        String session = ready();
        for (String name : List.of("draft_get", "draft_create")) {
            var args = name.equals("draft_get") ? Map.of() : Map.of("spec", Map.of("secretRef", "NEVER-ECHO-THIS"));
            var response = mvc.perform(postRpc(rpc("tools/call", Map.of("name", name, "arguments", args))).header("Mcp-Session-Id", session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(true)).andReturn().getResponse();
            assertThat(response.getContentAsString()).doesNotContain(token, session, "NEVER-ECHO-THIS", "Exception", "SQL");
        }
        mvc.perform(postRpc(rpc("unknown", Map.of())).header("Mcp-Session-Id", session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.error.code").value(-32601));
        for (var params : List.of(Map.of("name", "unknown", "arguments", Map.of()),
                Map.of("name", "draft_create", "arguments", Map.of("spec", "raw json")),
                Map.of("name", "draft_get", "arguments", Map.of("draftId", "forged")))) {
            mvc.perform(postRpc(rpc("tools/call", params)).header("Mcp-Session-Id", session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.error.code").value(-32602));
        }
        mvc.perform(postRpc("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{}}").header("Mcp-Session-Id", session))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.id").doesNotExist());
    }
    @Test void controlPlaneRequiresBearerForEveryMethodAndRejectsBrowserCredentials() throws Exception {
        for (String method : List.of("GET", "POST", "DELETE", "OPTIONS", "PATCH", "PUT", "HEAD")) {
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/mcp"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(postRpc(INIT).header("Cookie", "JSESSIONID=pretend")).andExpect(status().isUnauthorized());
        mvc.perform(postRpc(INIT).queryParam("access_token", "pretend")).andExpect(status().isUnauthorized());
        mvc.perform(postRpc(INIT).header("Authorization", "Bearer extra")).andExpect(status().isUnauthorized());
        mvc.perform(postRpc(INIT).header("Origin", "https://example.com")).andExpect(status().isForbidden());
        for (String method : List.of("GET", "DELETE", "OPTIONS")) {
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/mcp").header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed());
            mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/mcp").header("Authorization", "Bearer " + token).header("Origin", "null"))
                .andExpect(status().isForbidden());
        }
    }
    @Test void scopesAndSessionsCannotBeReusedAfterRevocationOrWithAnotherToken() throws Exception {
        String session = ready();
        String old = token; long oldProject = project;
        seed();
        String other = token;
        token = other;
        mvc.perform(postRpc(rpc("tools/list", Map.of())).header("Mcp-Session-Id", session)).andExpect(status().isNotFound());
        token = old;
        jdbc.update("delete from project_members where project_id=?", oldProject);
        for (String method : List.of("tools/list", "tools/call", "ping")) {
            mvc.perform(postRpc(rpc(method, Map.of())).header("Mcp-Session-Id", session)).andExpect(status().isUnauthorized());
        }
    }
    @Test void transportIsStrictAndPreservesLargeIntegerIds() throws Exception {
        String session = ready();
        String ping = rpc("ping", Map.of());
        mvc.perform(postRpc(ping).header("Mcp-Session-Id", session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result").isMap());
        for (String body : List.of("[" + INIT + "]", INIT + "{}", INIT.replace("\"jsonrpc\":\"2.0\"", "\"jsonrpc\":\"2.0\",\"jsonrpc\":\"2.0\""),
                INIT.replace("\"init-1\"", "null"), INIT.replace("\"init-1\"", "true"), "{\"a\":".repeat(40) + "0" + "}".repeat(40))) {
            mvc.perform(postRpc(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(postRpc(INIT).content(new byte[]{(byte)0xc3, (byte)0x28})).andExpect(status().isBadRequest());
        mvc.perform(postRpc(" ".repeat(65537)).header("Transfer-Encoding", "chunked")).andExpect(status().isPayloadTooLarge());
        mvc.perform(postRpc(INIT).contentType("text/plain")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/mcp").header("Authorization", "Bearer " + token).contentType("application/json").content(INIT))
            .andExpect(status().isNotAcceptable());
        mvc.perform(postRpc(ping).header("Mcp-Session-Id", session).header("MCP-Protocol-Version", "2025-03-26")).andExpect(status().isBadRequest());
        mvc.perform(postRpc(ping).header("Mcp-Session-Id", "unknown")).andExpect(status().isNotFound());
        mvc.perform(postRpc(ping)).andExpect(status().isBadRequest());
        mvc.perform(postRpc(INIT.replace("2025-11-25", "2099-01-01")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result.protocolVersion").value("2025-11-25"));
    }
    @Test void exactPathOnlyAndOrdinaryApiCsrfUnchanged() throws Exception {
        mvc.perform(post("/mcp/").header("Authorization", "Bearer " + token).contentType("application/json").content(INIT))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/projects").header("Authorization", "Bearer " + token).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/ctx/mcp").contextPath("/ctx").header("Authorization", "Bearer " + token)
            .header("Accept", "application/json, text/event-stream").contentType("application/json").content(INIT)).andExpect(status().isOk());
        mvc.perform(postRpc(INIT).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("browser").roles("ADMIN")))
            .andExpect(status().isOk());
        mvc.perform(post("/mcp").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("browser").roles("ADMIN")))
            .andExpect(status().isUnauthorized());
    }
    @Test void toolActionIsReauthorizedAndConflictsAreNotSuccessfulResults() throws Exception {
        String session = ready();
        var spec = json.readTree("{\"id\":\"one\",\"name\":\"One\",\"startUrl\":\"https://example.com/app\",\"steps\":[{\"id\":\"s\",\"action\":\"assertVisible\",\"target\":\"#main\"}]}");
        mvc.perform(postRpc(rpc("tools/call", Map.of("name", "draft_create", "arguments", Map.of("spec", spec)))).header("Mcp-Session-Id", session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(false));
        ((tools.jackson.databind.node.ObjectNode) spec).put("name", "Different");
        mvc.perform(postRpc(rpc("tools/call", Map.of("name", "draft_create", "arguments", Map.of("spec", spec)))).header("Mcp-Session-Id", session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(true));
        jdbc.update("update assistant_execution_grants set actions='generation_context,draft_get' where binding_id=?", binding.bindingId());
        mvc.perform(postRpc(rpc("tools/list", Map.of())).header("Mcp-Session-Id", session)).andExpect(status().isOk());
        mvc.perform(postRpc(rpc("tools/call", Map.of("name", "draft_create", "arguments", Map.of("spec", spec)))).header("Mcp-Session-Id", session))
            .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from assistant_generation_drafts where generation_id=?", Integer.class, generation)).isEqualTo(1);
    }
    @Test void protocolMetadataIsAcceptedWithoutBecomingBusinessArguments() throws Exception {
        String session = ready();
        var meta = Map.of("progressToken", "p1", "client/example", Map.of("trace", "opaque"));
        mvc.perform(postRpc(rpc("tools/list", Map.of("_meta", meta))).header("Mcp-Session-Id", session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result.tools.length()").value(3));
        mvc.perform(postRpc(rpc("tools/call", Map.of("name", "generation_context", "arguments", Map.of(), "_meta", meta)))
                .header("Mcp-Session-Id", session))
            .andExpect(status().isOk()).andExpect(jsonPath("$.result.isError").value(false))
            .andExpect(jsonPath("$.result.structuredContent.requirementText").value("Immutable requirement"));
        // _meta is protocol data, never a way to inject tool identity arguments.
        mvc.perform(postRpc(rpc("tools/call", Map.of("name", "generation_context", "arguments", Map.of("actorId", actor), "_meta", meta)))
                .header("Mcp-Session-Id", session))
            .andExpect(jsonPath("$.error.code").value(-32602));
    }

    @Test void parameterlessToolsAcceptOmittedArgumentsButCreateStillRequiresSpec() throws Exception {
        String session = ready();
        mvc.perform(postRpc(rpc("tools/call", Map.of("name", "generation_context"))).header("Mcp-Session-Id", session))
            .andExpect(jsonPath("$.result.isError").value(false));
        mvc.perform(postRpc(rpc("tools/call", Map.of("name", "draft_get"))).header("Mcp-Session-Id", session))
            .andExpect(jsonPath("$.result.isError").value(true))
            .andExpect(jsonPath("$.error").doesNotExist());
        mvc.perform(postRpc(rpc("tools/call", Map.of("name", "draft_create"))).header("Mcp-Session-Id", session))
            .andExpect(jsonPath("$.error.code").value(-32602));
        assertThat(jdbc.queryForObject("select count(*) from assistant_generation_drafts where generation_id=?", Integer.class, generation)).isZero();
    }

    @Test void metadataTypesAndExplicitNullArgumentsRemainInvalid() throws Exception {
        String session = ready();
        for (String params : List.of(
                "{\"name\":\"generation_context\",\"arguments\":null}",
                "{\"name\":\"generation_context\",\"_meta\":null}",
                "{\"name\":\"generation_context\",\"_meta\":[]}",
                "{\"name\":\"generation_context\",\"_meta\":{\"progressToken\":true}}",
                "{\"name\":\"generation_context\",\"actorId\":1}")) {
            mvc.perform(postRpc(rpc("tools/call", json.readTree(params))).header("Mcp-Session-Id", session))
                .andExpect(jsonPath("$.error.code").value(-32602));
        }
    }

    @Test void invalidVersionIsRejectedEvenForUnsupportedHttpMethods() throws Exception {
        mvc.perform(get("/mcp").header("Authorization", "Bearer " + token).header("MCP-Protocol-Version", "bad"))
            .andExpect(status().isBadRequest());
    }
    @Test void initializeNegotiatesAndIsolatedFromBrowserSession() throws Exception {
        MvcResult result = mvc.perform(postRpc(INIT)).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie"))
            .andExpect(jsonPath("$.id").value("init-1"))
            .andExpect(jsonPath("$.result.protocolVersion").value("2025-11-25"))
            .andExpect(jsonPath("$.result.serverInfo.name").value("haitlify-private-drafts"))
            .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(json.readTree(result.getResponse().getContentAsString()).path("result").path("capabilities").propertyNames()).containsExactly("tools");
        assertThat(result.getResponse().getHeader("Mcp-Session-Id")).matches("[A-Za-z0-9_-]{43}");
    }
}
