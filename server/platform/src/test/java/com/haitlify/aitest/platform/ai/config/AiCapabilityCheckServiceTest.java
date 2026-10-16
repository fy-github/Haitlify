package com.haitlify.aitest.platform.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.haitlify.aitest.platform.ai.config.AiCapabilityCheckService.CapabilityCheckReport;
import com.haitlify.aitest.platform.ai.runtime.transport.AiProtocolTransportClient;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.ObjectMapper;

/**
 * T04：生成级与业务级能力预检。先行的三条要求用例：
 * HTTP 200 空内容不能通过；models 可列出但生成拒绝；probe 后更新 profile 导致报告过期。
 */
class AiCapabilityCheckServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;
    private String baseUrl;
    private JdbcTemplate jdbc;
    private AiCapabilityCheckService service;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        jdbc = mock(JdbcTemplate.class);
        var cipher = mock(com.haitlify.aitest.platform.security.SecretCipher.class);
        when(cipher.decrypt(anyString())).thenAnswer(invocation -> "materialized-" + invocation.getArgument(0));
        service = new AiCapabilityCheckService(
            jdbc, objectMapper, new AiProtocolTransportClient(HttpClient.newHttpClient(), objectMapper),
            cipher, HttpClient.newHttpClient(), 3_600);
        when(jdbc.queryForList(anyString(), eq("profile-1"), eq(1)))
            .thenReturn(List.of(profileRow("{\"capabilities\":[\"TEXT\",\"JSON_OBJECT\"]}")));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private Map<String, Object> profileRow(String capabilitiesJson) {
        Map<String, Object> row = new HashMap<>();
        row.put("profile_id", "profile-1");
        row.put("version", 1);
        row.put("connection_id", "conn-1");
        row.put("model_id", "model-1");
        row.put("capabilities_json", capabilitiesJson);
        row.put("parameter_policy_json", "{}");
        row.put("base_url", baseUrl);
        row.put("protocol", "chat_completions");
        row.put("credential_ref", "test-key");
        return row;
    }

    private void serveModels(int status) {
        server.createContext("/v1/models", exchange -> {
            byte[] body = "{\"data\":[{\"id\":\"model-1\"}]}".getBytes();
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    private void serveChat(int status, String body) {
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] bytes = body.getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private static String chatCompletion(String id, String content) {
        return "{\"id\":\"" + id + "\",\"choices\":[{\"message\":{\"content\":\""
            + content.replace("\\", "\\\\").replace("\"", "\\\"") + "\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":5}}";
    }

    // ---------------------------------------------------------------- 三条要求用例

    @Test
    void http200WithEmptyContentDoesNotPass() {
        serveModels(200);
        serveChat(200, chatCompletion("req-empty", ""));

        CapabilityCheckReport report = service.checkProfile("profile-1", 1, false);

        assertThat(probeStatus(report, "reachability")).isEqualTo(AiCapabilityCheckService.STATUS_PASS);
        assertThat(probeStatus(report, "text")).isEqualTo(AiCapabilityCheckService.STATUS_FAIL);
        assertThat(probeDetail(report, "text")).contains("without usable text");
        assertThat(probeStatus(report, "task-json")).isEqualTo(AiCapabilityCheckService.STATUS_FAIL);
        assertThat(report.overall()).isEqualTo(AiCapabilityCheckService.STATUS_FAIL);
    }

    @Test
    void modelsListableButGenerationRefused() {
        serveModels(200);
        serveChat(403, "{\"error\":{\"message\":\"generation not allowed\"}}");

        CapabilityCheckReport report = service.checkProfile("profile-1", 1, false);

        assertThat(probeStatus(report, "reachability")).isEqualTo(AiCapabilityCheckService.STATUS_PASS);
        assertThat(probeStatus(report, "text")).isEqualTo(AiCapabilityCheckService.STATUS_FAIL);
        assertThat(probeDetail(report, "text")).contains("http 403");
        assertThat(report.overall()).isEqualTo(AiCapabilityCheckService.STATUS_FAIL);
    }

    @Test
    void reportGoesStaleWhenProfileChangesOrTtlExpires() {
        serveModels(200);
        serveChat(200, chatCompletion("req-ok", "ok"));

        CapabilityCheckReport report = service.checkProfile("profile-1", 1, false);
        String originalHash = report.configHash();

        // 同 config：TTL 内仍有效；TTL 后过期。
        assertThat(report.isFreshAt(report.checkedAt().plusSeconds(10), originalHash)).isTrue();
        assertThat(report.isFreshAt(report.checkedAt().plusSeconds(3_601), originalHash)).isFalse();

        // profile 行更新（能力集变化 ⇒ config hash 变化）⇒ 旧报告过期，且不再等价于当前档案。
        when(jdbc.queryForList(anyString(), eq("profile-1"), eq(1)))
            .thenReturn(List.of(profileRow("{\"capabilities\":[\"TEXT\",\"JSON_OBJECT\",\"IMAGE_INPUT\"]}")));
        String changedHash = service.currentConfigHash("profile-1", 1);
        assertThat(changedHash).isNotEqualTo(originalHash);
        assertThat(report.isFreshAt(Instant.now(), changedHash)).isFalse();
    }

    // ---------------------------------------------------------------- 业务探针与状态语义

    @Test
    void taskJsonProbeRequiresTwoEvidenceTaggedPointsAndRecordsTheMode() {
        serveModels(200);
        serveChat(200, chatCompletion("req-biz",
            "{\"items\":["
                + "{\"title\":\"重命名成功\",\"steps\":\"输入新名称并保存\",\"expectedResult\":\"列表显示新名称\",\"evidenceIds\":[\"probe-evidence-1\"]},"
                + "{\"title\":\"重命名被拒\",\"steps\":\"输入非法名称并保存\",\"expectedResult\":\"显示校验提示\",\"evidenceIds\":[\"probe-evidence-2\"]}"
                + "]}"));

        CapabilityCheckReport report = service.checkProfile("profile-1", 1, false);

        assertThat(probeStatus(report, "task-json")).isEqualTo(AiCapabilityCheckService.STATUS_PASS);
        assertThat(probeDetail(report, "task-json")).contains("json_mode");
        assertThat(report.overall()).isEqualTo(AiCapabilityCheckService.STATUS_PASS);
    }

    @Test
    void singlePointOrUnknownEvidenceIdFailsTheBusinessProbe() {
        serveModels(200);
        serveChat(200, chatCompletion("req-biz-bad",
            "{\"items\":[{\"title\":\"只有一个点\",\"steps\":[\"s\"],\"expectedResult\":\"x\",\"evidenceIds\":[\"forged-evidence\"]}]}"));

        CapabilityCheckReport report = service.checkProfile("profile-1", 1, false);

        assertThat(probeStatus(report, "task-json")).isEqualTo(AiCapabilityCheckService.STATUS_FAIL);
        assertThat(probeDetail(report, "task-json")).contains("expected exactly two test points");
    }

    @Test
    void imageAndToolProbesAreHonestlyUnsupportedBeforeTheT06Runtime() {
        serveModels(200);
        serveChat(200, chatCompletion("req-x", "ok"));

        CapabilityCheckReport report = service.checkProfile("profile-1", 1, false);

        assertThat(probeStatus(report, "image")).isEqualTo(AiCapabilityCheckService.STATUS_UNSUPPORTED);
        assertThat(probeStatus(report, "read-only-tool")).isEqualTo(AiCapabilityCheckService.STATUS_UNSUPPORTED);
    }

    @Test
    void unregisteredProfileNeverProbesAgainstAGuessedUrl() {
        when(jdbc.queryForList(anyString(), eq("missing-profile"), eq(1)))
            .thenReturn(List.of());

        // profile 行不存在 ⇒ 在发出任何请求之前即拒绝（loadProfileRow 先于全部探针执行）。
        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.checkProfile("missing-profile", 1, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("profile not registered");
    }

    private String probeStatus(CapabilityCheckReport report, String probe) {
        return report.probes().stream().filter(p -> p.probe().equals(probe)).findFirst().orElseThrow().status();
    }

    private String probeDetail(CapabilityCheckReport report, String probe) {
        return report.probes().stream().filter(p -> p.probe().equals(probe)).findFirst().orElseThrow().detail();
    }

    // ---------------------------------------------------------------- 持久化与陈旧判定

    @Test
    void persistedReportRoundTripsThroughVerificationJson() throws Exception {
        serveModels(200);
        serveChat(200, chatCompletion("req-persist",
            "{\"items\":["
                + "{\"title\":\"a\",\"steps\":\"s\",\"expectedResult\":\"e\",\"evidenceIds\":[\"probe-evidence-1\"]},"
                + "{\"title\":\"b\",\"steps\":\"s\",\"expectedResult\":\"e\",\"evidenceIds\":[\"probe-evidence-2\"]}"
                + "]}"));

        org.mockito.Mockito.when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(1);
        service.checkProfile("profile-1", 1, true);

        org.mockito.ArgumentCaptor<String> jsonCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(jdbc).update(anyString(),
            jsonCaptor.capture(), org.mockito.ArgumentMatchers.any(java.sql.Timestamp.class),
            eq("profile-1"), eq(1));

        // 捕获到的 verification_json 必须能被读回（时间戳序列化/反序列化往返成立）且判为新鲜。
        when(jdbc.queryForList(anyString(), eq("profile-1"), eq(1)))
            .thenReturn(List.of(storedRow(jsonCaptor.getValue())));
        AiCapabilityCheckService.StoredCapabilityCheck stored = service.storedReport("profile-1", 1);
        assertThat(stored.report()).isNotNull();
        assertThat(stored.fresh()).isTrue();
        assertThat(stored.report().overall()).isEqualTo(AiCapabilityCheckService.STATUS_PASS);
    }

    @Test
    void storedReportIsNotFreshWhenCapabilitiesChangedOrVerificationMalformed() {
        Map<String, Object> malformed = storedRow("not-json");
        when(jdbc.queryForList(anyString(), eq("profile-1"), eq(1))).thenReturn(List.of(malformed));
        assertThat(service.storedReport("profile-1", 1).fresh()).isFalse();
        assertThat(service.storedReport("profile-1", 1).report()).isNull();

        // 能力集变化 ⇒ 当前 hash 与报告内 hash 不同 ⇒ 需要重新验证。
        CapabilityCheckReport report = new CapabilityCheckReport(
            "profile-1", 1, "hash-old", Instant.now(), 3_600, AiCapabilityCheckService.STATUS_PASS, List.of());
        String changedJson;
        try {
            changedJson = objectMapper.writeValueAsString(report);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        when(jdbc.queryForList(anyString(), eq("profile-1"), eq(1)))
            .thenReturn(List.of(profileRowWithVerification("{\"capabilities\":[\"TEXT\",\"JSON_OBJECT\",\"IMAGE_INPUT\"]}", changedJson)));
        AiCapabilityCheckService.StoredCapabilityCheck stored = service.storedReport("profile-1", 1);
        assertThat(stored.report()).isNotNull();
        assertThat(stored.fresh()).isFalse();
    }

    private Map<String, Object> storedRow(String verificationJson) {
        Map<String, Object> row = profileRow("{\"capabilities\":[\"TEXT\",\"JSON_OBJECT\"]}");
        row.put("verification_json", verificationJson);
        return row;
    }

    private Map<String, Object> profileRowWithVerification(String capabilitiesJson, String verificationJson) {
        Map<String, Object> row = profileRow(capabilitiesJson);
        row.put("verification_json", verificationJson);
        return row;
    }

    // ---------------------------------------------------------------- 复核轮修复（F1–F6）

    @Test
    void reachabilityAloneCanNeverRaiseOverallToPass() throws Exception {
        // F1：GET /models 200 但 chat 连接被服务端中断 ⇒ 生成探针 unknown，整体必须为 unknown（不是 pass）。
        serveModels(200);
        server.createContext("/v1/chat/completions", exchange -> {
            // 不发任何响应头直接断开：传输层异常 ⇒ text/task-json 记 unknown。
            exchange.close();
        });

        CapabilityCheckReport report = service.checkProfile("profile-1", 1, false);

        assertThat(probeStatus(report, "reachability")).isEqualTo(AiCapabilityCheckService.STATUS_PASS);
        assertThat(probeStatus(report, "text")).isEqualTo(AiCapabilityCheckService.STATUS_UNKNOWN);
        assertThat(report.overall()).isEqualTo(AiCapabilityCheckService.STATUS_UNKNOWN);
    }

    @Test
    void nonJsonResponseBodyFailsTheBusinessProbe() {
        // F1 配套：有响应体但不是合法 JSON ⇒ FAIL（真实负面信号），不是 unknown。
        serveModels(200);
        serveChat(200, chatCompletion("req-html", "<html><body>gateway error</body></html>"));

        CapabilityCheckReport report = service.checkProfile("profile-1", 1, false);

        assertThat(probeStatus(report, "task-json")).isEqualTo(AiCapabilityCheckService.STATUS_FAIL);
        assertThat(probeDetail(report, "task-json")).contains("not valid JSON");
        assertThat(report.overall()).isEqualTo(AiCapabilityCheckService.STATUS_FAIL);
    }

    @Test
    void structurallyIncompleteStoredReportIsTreatedAsNeedingReverification() {
        // F2：可解析但缺必需字段（如仅 profileId）⇒ report=null / fresh=false，绝不 NPE。
        when(jdbc.queryForList(anyString(), eq("profile-1"), eq(1)))
            .thenReturn(List.of(storedRow("{\"profileId\":\"profile-1\"}")));

        AiCapabilityCheckService.StoredCapabilityCheck stored = service.storedReport("profile-1", 1);

        assertThat(stored.report()).isNull();
        assertThat(stored.fresh()).isFalse();
    }

    @Test
    void nativeSchemaCapabilityActuallySendsTheContractSchema() throws Exception {
        // F3：声明 JSON_SCHEMA 的档案，请求体必须带 response_format.json_schema（真的走原生路径）。
        serveModels(200);
        java.util.concurrent.atomic.AtomicReference<String> requestBody = new java.util.concurrent.atomic.AtomicReference<>("");
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] bytes = chatCompletion("req-native",
                "{\"items\":["
                    + "{\"title\":\"a\",\"steps\":\"s\",\"expectedResult\":\"e\",\"evidenceIds\":[\"probe-evidence-1\"]},"
                    + "{\"title\":\"b\",\"steps\":\"s\",\"expectedResult\":\"e\",\"evidenceIds\":[\"probe-evidence-2\"]}"
                    + "]}").getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        when(jdbc.queryForList(anyString(), eq("profile-1"), eq(1)))
            .thenReturn(List.of(profileRow("{\"capabilities\":[\"TEXT\",\"JSON_SCHEMA\"]}")));
        CapabilityCheckReport report = service.checkProfile("profile-1", 1, false);

        assertThat(probeStatus(report, "task-json")).isEqualTo(AiCapabilityCheckService.STATUS_PASS);
        assertThat(probeDetail(report, "task-json")).contains("native_schema").contains("provider_request_id=req-native");
        assertThat(requestBody.get()).contains("\"response_format\"").contains("\"json_schema\"");
    }

    @Test
    void declaredButUnwiredCapabilitiesAreUnknownNotUnsupported() {
        // F4：声明了 IMAGE_INPUT/TOOL_CALLS 但 transport 未接通 ⇒ 未验证（unknown）。
        serveModels(200);
        serveChat(200, chatCompletion("req-x", "ok"));
        when(jdbc.queryForList(anyString(), eq("profile-1"), eq(1)))
            .thenReturn(List.of(profileRow("{\"capabilities\":[\"TEXT\",\"JSON_OBJECT\",\"IMAGE_INPUT\",\"TOOL_CALLS\"]}")));

        CapabilityCheckReport report = service.checkProfile("profile-1", 1, false);

        assertThat(probeStatus(report, "image")).isEqualTo(AiCapabilityCheckService.STATUS_UNKNOWN);
        assertThat(probeStatus(report, "read-only-tool")).isEqualTo(AiCapabilityCheckService.STATUS_UNKNOWN);
    }

    @Test
    void credentialChangeInvalidatesTheStoredReport() {
        // F5：凭据引用（Authorization header 材料）变化 ⇒ config hash 变化 ⇒ 旧报告过期。
        serveModels(200);
        serveChat(200, chatCompletion("req-c", "ok"));
        String originalHash = service.currentConfigHash("profile-1", 1);

        Map<String, Object> rotated = profileRow("{\"capabilities\":[\"TEXT\",\"JSON_OBJECT\"]}");
        rotated.put("credential_ref", "rotated-key");
        when(jdbc.queryForList(anyString(), eq("profile-1"), eq(1))).thenReturn(List.of(rotated));

        assertThat(service.currentConfigHash("profile-1", 1)).isNotEqualTo(originalHash);
    }

    @Test
    void persistRequiresExactlyOneUpdatedRow() {
        // F6：update 影响 0 行时不得静默报成功。
        serveModels(200);
        serveChat(200, chatCompletion("req-f", "ok"));
        when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(0);

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.checkProfile("profile-1", 1, true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not persisted");
    }

    @Test
    void pathologicalStoredTtlNeitherThrowsNorOverflows() {
        // F2b：存储数据不可信——ttlSeconds=Long.MAX 绝不因 plusSeconds 溢出而 500；视为永不过期。
        CapabilityCheckReport hugeTtl = new CapabilityCheckReport(
            "profile-1", 1, service.currentConfigHash("profile-1", 1), Instant.now(), Long.MAX_VALUE,
            AiCapabilityCheckService.STATUS_PASS, List.of());
        String json;
        try {
            json = objectMapper.writeValueAsString(hugeTtl);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
        when(jdbc.queryForList(anyString(), eq("profile-1"), eq(1)))
            .thenReturn(List.of(storedRow(json)));

        AiCapabilityCheckService.StoredCapabilityCheck stored = service.storedReport("profile-1", 1);

        assertThat(stored.report()).isNotNull();
        assertThat(stored.fresh()).isTrue();
    }

    // ---------------------------------------------------------------- T05 复核修复（R2-3/R2-1）

    @Test
    void unresolvableCredentialRefIsRejectedBeforeAnyProbe() {
        // R2-3：占位符引用（解密结果等于引用本身）⇒ 拒绝检查，绝不把占位符当 Bearer 发往端点。
        var cipher = mock(com.haitlify.aitest.platform.security.SecretCipher.class);
        when(cipher.decrypt(anyString())).thenReturn("placeholder-ref");
        var strictService = new AiCapabilityCheckService(
            jdbc, objectMapper, new AiProtocolTransportClient(HttpClient.newHttpClient(), objectMapper),
            cipher, HttpClient.newHttpClient(), 3_600);
        when(jdbc.queryForList(anyString(), eq("profile-1"), eq(1)))
            .thenReturn(List.of(profileRowWithCredential("{\"capabilities\":[\"TEXT\"]}", "placeholder-ref")));

        org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> strictService.checkProfile("profile-1", 1, false))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("credential unresolved");
    }

    @Test
    void bareArrayCapabilitiesFromTheProductionWriterAreParsed() {
        // R2-1：生产写入方写裸数组 ["TEXT","JSON_OBJECT"]；解析必须兼容（否则能力筛选在 live 数据上停用）。
        serveModels(200);
        serveChat(200, chatCompletion("req-bare",
            "{\"items\":["
                + "{\"title\":\"a\",\"steps\":\"s\",\"expectedResult\":\"e\",\"evidenceIds\":[\"probe-evidence-1\"]},"
                + "{\"title\":\"b\",\"steps\":\"s\",\"expectedResult\":\"e\",\"evidenceIds\":[\"probe-evidence-2\"]}"
                + "]}"));
        when(jdbc.queryForList(anyString(), eq("profile-1"), eq(1)))
            .thenReturn(List.of(profileRow("[\"TEXT\",\"JSON_OBJECT\"]")));

        CapabilityCheckReport report = service.checkProfile("profile-1", 1, false);

        assertThat(probeStatus(report, "task-json")).isEqualTo(AiCapabilityCheckService.STATUS_PASS);
        assertThat(probeDetail(report, "task-json")).contains("json_mode");
    }

    private Map<String, Object> profileRowWithCredential(String capabilitiesJson, String credentialRef) {
        Map<String, Object> row = profileRow(capabilitiesJson);
        row.put("credential_ref", credentialRef);
        return row;
    }

    @Test
    void streamingIsNeverReportedAsSupportedBeforeTheT02ConsumerExists() {
        // 未声明 streaming：UNSUPPORTED（与 image/tool 同族语义）。
        serveModels(200);
        serveChat(200, chatCompletion("req-stream-1", "ok"));
        CapabilityCheckReport undeclared = service.checkProfile("profile-1", 1, false);
        assertThat(probeStatus(undeclared, "streaming")).isEqualTo(AiCapabilityCheckService.STATUS_UNSUPPORTED);

        // 声明 streaming：一律 UNKNOWN（未验证）——非流式探针通过也不得把它抬成 pass/supported。
        when(jdbc.queryForList(anyString(), eq("profile-1"), eq(1)))
            .thenReturn(List.of(profileRow("{\"capabilities\":[\"TEXT\",\"JSON_OBJECT\",\"STREAMING\"]}")));
        CapabilityCheckReport declared = service.checkProfile("profile-1", 1, false);
        assertThat(probeStatus(declared, "streaming")).isEqualTo(AiCapabilityCheckService.STATUS_UNKNOWN);
        assertThat(probeDetail(declared, "streaming")).contains("not verified");
    }

}
