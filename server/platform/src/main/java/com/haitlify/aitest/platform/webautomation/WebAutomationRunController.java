package com.haitlify.aitest.platform.webautomation;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api/web-automation")
class WebAutomationRunController {

    private final WebAutomationRunService webAutomationRunService;

    WebAutomationRunController(WebAutomationRunService webAutomationRunService) {
        this.webAutomationRunService = webAutomationRunService;
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    WebAutomationRunSnapshotResponse createRun(@RequestBody WebAutomationRunRequest request) {
        return webAutomationRunService.createRun(request);
    }

    @GetMapping("/runs/{runId}")
    WebAutomationRunSnapshotResponse getRun(@PathVariable String runId) {
        return webAutomationRunService.getRun(runId);
    }

    @PostMapping("/runs/{runId}/pause")
    WebAutomationRunSnapshotResponse pauseRun(@PathVariable String runId) {
        return webAutomationRunService.controlRun(runId, "pause");
    }

    @PostMapping("/runs/{runId}/resume")
    WebAutomationRunSnapshotResponse resumeRun(@PathVariable String runId) {
        return webAutomationRunService.controlRun(runId, "resume");
    }

    @PostMapping("/runs/{runId}/stop")
    WebAutomationRunSnapshotResponse stopRun(@PathVariable String runId) {
        return webAutomationRunService.controlRun(runId, "stop");
    }

    @GetMapping("/capabilities")
    WebAutomationCapabilitiesResponse capabilities() {
        return webAutomationRunService.capabilities();
    }

    @GetMapping("/sessions")
    WebAutomationSessionListResponse listSessions() {
        return webAutomationRunService.listSessions();
    }

    @PostMapping("/login-discovery")
    @ResponseStatus(HttpStatus.ACCEPTED)
    WebLoginDiscoverySnapshotResponse createLoginDiscovery(@RequestBody WebLoginDiscoveryRequest request) {
        return webAutomationRunService.createLoginDiscovery(request);
    }

    @GetMapping("/login-discovery/{discoveryId}")
    WebLoginDiscoverySnapshotResponse getLoginDiscovery(@PathVariable String discoveryId) {
        return webAutomationRunService.getLoginDiscovery(discoveryId);
    }

    @PostMapping("/login-discovery/{discoveryId}/confirm")
    WebLoginDiscoverySnapshotResponse confirmLoginDiscovery(
        @PathVariable String discoveryId,
        @RequestBody WebLoginDiscoveryConfirmRequest request
    ) {
        return webAutomationRunService.confirmLoginDiscovery(discoveryId, request);
    }

    @PostMapping("/evidence/{handoffId}/resume")
    WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse resumeEvidenceHandoff(
        @PathVariable String handoffId,
        @RequestBody WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest request
    ) {
        return webAutomationRunService.resumeEvidenceHandoff(handoffId, request);
    }
}

@RestControllerAdvice
class WebAutomationRunExceptionHandler {

    @ExceptionHandler(WebAutomationRunnerDisabledException.class)
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    WebAutomationRunErrorResponse handleDisabled(WebAutomationRunnerDisabledException exception) {
        return new WebAutomationRunErrorResponse(
            exception.getMessage(),
            List.of(
                "Start the Web runner with `cd server/web-runner && npm install && node server.mjs`.",
                "Enable the proxy with `APP_WEB_AUTOMATION_RUNNER_ENABLED=true` and restart the Java backend."
            )
        );
    }

    @ExceptionHandler(WebAutomationRunnerUnavailableException.class)
    ResponseEntity<WebAutomationRunErrorResponse> handleUnavailable(WebAutomationRunnerUnavailableException exception) {
        HttpStatus status = HttpStatus.resolve(exception.statusCode());
        if (status == null) status = HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(new WebAutomationRunErrorResponse(
            exception.getMessage(),
            List.of(
                "Confirm the Web runner is listening at the configured base URL.",
                "Run `curl http://127.0.0.1:9323/health` to check runner health.",
                "Run `curl http://127.0.0.1:9323/capabilities` to inspect available browsers."
            ),
            exception.details()
        ));
    }

    @ExceptionHandler(WebAutomationEvidenceException.class)
    ResponseEntity<WebAutomationRunErrorResponse> handleEvidence(WebAutomationEvidenceException exception) {
        String category = effectiveCategory(exception);
        return ResponseEntity.status(exception.statusCode()).body(new WebAutomationRunErrorResponse(
            exception.getMessage(),
            safeSuggestions(category, exception),
            exception.details()
        ));
    }

