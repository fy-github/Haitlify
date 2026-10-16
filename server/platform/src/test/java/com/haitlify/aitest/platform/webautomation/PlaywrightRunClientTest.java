package com.haitlify.aitest.platform.webautomation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class PlaywrightRunClientTest {

    @Test
    void forwardsCreateQueryControlAndCapabilityRequests() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> handleRequest(exchange, calls));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            PlaywrightRunClient client = client(true, server.getAddress().getPort(), 5_000);
            WebAutomationRunRequest request = new WebAutomationRunRequest(
                "sequential",
                1,
                "firefox",
                new ObjectMapper().readTree("""
                    { "baseUrl": "https://example.com", "authToken": "token-1" }
                    """),
                List.of(new WebAutomationRunCaseRequest(
                    "WEB-1", null, "Haitlify", "登录", "登录页", "登录", "/login",
                    "chromium", "1440x900", 1, "passed", "environment",
                    List.of(new WebAutomationStepRequest(
                        "step-1", "打开登录页", "goto", "url", "/login", null, 8_000, "always"
                    ))
                )),
                null
            );

            WebAutomationRunSnapshotResponse created = client.createRun(request);
            assertEquals("run-1", created.runId());
            assertEquals("queued", created.status());
            assertEquals("running", client.getRun("run-1").status());
            assertEquals("pause_requested", client.controlRun("run-1", "pause").status());
            assertEquals("running", client.controlRun("run-1", "resume").status());
            assertEquals("stop_requested", client.controlRun("run-1", "stop").status());
            WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse resumed = client.resumeEvidenceHandoff(
                "handoff-1",
                new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest(
                    "https://example.com/login",
                    "https://example.com",
                    "chromium",
                    "1440x900",
                    true,
                    20_000,
                    "domcontentloaded",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
                )
            );
            assertEquals("session-1", resumed.sessionId());
            WebAutomationCapabilitiesResponse capabilities = client.capabilities();
            assertEquals("chromium", capabilities.browsers().getFirst().id());
            assertTrue(capabilities.browsers().getFirst().available());
            WebAutomationSessionListResponse sessions = client.listSessions();
            assertEquals(1, sessions.sessions().size());
            assertEquals("session-1", sessions.sessions().getFirst().sessionId());
            assertFalse(sessions.sessions().getFirst().leased());

            assertEquals(List.of(
                "POST /runs",
                "GET /runs/run-1",
                "POST /runs/run-1/pause",
                "POST /runs/run-1/resume",
                "POST /runs/run-1/stop",
                "POST /page-locator-discovery",
                "GET /capabilities",
                "GET /sessions"
            ), calls);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesStructuredEvidenceFailuresWhenResumingEvidenceHandoffs() throws Exception {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page-locator-discovery", exchange -> respond(exchange, 503, """
            {
              "message": "manual handoff required",
              "category": "manual_review_required",
              "handoff": { "required": true, "mode": "manual", "reason": "manual_review_required", "resumeSupported": false },
              "retryable": false
            }
            """));
        server.start();

        try {
            WebAutomationEvidenceException error = assertThrows(
                WebAutomationEvidenceException.class,
                () -> client(true, server.getAddress().getPort(), 5_000).resumeEvidenceHandoff(
                    "handoff-1",
                    new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest(
                        "https://example.com/login",
                        "https://example.com",
                        "chromium",
                        "1440x900",
                        true,
                        20_000,
                        "domcontentloaded",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                    )
                )
            );
            assertEquals(422, error.statusCode());
            assertEquals("manual_review_required", error.category());
            assertTrue(error.details().path("handoff").path("required").asBoolean(false));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsCommandsWhenRunnerIsDisabled() {
        PlaywrightRunClient client = client(false, 9323, 5_000);
        assertThrows(WebAutomationRunnerDisabledException.class, client::capabilities);
    }

    @Test
    void forwardsLoginDiscoveryCreateQueryAndConfirmRequests() throws Exception {
        List<String> calls = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            calls.add(call);
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (call.equals("POST /login-discovery")) {
                assertTrue(body.contains("\"url\":\"https://example.com/login\""));
                respond(exchange, 202, discoverySnapshot("queued", false));
                return;
            }
            if (call.endsWith("/confirm")) {
                assertTrue(body.contains("\"usernameCandidateId\":\"username-1\""));
                respond(exchange, 200, discoverySnapshot("completed", true));
                return;
            }
            respond(exchange, 200, discoverySnapshot("completed", false));
        });
        server.start();

        try {
            PlaywrightRunClient client = client(true, server.getAddress().getPort(), 5_000);
            WebLoginDiscoverySnapshotResponse created = client.createLoginDiscovery(
                new WebLoginDiscoveryRequest(
                    "https://example.com/login", "chromium", "1440x900", true, 20_000, "domcontentloaded"
                )
            );
            assertEquals("discovery-1", created.discoveryId());
            assertEquals("completed", client.getLoginDiscovery("discovery-1").status());
            WebLoginDiscoverySnapshotResponse confirmed = client.confirmLoginDiscovery(
                "discovery-1",
                new WebLoginDiscoveryConfirmRequest("username-1", "password-1", "submit-1")
            );
            assertEquals("css=#username", confirmed.confirmed().usernameLocator());
            assertEquals(List.of(
                "POST /login-discovery",
                "GET /login-discovery/discovery-1",
                "POST /login-discovery/discovery-1/confirm"
            ), calls);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesRunnerStatusAndMessageForMissingRuns() throws Exception {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/runs/missing", exchange -> respond(exchange, 404, """
            { "message": "Web automation run not found" }
            """));
        server.start();
        try {
            PlaywrightRunClient client = client(true, server.getAddress().getPort(), 5_000);
            WebAutomationRunnerUnavailableException error = assertThrows(
                WebAutomationRunnerUnavailableException.class,
                () -> client.getRun("missing")
            );
            assertEquals(404, error.statusCode());
            assertTrue(error.getMessage().contains("HTTP 404"));
            assertTrue(error.getMessage().contains("Web automation run not found"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsStructuredRunnerEvidenceFailureTo422InsteadOfUnavailable() throws Exception {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/capabilities", exchange -> respond(exchange, 503, """
            {"message":"business API blocked query=secret search=token selector=button[data-token=secret] locator=button[data-token=secret] password=secret token=secret","category":"network_blocked_critical","pageUrl":"https://user:pass@example.test/path?query=secret#/target?search=token","network":{"criticalBlocked":[{"origin":"https://user:pass@api.example.test:8443/v1?query=token","path":"/api/list?query=secret","resourceType":"xhr","criticality":"critical","failureType":"network_blocked"}]},"repairTrail":[{"category":"step-1 query=secret search=token selector=button[data-token=secret] locator=#secret password=secret token=secret","round":1,"changedFingerprint":true,"pending":false,"locator":"#secret","query":"query=token","message":"raw failure with query=token selector=button[data-token=secret]"}],"retryable":true}
            """));
        server.start();
        try {
            WebAutomationEvidenceException error = assertThrows(WebAutomationEvidenceException.class, () -> client(true, server.getAddress().getPort(), 5_000).capabilities());
            assertEquals(422, error.statusCode());
            assertEquals("network_blocked_critical", error.category());
            assertFalse(error.getMessage().contains("secret"));
            assertFalse(error.getMessage().contains("query=secret"));
            assertFalse(error.getMessage().contains("search=token"));
            assertFalse(error.getMessage().contains("button[data-token"));
            assertTrue(error.getMessage().contains("query=[redacted]"));
            assertTrue(error.getMessage().contains("search=[redacted]"));
            assertTrue(error.getMessage().contains("selector=[redacted]"));
            assertTrue(error.getMessage().contains("locator=[redacted]"));
            assertTrue(error.getMessage().contains("password=[redacted]"));
            assertTrue(error.getMessage().contains("token=[redacted]"));
            assertEquals("https://example.test/path#/target", error.details().path("pageUrl").asText());
            assertEquals("https://api.example.test:8443", error.details().path("network").path("criticalBlocked").path(0).path("origin").asText());
            assertEquals("/api/list", error.details().path("network").path("criticalBlocked").path(0).path("path").asText());
            assertTrue(error.details().toString().contains("/api/list"));
            assertTrue(!error.details().toString().contains("secret"));
            assertTrue(!error.details().toString().contains("user:pass"));
            assertEquals(1, error.details().path("repairTrail").size());
            assertTrue(error.details().path("repairTrail").path(0).path("category").asText().contains("query=[redacted]"));
            assertTrue(error.details().path("repairTrail").path(0).path("category").asText().contains("search=[redacted]"));
            assertTrue(error.details().path("repairTrail").path(0).path("category").asText().contains("selector=[redacted]"));
            assertFalse(error.details().path("repairTrail").path(0).path("category").asText().contains("secret"));
            assertFalse(error.details().toString().contains("query=token"));
            assertFalse(error.details().toString().contains("message"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsRunnerFailureCategoriesInsteadOfFoldingThemIntoUnsupportedDom() throws Exception {
        // navigation_failed / unsafe_action / locator_persistence 都是 runner 的稳定分类：
        // 折叠成 wrong_page/unsupported_dom 会说错原因，还会给错「需要人工接管」的建议。
        HttpServer navigationServer = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        navigationServer.createContext("/capabilities", exchange -> respond(exchange, 503, """
            {"message":"direct navigation must be same-origin","category":"navigation_failed","pageUrl":"https://example.test/target","retryable":false}
            """));
        navigationServer.start();

        HttpServer unsafeServer = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        unsafeServer.createContext("/capabilities", exchange -> respond(exchange, 503, """
            {"message":"unsafe action rejected","category":"unsafe_action","pageUrl":"https://example.test/target","retryable":false}
            """));
        unsafeServer.start();

        try {
            WebAutomationEvidenceException navigationError = assertThrows(
                WebAutomationEvidenceException.class,
                () -> client(true, navigationServer.getAddress().getPort(), 5_000).capabilities()
            );
            assertEquals(422, navigationError.statusCode());
            assertEquals("navigation_failed", navigationError.category());

            WebAutomationEvidenceException unsafeError = assertThrows(
                WebAutomationEvidenceException.class,
                () -> client(true, unsafeServer.getAddress().getPort(), 5_000).capabilities()
            );
            assertEquals(422, unsafeError.statusCode());
            assertEquals("unsafe_action", unsafeError.category());
        } finally {
            navigationServer.stop(0);
            unsafeServer.stop(0);
        }
    }

    @Test
    void keepsStepLevelEvidenceCategoriesInsteadOfDroppingThem() throws Exception {
        // runner 的步骤级/Action IR 分类以前不在稳定表里 → normalizeCategory 返回空串，
        // 这些取证失败会被降级成「runner 调用异常」，看不出真实原因。
        record CategoryCase(String category, String expected) { }
        for (CategoryCase item : List.of(
            new CategoryCase("unsupported_shadow_dom", "unsupported_shadow_dom"),
            new CategoryCase("unsupported_frame", "unsupported_frame"),
            new CategoryCase("unsupported_action_target", "unsupported_action_target"),
            new CategoryCase("invalid_action_ir", "invalid_action_ir"),
            new CategoryCase("action_evidence_missing", "action_evidence_missing"),
            new CategoryCase("action_evidence_mismatch", "action_evidence_mismatch"),
            new CategoryCase("assertion_failed", "assertion_failed"),
            new CategoryCase("observation_failed", "observation_failed"),
            new CategoryCase("pagination_not_changed", "pagination_not_changed"),
            new CategoryCase("action_failed", "action_failed"),
            new CategoryCase("unsupported_action", "unsupported_action"),
            new CategoryCase("navigation_failed", "navigation_failed"),
            new CategoryCase("unsafe_action", "unsafe_action"),
            new CategoryCase("locator_persistence", "locator_persistence"),
            new CategoryCase("settle_timeout", "settle_timeout"))) {
            HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/capabilities", exchange -> respond(exchange, 503, """
                {"message":"step evidence failure","category":"%s","pageUrl":"https://example.test/target","retryable":false}
                """.formatted(item.category())));
            server.start();
            try {
                WebAutomationEvidenceException error = assertThrows(
                    WebAutomationEvidenceException.class,
                    () -> client(true, server.getAddress().getPort(), 5_000).capabilities()
                );
                assertEquals(item.expected(), error.category(), "分类必须保留而不是被丢成空串：" + item.category());
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void keepsTheFailingTargetIdInEvidenceDetailsForAuthoringRetry() throws Exception {
        // action_evidence_missing 的 targetEvidenceId 决定能不能自动排除失效目标后重授权：
        // 白名单曾经把它吞掉，调用方只知道「有目标失效」而不知道是哪个（实测 2026-10-14）。
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/capabilities", exchange -> respond(exchange, 422, """
            {"message":"Action IR target element-abc was not observed in the current page","category":"action_evidence_missing","targetEvidenceId":"element-abc","matchedElementCount":0,"retryable":false}
            """));
        server.start();
        try {
            WebAutomationEvidenceException error = assertThrows(
                WebAutomationEvidenceException.class,
                () -> client(true, server.getAddress().getPort(), 5_000).capabilities()
            );
            assertEquals("action_evidence_missing", error.category());
            assertEquals("element-abc", error.details().path("targetEvidenceId").asText(""));
            assertEquals(0, error.details().path("matchedElementCount").asInt(-1));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsCaptchaAndManualCategoriesToStableEvidenceExceptions() throws Exception {
        HttpServer captchaServer = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        captchaServer.createContext("/capabilities", exchange -> respond(exchange, 503, """
            {"message":"captcha challenge detected","category":"captcha_required","pageUrl":"https://example.test/login","handoff":{"required":true,"mode":"manual","reason":"captcha_required","resumeSupported":false,"handoffId":"handoff-1","sessionId":"session-1"},"retryable":false}
            """));
        captchaServer.start();

        HttpServer manualServer = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        manualServer.createContext("/capabilities", exchange -> respond(exchange, 503, """
            {"message":"manual handoff required","category":"manual_required","pageUrl":"https://example.test/login","handoff":{"required":true,"mode":"manual","reason":"manual_required","resumeSupported":false},"retryable":false}
            """));
        manualServer.start();

        try {
            WebAutomationEvidenceException captchaError = assertThrows(
                WebAutomationEvidenceException.class,
                () -> client(true, captchaServer.getAddress().getPort(), 5_000).capabilities()
            );
            assertEquals(422, captchaError.statusCode());
            assertEquals("captcha_required", captchaError.category());
            assertEquals(false, captchaError.retryable());
            assertTrue(captchaError.details().toString().contains("\"handoff\""));
            assertEquals("handoff-1", captchaError.details().path("handoff").path("handoffId").asText());
            assertEquals("session-1", captchaError.details().path("handoff").path("sessionId").asText());

            WebAutomationEvidenceException manualError = assertThrows(
                WebAutomationEvidenceException.class,
                () -> client(true, manualServer.getAddress().getPort(), 5_000).capabilities()
            );
            assertEquals(422, manualError.statusCode());
            assertEquals("manual_review_required", manualError.category());
            assertEquals(false, manualError.retryable());
        } finally {
            captchaServer.stop(0);
            manualServer.stop(0);
        }
    }

    @Test
    void prefersStructuredHandoffEvidenceOverFreeTextFailureReasonWhenHandoffIsRequired() throws Exception {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/capabilities", exchange -> respond(exchange, 503, """
            {"message":"runner reported an error","failureReason":"network_blocked_critical","handoff":{"required":true,"mode":"manual","reason":"manual","resumeSupported":false},"retryable":false}
            """));
        server.start();

        try {
            WebAutomationEvidenceException error = assertThrows(
                WebAutomationEvidenceException.class,
                () -> client(true, server.getAddress().getPort(), 5_000).capabilities()
            );
            assertEquals(422, error.statusCode());
            assertEquals("manual_review_required", error.category());
            assertTrue(error.details().path("handoff").path("required").asBoolean(false));
            assertEquals("manual_review_required", error.details().path("handoff").path("reason").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void mapsExhaustionReasonsToDurationExhausted() throws Exception {
        HttpServer exhaustedServer = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        exhaustedServer.createContext("/capabilities", exchange -> respond(exchange, 503, """
            {"message":"round budget exhausted","category":"round_budget_exhausted","pageUrl":"https://example.test/login","retryable":false}
            """));
        exhaustedServer.start();

        try {
            WebAutomationEvidenceException error = assertThrows(
                WebAutomationEvidenceException.class,
                () -> client(true, exhaustedServer.getAddress().getPort(), 5_000).capabilities()
            );
            assertEquals(422, error.statusCode());
            assertEquals("duration_exhausted", error.category());
        } finally {
            exhaustedServer.stop(0);
        }
    }

    @Test
    void keeps503WithoutStructuredCategoryAsRunnerUnavailable() throws Exception {
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/capabilities", exchange -> respond(exchange, 503, """
            {"message":"runner health check failed"}
            """));
        server.start();
        try {
            WebAutomationRunnerUnavailableException error = assertThrows(
                WebAutomationRunnerUnavailableException.class,
                () -> client(true, server.getAddress().getPort(), 5_000).capabilities()
            );
            assertTrue(error.getMessage().contains("HTTP 503"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exposesConfiguredRunnerBaseUrlAndTimeout() {
        WebAutomationProperties properties = new WebAutomationProperties(
            new WebAutomationProperties.Runner(true, "http://127.0.0.1:9323", 8_000)
        );
        assertEquals("http://127.0.0.1:9323", properties.runner().baseUrl());
        assertEquals(8_000, properties.runner().timeoutMs());
    }

    private PlaywrightRunClient client(boolean enabled, int port, int timeoutMs) {
        return new PlaywrightRunClient(new WebAutomationProperties(
            new WebAutomationProperties.Runner(enabled, "http://127.0.0.1:" + port, timeoutMs)
        ), new ObjectMapper());
    }

    private void handleRequest(HttpExchange exchange, List<String> calls) throws IOException {
        String call = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
        calls.add(call);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (call.equals("POST /runs")) {
            assertTrue(body.contains("\"browserOverride\":\"firefox\""));
            assertTrue(body.contains("\"timeoutMs\":8000"));
            respond(exchange, 202, snapshot("queued"));
            return;
        }
        if (call.equals("GET /capabilities")) {
            respond(exchange, 200, """
                {
                  "status": "UP",
                  "checkedAt": "2026-07-13T12:00:00.000Z",
                  "browsers": [
                    { "id": "chromium", "label": "Chromium", "available": true, "recommendation": "" }
                  ]
                }
                """);
            return;
        }
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
        if (call.equals("GET /sessions")) {
            respond(exchange, 200, """
                {
                  "sessions": [
                    { "sessionId": "session-1", "projectId": 1, "environmentId": "env-1", "browser": "chromium", "state": "READY_FOR_COMPILE", "leased": false, "pageKey": "https://example.com/login", "createdAt": 1, "expiresAt": 2, "lastUsedAt": 3 }
                  ]
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
              "progress": { "totalCases": 1, "completedCases": 0, "totalSteps": 1, "completedSteps": 0 },
              "controls": { "canPause": true, "canResume": false, "canStop": true },
              "summary": { "total": 0, "passed": 0, "failed": 0, "error": 0 },
              "stepLogs": [],
              "results": [],
              "diagnostics": []
            }
            """.formatted(runStatus);
    }

    private String discoverySnapshot(String status, boolean confirmed) {
        return """
            {
              "discoveryId": "discovery-1",
              "status": "%s",
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
                status,
                confirmed
                    ? ", \"confirmed\": { \"usernameLocator\": \"css=#username\", \"passwordLocator\": \"css=#password\", \"submitLocator\": \"role=button[name=登录]\" }"
                    : ""
            );
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    @Test
    void keepsStepEvidenceAndSnapshotsOnFailedResponses() throws Exception {
        // runner 的失败响应已带 stepEvidence/pageSnapshots；平台此前只转发分类，失败那一步的 before/after 全丢。
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/capabilities", exchange -> respond(exchange, 422, """
            {
              "message": "Action IR text assertion failed",
              "category": "assertion_failed",
              "retryable": false,
              "stepEvidence": [
                { "actionId": "go-detail", "actionType": "click", "execution": { "status": "passed" }, "before": { "pageKey": "http://127.0.0.1:41827/list" }, "secretValue": "must-not-leak" },
                { "actionId": "assert-title", "actionType": "assertVisible", "execution": { "status": "failed", "failureType": "assertion_failed" } }
              ],
              "pageSnapshots": [ { "pageKey": "http://127.0.0.1:41827/detail", "elementsEmpty": false } ]
            }
            """));
        server.start();
        try {
            WebAutomationEvidenceException error = assertThrows(
                WebAutomationEvidenceException.class,
                () -> client(true, server.getAddress().getPort(), 5_000).capabilities()
            );
            assertEquals("assertion_failed", error.category());
            assertEquals("go-detail", error.details().path("stepEvidence").get(0).path("actionId").asText());
            assertEquals("assert-title", error.details().path("stepEvidence").get(1).path("actionId").asText());
            assertEquals(2, error.details().path("stepEvidence").size());
            assertEquals(1, error.details().path("pageSnapshots").size());
            assertEquals("http://127.0.0.1:41827/list",
                error.details().path("stepEvidence").get(0).path("before").path("pageKey").asText());
            // 白名单之外的字段（例如可能夹带输入值的字段）不得透出。
            assertEquals(true, error.details().path("stepEvidence").get(0).path("secretValue").isMissingNode());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsEvidenceDetailsOnUnavailableRunnerResponses() throws Exception {
        // 未分类失败（runner 侧裸错误）走 503：分类为空，但已采集的证据仍必须透出，否则线上只能看到「runner 不可用」。
        HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/capabilities", exchange -> respond(exchange, 503, """
            {
              "message": "Cannot read properties of undefined",
              "stepEvidence": [ { "actionId": "a1", "execution": { "status": "failed" } } ],
              "pageSnapshots": [ { "pageKey": "http://127.0.0.1:41827/list" } ]
            }
            """));
        server.start();
        try {
            WebAutomationRunnerUnavailableException error = assertThrows(
                WebAutomationRunnerUnavailableException.class,
                () -> client(true, server.getAddress().getPort(), 5_000).capabilities()
            );
            assertEquals(503, error.statusCode());
            assertEquals("a1", error.details().path("stepEvidence").get(0).path("actionId").asText());
            assertEquals(1, error.details().path("pageSnapshots").size());
        } finally {
            server.stop(0);
        }
    }
}
