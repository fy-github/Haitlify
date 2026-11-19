package com.haitlify.aitest.platform.assistant.dispatch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

import com.haitlify.aitest.platform.webautomation.WebAutomationProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Runner 的最小客户端（A1 链路：提交运行 / 查询结果）。只做两件事，避免把 Runner 的
 * 业务语义复制到平台侧：{@code POST /runs} 返回 runId，{@code GET /runs/{id}} 返回原始状态。
 *
 * <p>地址复用 {@code app.web-automation.runner.base-url}（默认 http://127.0.0.1:9323），
 * 不自建第二份配置。
 */
@Component
public class AssistantRunnerGateway {

    public record RunCreated(String runId) {}

    /** Runner 的 summary：completed 只表示「跑完了」，成败要看 failed/error 计数。 */
    public record RunSnapshot(String status, int total, int passed, int failed, int error, String rawJson) {}

    private final WebAutomationProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AssistantRunnerGateway(WebAutomationProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public String baseUrl() {
        return properties.runner().baseUrl();
    }

    /** 提交运行；非 2xx 或缺少 runId 都视为失败（宁可 UNKNOWN，不伪造成功）。 */
    public RunCreated createRun(String payloadJson) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/runs"))
            .timeout(Duration.ofMillis(properties.runner().timeoutMs()))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Runner 拒绝运行：HTTP " + response.statusCode() + " " + truncate(response.body()));
        }
        JsonNode body = objectMapper.readTree(response.body());
        String runId = body.path("runId").asString("");
        if (runId.isBlank()) {
            runId = body.path("id").asString("");
        }
        if (runId.isBlank()) {
            throw new IOException("Runner 响应缺少 runId：" + truncate(response.body()));
        }
        return new RunCreated(runId);
    }

    /** Runner 已经没有这个 run（回收/重启）：调用方据此判断「run 丢失」，而不是当成普通失败。 */
    public static class RunNotFoundException extends IOException {
        public RunNotFoundException(String message) {
            super(message);
        }
    }

    /** 停止一个已提交的 run；非 2xx 直接失败（不允许「账面取消、实际还在跑」）。 */
    public void stopRun(String runId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/runs/" + runId + "/stop"))
            .timeout(Duration.ofMillis(properties.runner().timeoutMs()))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            throw new RunNotFoundException("Runner 中已不存在该 run：" + runId);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Runner 停止失败：HTTP " + response.statusCode() + " " + truncate(response.body()));
        }
    }

    public RunSnapshot fetchRun(String runId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/runs/" + runId))
            .timeout(Duration.ofMillis(properties.runner().timeoutMs()))
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Runner 查询失败：HTTP " + response.statusCode() + " " + truncate(response.body()));
        }
        JsonNode body = objectMapper.readTree(response.body());
        String status = body.path("status").asString("");
        JsonNode summary = body.path("summary");
        int total = summary.path("total").asInt(0);
        int passed = summary.path("passed").asInt(0);
        int failed = summary.path("failed").asInt(0);
        int error = summary.path("error").asInt(0);
        return new RunSnapshot(status, total, passed, failed, error, response.body());
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "…";
    }
}