    private List<String> safeSuggestions(String category, WebAutomationEvidenceException exception) {
        boolean takeoverRequired = requiresManualTakeover(category, exception);
        return switch (category) {
            case "auth_context_lost", "login_failed" -> takeoverRequired
                ? List.of(
                    "先重新登录并恢复同一会话，再继续当前操作。",
                    "如果页面仍停留在登录态，请切回原业务页后重试。"
                )
                : List.of(
                    "检查登录会话是否仍有效，再重试当前操作。",
                    "如果已经登录但仍失败，请确认页面是否已跳回登录页。"
                );
            case "captcha_required" -> {
                List<String> hints = new java.util.ArrayList<>();
                String blockedHint = networkBlockedHint(exception.details());
                if (!blockedHint.isBlank()) hints.add(blockedHint);
                hints.add("先完成验证码验证，再继续当前流程。");
                hints.add("如果验证码持续拦截，切换为人工接管。");
                yield List.copyOf(hints);
            }
            case "manual_review_required", "unsupported_dom" -> List.of(
                "当前页面需要人工接管后继续。",
                "若页面出现遮罩、验证码或登录态变化，请先恢复可操作状态。"
            );
            default -> {
                List<String> suggestions = new java.util.ArrayList<>();
                suggestions.add("检查登录会话、目标页面和允许的业务 Origin 后重试。");
                if (takeoverRequired) suggestions.add("当前页面需要人工接管后继续。");
                yield List.copyOf(suggestions);
            }
        };
    }

    /** 把被拦来源与放行建议写进建议列表（旧 Agent 分发器退役前与该实现同源）。 */
    private static String networkBlockedHint(JsonNode details) {
        if (details == null) return "";
        JsonNode network = details.path("network");
        java.util.LinkedHashSet<String> blocked = new java.util.LinkedHashSet<>();
        for (String bucket : List.of("criticalBlocked", "unknownBlocked", "optionalBlocked")) {
            JsonNode entries = network.path(bucket);
            if (!entries.isArray()) continue;
            for (JsonNode entry : entries) {
                String origin = entry.path("origin").asText("").strip();
                String path = entry.path("path").asText("").strip();
                String label = origin.isBlank() ? path
                    : (path.isBlank() || "/".equals(path) ? origin : origin + path);
                if (!label.isBlank()) blocked.add(label);
                if (blocked.size() >= 3) break;
            }
            if (blocked.size() >= 3) break;
        }
        if (blocked.isEmpty()) return "";
        String suffix = blocked.size() >= 3 ? " 等" : "";
        return "可能被网络白名单拦截：" + String.join("、", blocked) + suffix
            + "（如属登录或接口域名，请将对应 Origin 或带路径的完整 URL 加入环境「认证与放行白名单」后重试）";
    }

    private boolean requiresManualTakeover(String category, WebAutomationEvidenceException exception) {
        if (exception.details() != null && exception.details().path("handoff").path("required").asBoolean(false)) return true;
        return switch (category) {
            case "captcha_required", "manual_review_required", "unsupported_dom", "login_failed" -> true;
            case "auth_context_lost" -> !exception.retryable();
            default -> false;
        };
    }

    private String effectiveCategory(WebAutomationEvidenceException exception) {
        if (exception.details() == null) return exception.category();
        String category = exception.details().path("category").asText(exception.category());
        return category == null ? "" : category;
    }
}

class WebAutomationRunnerDisabledException extends RuntimeException {

    WebAutomationRunnerDisabledException(String message) {
        super(message);
    }
}

class WebAutomationRunnerUnavailableException extends RuntimeException {

    private final int statusCode;
    private final tools.jackson.databind.JsonNode details;

    WebAutomationRunnerUnavailableException(int statusCode, String message, Throwable cause) {
        this(statusCode, message, cause, null);
    }

    WebAutomationRunnerUnavailableException(int statusCode, String message, Throwable cause, tools.jackson.databind.JsonNode details) {
        super(message, cause);
        this.statusCode = statusCode;
        this.details = details;
    }

    int statusCode() {
        return statusCode;
    }

