package com.haitlify.aitest.platform.webautomation;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.web-automation.runner.base-url=http://127.0.0.1:19323")
class WebAutomationRunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void mirrorsAsyncRunLifecycleAndBrowserCapabilities() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 19323), 0);
        server.createContext("/", exchange -> handleRequest(exchange, calls));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            mockMvc.perform(post("/api/web-automation/runs")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(runRequestJson()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.status").value("queued"));

            mockMvc.perform(get("/api/web-automation/runs/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.progress.totalCases").value(1))
                .andExpect(jsonPath("$.currentStep.id").value("step-1"))
                .andExpect(jsonPath("$.latestScreenshotUrl").value("http://127.0.0.1:9323/artifacts/run-1.png"));

            mockMvc.perform(post("/api/web-automation/runs/run-1/pause"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("pause_requested"));
            mockMvc.perform(post("/api/web-automation/runs/run-1/resume"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"));
            mockMvc.perform(post("/api/web-automation/runs/run-1/stop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("stop_requested"));

            mockMvc.perform(get("/api/web-automation/capabilities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.browsers[0].id").value("chromium"))
                .andExpect(jsonPath("$.browsers[1].available").value(false));

            mockMvc.perform(post("/api/web-automation/login-discovery")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        { "url": "https://example.com/login", "browser": "chromium", "viewport": "1440x900" }
                        """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.discoveryId").value("discovery-1"));
            mockMvc.perform(get("/api/web-automation/login-discovery/discovery-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommended.password.locator").value("css=#password"));
            mockMvc.perform(post("/api/web-automation/login-discovery/discovery-1/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "usernameCandidateId": "username-1",
                          "passwordCandidateId": "password-1",
                          "submitCandidateId": "submit-1"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.confirmed.usernameLocator").value("css=#username"));

            assertTrue(calls.contains("POST /runs"));
            assertTrue(calls.contains("GET /runs/run-1"));
            assertTrue(calls.contains("POST /runs/run-1/pause"));
            assertTrue(calls.contains("POST /runs/run-1/resume"));
            assertTrue(calls.contains("POST /runs/run-1/stop"));
            assertTrue(calls.contains("GET /capabilities"));
            assertTrue(calls.contains("POST /login-discovery"));
            assertTrue(calls.contains("GET /login-discovery/discovery-1"));
            assertTrue(calls.contains("POST /login-discovery/discovery-1/confirm"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsSafeTakeoverRecommendationsForAuthLossAndCaptchaFailures() throws Exception {
        java.util.concurrent.atomic.AtomicInteger invocation = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 19323), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestMethod().equals("GET") && exchange.getRequestURI().getPath().equals("/capabilities")) {
                int index = invocation.getAndIncrement();
                if (index == 0) {
                    respond(exchange, 503, """
                        {
                          "message": "login failed token=secret locator=button[data-token=\\"secret\\"]",
                          "category": "login_failed",
                          "handoff": { "required": true, "mode": "manual", "reason": "login_failed", "resumeSupported": false },
                          "retryable": false
                        }
                        """);
                    return;
                }
                if (index == 1) {
                    respond(exchange, 503, """
                        {
                          "message": "auth context lost token=secret locator=button[data-token=\\"secret\\"]",
                          "category": "auth_context_lost",
                          "handoff": { "required": true, "mode": "manual", "reason": "auth_context_lost", "resumeSupported": false },
                          "retryable": false
                        }
                        """);
                    return;
                }
                if (index == 2) {
                    respond(exchange, 503, """
                        {
                          "message": "captcha required token=secret locator=button[data-token=\\"secret\\"]",
                          "category": "captcha_required",
                          "handoff": { "required": true, "mode": "manual", "reason": "captcha_required", "resumeSupported": false },
                          "retryable": false
                        }
                        """);
                    return;
                }
                if (index == 3) {
                    respond(exchange, 503, """
                        {
                          "message": "manual review required token=secret locator=button[data-token=\\"secret\\"]",
                          "category": "manual_required",
                          "handoff": { "required": true, "mode": "manual", "reason": "manual_required", "resumeSupported": false },
                          "retryable": false
                        }
                        """);
                    return;
                }
                if (index == 4) {
                    respond(exchange, 503, """
                        {
                          "message": "captcha required",
                          "category": "captcha_required",
                          "handoff": { "required": true, "mode": "manual", "reason": "captcha_required", "resumeSupported": false },
                          "network": {
                            "criticalBlocked": [],
                            "optionalBlocked": [{ "origin": "https://o.alicdn.com", "path": "/captcha-frontend/aliyunCaptcha/AliyunCaptcha.js", "resourceType": "script", "criticality": "optional", "failureType": "network_blocked" }],
                            "unknownBlocked": [{ "origin": "https://api.app-dev.example.test", "path": "/v4/uc/n/auth/login", "resourceType": "xhr", "criticality": "unknown", "failureType": "network_blocked" }]
                          },
                          "retryable": false
                        }
                        """);
                    return;
                }
                respond(exchange, 503, """
                    {
                      "message": "manual review required token=secret locator=button[data-token=\\"secret\\"]",
                      "category": "manual_required",
                      "handoff": { "required": true, "mode": "manual", "reason": "manual_required", "resumeSupported": false },
                      "retryable": false
                    }
                    """);
                return;
            }
            respond(exchange, 404, "{ \"message\": \"not found\" }");
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        try {
            mockMvc.perform(get("/api/web-automation/capabilities"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.recommendations[0]").value("先重新登录并恢复同一会话，再继续当前操作。"))
                .andExpect(jsonPath("$.recommendations[1]").value("如果页面仍停留在登录态，请切回原业务页后重试。"));

            mockMvc.perform(get("/api/web-automation/capabilities"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.recommendations[0]").value("先重新登录并恢复同一会话，再继续当前操作。"))
                .andExpect(jsonPath("$.recommendations[1]").value("如果页面仍停留在登录态，请切回原业务页后重试。"));

            mockMvc.perform(get("/api/web-automation/capabilities"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.recommendations[0]").value("先完成验证码验证，再继续当前流程。"))
                .andExpect(jsonPath("$.recommendations[1]").value("如果验证码持续拦截，切换为人工接管。"));

            mockMvc.perform(get("/api/web-automation/capabilities"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.recommendations[0]").value("当前页面需要人工接管后继续。"))
                .andExpect(jsonPath("$.recommendations[1]").value("若页面出现遮罩、验证码或登录态变化，请先恢复可操作状态。"));

            // 验证码 + 网络白名单拦截：建议里必须点名被拦来源（用户实测的真因不可见问题）。
            mockMvc.perform(get("/api/web-automation/capabilities"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.recommendations[0]").value(org.hamcrest.Matchers.containsString("可能被网络白名单拦截")))
                .andExpect(jsonPath("$.recommendations[0]").value(org.hamcrest.Matchers.containsString("https://api.app-dev.example.test/v4/uc/n/auth/login")))
                .andExpect(jsonPath("$.recommendations[0]").value(org.hamcrest.Matchers.containsString("认证与放行白名单")))
                .andExpect(jsonPath("$.recommendations[1]").value("先完成验证码验证，再继续当前流程。"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void resumesEvidenceHandoffsThroughTheRunnerWithOpaqueIds() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 19323), 0);
        server.createContext("/", exchange -> {
            String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            calls.add(call);
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (call.equals("POST /page-locator-discovery")) {
                assertTrue(body.contains("\"handoffId\":\"handoff-1\""));
                assertTrue(body.contains("\"resumeFromHandoff\":true"));
                respond(exchange, 200, """
                    {
                      "pageUrl": "https://example.com/login",
                      "status": "matched",
                      "failureReason": "",
                      "sessionId": "session-1",
                      "authenticated": true,
                      "elements": [],
                      "exploration": { "status": "matched", "rounds": [], "navigationTrail": [], "durationMs": 0, "remainingDurationMs": 0, "networkBlocked": [], "network": { "criticalBlocked": [], "optionalBlocked": [], "unknownBlocked": [] }, "readiness": { "ready": true, "durationMs": 0 }, "repairTrail": [] },
                      "finalObservation": { "pageKey": "https://example.com/login" },
                      "rounds": [],
                      "historicalMatchedIds": [],
                      "historicalRejectedIds": []
                    }
                    """);
                return;
            }
            respond(exchange, 404, "{ \"message\": \"not found\" }");
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
        try {
            mockMvc.perform(post("/api/web-automation/evidence/handoff-1/resume")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                        {
                          "url": "https://example.com/login",
                          "baseUrl": "https://example.com",
                          "browser": "chromium",
                          "viewport": "1440x900",
                          "headless": true,
                          "timeoutMs": 20000,
                          "waitUntil": "domcontentloaded"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("session-1"))
                .andExpect(jsonPath("$.authenticated").value(true));

            assertTrue(calls.contains("POST /page-locator-discovery"));
        } finally {
            server.stop(0);
        }
    }

    private void handleRequest(HttpExchange exchange, List<String> calls) throws java.io.IOException {
        String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        calls.add(call);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (call.equals("POST /runs")) {
            assertTrue(body.contains("\"browserOverride\":\"firefox\""));
            assertTrue(body.contains("\"authToken\":\"token-1\""));
            assertTrue(body.contains("\"authenticationMode\":\"environment\""));
            respond(exchange, 202, snapshot("queued"));
            return;
        }
        if (call.equals("GET /capabilities")) {
            respond(exchange, 200, """
                {
                  "status": "UP",
                  "checkedAt": "2026-07-13T12:00:00.000Z",
                  "browsers": [
                    { "id": "chromium", "label": "Chromium", "available": true, "recommendation": "" },
                    { "id": "firefox", "label": "Firefox", "available": false, "recommendation": "install firefox" }
                  ]
                }
                """);
            return;
        }
        if (call.startsWith("POST /login-discovery") || call.startsWith("GET /login-discovery")) {
            respond(exchange, call.equals("POST /login-discovery") ? 202 : 200, loginDiscoverySnapshot(call.endsWith("/confirm")));
            return;
        }
        if (call.equals("POST /page-locator-discovery")) {
            assertTrue(body.contains("\"handoffId\":\"handoff-1\"") || body.contains("\"resumeFromHandoff\":true"));
            respond(exchange, 200, """
                {
                  "pageUrl": "https://example.com/login",
                  "status": "matched",
                  "failureReason": "",
                  "sessionId": "session-1",
                  "authenticated": true,
                  "elements": [],
                  "exploration": { "status": "matched", "rounds": [], "navigationTrail": [], "durationMs": 0, "remainingDurationMs": 0, "networkBlocked": [], "network": { "criticalBlocked": [], "optionalBlocked": [], "unknownBlocked": [] }, "readiness": { "ready": true, "durationMs": 0 }, "repairTrail": [] },
                  "finalObservation": { "pageKey": "https://example.com/login" },
                  "rounds": [],
                  "historicalMatchedIds": [],
                  "historicalRejectedIds": []
                }
                """);
            return;
        }
        String status = call.endsWith("/pause") ? "pause_requested"
            : call.endsWith("/stop") ? "stop_requested"
            : "running";
        respond(exchange, 200, snapshot(status));
    }

    private String snapshot(String runStatus) {
        return """
            {
              "runId": "run-1",
              "status": "%s",
              "mode": "sequential",
              "selectedBrowser": "firefox",
              "createdAt": "2026-07-13T12:00:00.000Z",
              "startedAt": "2026-07-13T12:00:01.000Z",
              "updatedAt": "2026-07-13T12:00:02.000Z",
              "progress": { "totalCases": 1, "completedCases": 0, "totalSteps": 3, "completedSteps": 1 },
              "currentCase": { "id": "WEB-1", "name": "登录" },
              "currentStep": { "id": "step-1", "name": "打开登录页", "action": "goto", "phase": "setup" },
              "currentPageUrl": "https://example.com/login",
              "latestScreenshotUrl": "http://127.0.0.1:9323/artifacts/run-1.png",
              "latestScreenshotAt": "2026-07-13T12:00:02.000Z",
              "controls": { "canPause": true, "canResume": false, "canStop": true },
              "summary": { "total": 0, "passed": 0, "failed": 0, "error": 0 },
              "stepLogs": [],
              "results": [],
              "diagnostics": []
            }
            """.formatted(runStatus);
    }

    private String runRequestJson() {
        return """
            {
              "mode": "sequential",
              "loopCount": 1,
              "browserOverride": "firefox",
              "runtime": {
                "baseUrl": "https://haitlify.example.test",
                "authToken": "token-1"
              },
              "cases": [
                {
                  "id": "WEB-1",
                  "name": "登录",
                  "browser": "chromium",
                  "authenticationMode": "environment",
                  "steps": [
                    { "id": "step-1", "name": "打开登录页", "action": "goto", "targetType": "url", "target": "/login" }
                  ]
                }
              ]
            }
            """;
    }

    private String loginDiscoverySnapshot(boolean confirmed) {
        return """
            {
              "discoveryId": "discovery-1",
              "status": "completed",
              "createdAt": "2026-07-15T00:00:00.000Z",
              "pageUrl": "https://example.com/login",
              "screenshotUrl": "http://127.0.0.1:9323/artifacts/login.png",
              "candidates": {
                "username": [{ "id": "username-1", "role": "username", "locator": "css=#username", "strategy": "css", "label": "账号", "confidence": 98, "reason": "账号语义匹配" }],
                "password": [{ "id": "password-1", "role": "password", "locator": "css=#password", "strategy": "css", "label": "密码", "confidence": 100, "reason": "type=password" }],
                "submit": [{ "id": "submit-1", "role": "submit", "locator": "role=button[name=登录]", "strategy": "role", "label": "登录", "confidence": 96, "reason": "登录语义匹配" }]
              },
              "recommended": {
                "username": { "id": "username-1", "role": "username", "locator": "css=#username", "strategy": "css", "label": "账号", "confidence": 98, "reason": "账号语义匹配" },
                "password": { "id": "password-1", "role": "password", "locator": "css=#password", "strategy": "css", "label": "密码", "confidence": 100, "reason": "type=password" },
                "submit": { "id": "submit-1", "role": "submit", "locator": "role=button[name=登录]", "strategy": "role", "label": "登录", "confidence": 96, "reason": "登录语义匹配" }
              },
              "diagnostics": []%s
            }
            """.formatted(
                confirmed
                    ? ", \"confirmed\": { \"usernameLocator\": \"css=#username\", \"passwordLocator\": \"css=#password\", \"submitLocator\": \"role=button[name=登录]\" }"
                    : ""
            );
    }

    private void respond(HttpExchange exchange, int statusCode, String body) throws java.io.IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