    /** 未分类失败（runner 侧裸错误）也可能带已采集的证据：503 响应同样要把它透出。 */
    tools.jackson.databind.JsonNode details() {
        return details;
    }
}

record WebAutomationRunRequest(
    String mode,
    Integer loopCount,
    String browserOverride,
    JsonNode runtime,
    List<WebAutomationRunCaseRequest> cases,
    Long projectId
) {
}

record WebLoginDiscoveryRequest(
    String url,
    String browser,
    String viewport,
    Boolean headless,
    Integer timeoutMs,
    String waitUntil
) {
}

record WebLoginDiscoveryConfirmRequest(
    String usernameCandidateId,
    String passwordCandidateId,
    String submitCandidateId
) {
}

record WebLoginDiscoverySnapshotResponse(
    String discoveryId,
    String status,
    String createdAt,
    String startedAt,
    String updatedAt,
    String endedAt,
    WebLoginDiscoveryRequestSummaryResponse request,
    String pageUrl,
    String screenshotUrl,
    WebLoginDiscoveryCandidateGroupsResponse candidates,
    WebLoginDiscoveryRecommendedResponse recommended,
    List<String> diagnostics,
    WebLoginDiscoveryConfirmedResponse confirmed
) {
}

record WebLoginDiscoveryRequestSummaryResponse(
    String url,
    String browser,
    String viewport
) {
}

record WebLoginDiscoveryCandidateGroupsResponse(
    List<WebLoginDiscoveryCandidateResponse> username,
    List<WebLoginDiscoveryCandidateResponse> password,
    List<WebLoginDiscoveryCandidateResponse> submit
) {
}

record WebLoginDiscoveryRecommendedResponse(
    WebLoginDiscoveryCandidateResponse username,
    WebLoginDiscoveryCandidateResponse password,
    WebLoginDiscoveryCandidateResponse submit
) {
}

record WebLoginDiscoveryCandidateResponse(
    String id,
    String role,
    Integer domIndex,
    String locator,
    String strategy,
    String label,
    Integer confidence,
    String reason
) {
}

record WebLoginDiscoveryConfirmedResponse(
    String usernameCandidateId,
    String usernameLocator,
    String passwordCandidateId,
    String passwordLocator,
    String submitCandidateId,
    String submitLocator,
    String confirmedAt
) {
}

record WebAutomationRunCaseRequest(
    String id,
    String sourceCaseId,
    String project,
    String module,
    String pageName,
    String name,
    String startUrl,
    String browser,
    String viewport,
    int runOrder,
    String mockOutcome,
    String authenticationMode,
    List<WebAutomationStepRequest> steps
) {
}

record WebAutomationStepRequest(
    String id,
    String name,
    String action,
    String targetType,
    String target,
    String value,
    Integer timeoutMs,
    String screenshot
) {
}

record WebAutomationRunSnapshotResponse(
    String runId,
    String status,
    String mode,
    String selectedBrowser,
    String createdAt,
    String startedAt,
    String updatedAt,
    String endedAt,
    WebAutomationRunProgressResponse progress,
    WebAutomationCurrentCaseResponse currentCase,
    WebAutomationCurrentStepResponse currentStep,
    String currentPageUrl,
    String latestScreenshotUrl,
    String latestScreenshotAt,
    String sessionId,
    WebAutomationRunControlsResponse controls,
    WebAutomationRunSummaryResponse summary,
    List<WebAutomationStepLogResponse> stepLogs,
    List<WebAutomationRunResultResponse> results,
    List<String> diagnostics
) {
}

record WebAutomationRunProgressResponse(
    int totalCases,
    int completedCases,
    int totalSteps,
    int completedSteps
) {
}

record WebAutomationCurrentCaseResponse(
    String id,
    String name
) {
}

record WebAutomationCurrentStepResponse(
    String id,
    String name,
    String action,
    String phase
) {
}

record WebAutomationRunControlsResponse(
    boolean canPause,
    boolean canResume,
    boolean canStop
) {
}

record WebAutomationRunSummaryResponse(
    int total,
    int passed,
    int failed,
    int error
) {
}

record WebAutomationRunResultResponse(
    String caseId,
    String sourceCaseId,
    String name,
    String module,
    String pageName,
    String startUrl,
    String browser,
    String status,
    int durationMs,
    String failedStepId,
    String pageHtmlSnippet,
    String screenshotPath,
    String errorMessage,
    List<WebAutomationStepLogResponse> stepLogs,
    List<String> consoleErrors,
    List<String> pageErrors,
    List<String> failedRequests
) {
}

record WebAutomationStepLogResponse(
    String stepId,
    String phase,
    String name,
    String action,
    String target,
    String status,
    int durationMs,
    String startedAt,
    String endedAt,
    String pageUrl,
    String errorMessage,
    String errorStack
) {
}

record WebAutomationCapabilitiesResponse(
    String status,
    String checkedAt,
    List<WebAutomationBrowserCapabilityResponse> browsers
) {
}

/** 阶段 7 增量 3：会话状态面板视图（runner /sessions 的只读元数据，已脱敏）。 */
record WebAutomationSessionView(
    String sessionId,
    Long projectId,
    String environmentId,
    String browser,
    String state,
    boolean leased,
    String pageKey,
    Long createdAt,
    Long expiresAt,
    Long lastUsedAt
) {
}

record WebAutomationSessionListResponse(
    List<WebAutomationSessionView> sessions
) {
}

record WebAutomationBrowserCapabilityResponse(
    String id,
    String label,
    boolean available,
    String message,
    String recommendation
) {
}

record WebAutomationRunErrorResponse(
    String message,
    List<String> recommendations,
    tools.jackson.databind.JsonNode details
) {
    WebAutomationRunErrorResponse(String message, List<String> recommendations) {
        this(message, recommendations, null);
    }
}
