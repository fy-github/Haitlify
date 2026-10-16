package com.haitlify.aitest.platform.webautomation;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.JsonNode;

/** Obtains selector evidence from a real browser without exposing credentials to AI prompts. */
@Service
public class WebAutomationLocatorDiscoveryService {

    private final PlaywrightRunClient playwrightRunClient;

    WebAutomationLocatorDiscoveryService(PlaywrightRunClient playwrightRunClient) {
        this.playwrightRunClient = playwrightRunClient;
    }

    public PageLocatorDiscoveryResponse discover(PageLocatorDiscoveryRequest request) {
        return playwrightRunClient.discoverPageLocators(request);
    }

    public record PageLocatorDiscoveryRequest(
        String url,
        String baseUrl,
        String browser,
        String viewport,
        Boolean headless,
        Integer timeoutMs,
        String waitUntil,
        String authToken,
        Map<String, String> extraHeaders,
        List<String> cookieOrigins,
        @JsonProperty("allowedOrigins")
        @JsonAlias("allowedRequestOrigins")
        List<String> allowedRequestOrigins,
        Login login,
        List<String> navigationHints,
        @JsonAlias("intent")
        TargetIntent targetIntent,
        ExplorationBudget budget,
        @JsonAlias("history")
        List<String> previousAttempts,
        String sessionId,
        Boolean reuseSession,
        String sessionKey,
        @JsonAlias("pointId")
        String sourcePointId,
        RepairSuggestion repairSuggestion,
        Boolean criticalityAwareNetworkPolicy,
        List<HistoricalLocatorCandidate> historicalLocators,
        List<String> allowedRequestUrls,
        String diagnosticMode,
        Boolean tracingEnabled,
        JsonNode actions,
        String networkPolicyMode
    ) {
        public PageLocatorDiscoveryRequest(
            String url, String baseUrl, String browser, String viewport, Boolean headless, Integer timeoutMs,
            String waitUntil, String authToken, Map<String, String> extraHeaders, List<String> cookieOrigins,
            List<String> allowedRequestOrigins, Login login, List<String> navigationHints, TargetIntent targetIntent,
            ExplorationBudget budget, List<String> previousAttempts, String sessionId, Boolean reuseSession,
            String sessionKey, String sourcePointId, RepairSuggestion repairSuggestion, Boolean criticalityAwareNetworkPolicy,
            List<HistoricalLocatorCandidate> historicalLocators, List<String> allowedRequestUrls, String diagnosticMode,
            Boolean tracingEnabled
        ) {
            this(url, baseUrl, browser, viewport, headless, timeoutMs, waitUntil, authToken, extraHeaders, cookieOrigins,
                allowedRequestOrigins, login, navigationHints, targetIntent, budget, previousAttempts, sessionId, reuseSession,
                sessionKey, sourcePointId, repairSuggestion, criticalityAwareNetworkPolicy, historicalLocators,
                allowedRequestUrls, diagnosticMode, tracingEnabled, null, null);
        }
        public PageLocatorDiscoveryRequest(
            String url,
            String baseUrl,
            String browser,
            String viewport,
            Boolean headless,
            Integer timeoutMs,
            String waitUntil,
            String authToken,
            Map<String, String> extraHeaders,
            List<String> cookieOrigins,
            Login login,
            List<String> navigationHints
        ) {
            this(url, baseUrl, browser, viewport, headless, timeoutMs, waitUntil, authToken,
                extraHeaders, cookieOrigins, null, login, navigationHints, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        public PageLocatorDiscoveryRequest(
            String url,
            String baseUrl,
            String browser,
            String viewport,
            Boolean headless,
            Integer timeoutMs,
            String waitUntil,
            String authToken,
            Map<String, String> extraHeaders,
            List<String> cookieOrigins,
            Login login,
            List<String> navigationHints,
            TargetIntent targetIntent,
            ExplorationBudget budget,
            List<String> previousAttempts
        ) {
            this(url, baseUrl, browser, viewport, headless, timeoutMs, waitUntil, authToken,
                extraHeaders, cookieOrigins, null, login, navigationHints, targetIntent, budget, previousAttempts, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        /** Legacy web-runner payload name; keep targetIntent as the platform API field. */
        @JsonProperty("intent")
        public TargetIntent intent() {
            return targetIntent;
        }

        /** Runner payload name for retry/navigation history; previousAttempts remains the domain field. */
        @JsonProperty("history")
        public List<String> history() {
            return previousAttempts;
        }
    }

    public record TargetIntent(
        List<String> pageAliases,
        List<String> routeHints,
        List<String> elementTerms,
        List<String> actionTerms,
        List<String> stateTerms,
        List<String> expectedTexts,
        @JsonAlias("explicitNegativeTerms")
        List<String> negativeTerms
    ) {
        public List<String> explicitNegativeTerms() {
            return negativeTerms;
        }
    }

    public record RepairSuggestion(String category, String reason) { }

    public record HistoricalLocatorCandidate(
        String entryId,
        String locator,
        String label,
        String role,
        String type,
        int confidenceScore,
        String pageUrl,
        String pageFingerprint,
        String sourcePointId,
        Long projectId,
        String environmentId
    ) { }

    public record ExplorationBudget(
        int maxRounds,
        long maxDurationMs,
        int maxSameFailureRetries,
        int maxLoginRetries,
        int maxReloads,
        int maxPaginationSteps,
        int minimumTotalScore,
        int minimumElementScore
    ) {
    }

    public record ExplorationScores(
        int pageScore,
        int elementScore,
        int stateScore,
        int totalScore,
        boolean matched,
        JsonNode relevantElements
    ) {
        public ExplorationScores(int pageScore, int elementScore, int stateScore, int totalScore, boolean matched) {
            this(pageScore, elementScore, stateScore, totalScore, matched, null);
        }
    }

    public record ExplorationRound(
        int round,
        String pageUrl,
        String fingerprint,
        String actionType,
        String actionTarget,
        ExplorationScores scores,
        JsonNode observation,
        JsonNode action,
        JsonNode actionFailure
    ) {
        public ExplorationRound(
            int round,
            String pageUrl,
            String fingerprint,
            String actionType,
            String actionTarget,
            ExplorationScores scores
        ) {
            this(round, pageUrl, fingerprint, actionType, actionTarget, scores, null, null, null);
        }
    }

    public record ExplorationReport(
        String status,
        List<ExplorationRound> rounds,
        ExplorationScores scores,
        List<String> navigationTrail,
        String failureReason,
        long durationMs,
        JsonNode finalObservation,
        long remainingDurationMs,
        String trailError,
        JsonNode network,
        JsonNode readiness,
        JsonNode repairTrail,
        List<String> historicalMatchedIds,
        List<String> historicalRejectedIds
    ) {
        public ExplorationReport(
            String status,
            List<ExplorationRound> rounds,
            ExplorationScores scores,
            List<String> navigationTrail,
            String failureReason,
            long durationMs
        ) {
            this(status, rounds, scores, navigationTrail, failureReason, durationMs, null, 0, null, null, null, null);
        }

        public ExplorationReport(
            String status,
            List<ExplorationRound> rounds,
            ExplorationScores scores,
            List<String> navigationTrail,
            String failureReason,
            long durationMs,
            JsonNode finalObservation,
            long remainingDurationMs,
            String trailError
        ) {
            this(status, rounds, scores, navigationTrail, failureReason, durationMs, finalObservation, remainingDurationMs, trailError, null, null, null, List.of(), List.of());
        }

        public ExplorationReport(
            String status,
            List<ExplorationRound> rounds,
            ExplorationScores scores,
            List<String> navigationTrail,
            String failureReason,
            long durationMs,
            JsonNode finalObservation,
            long remainingDurationMs,
            String trailError,
            JsonNode network,
            JsonNode readiness,
            JsonNode repairTrail
        ) {
            this(status, rounds, scores, navigationTrail, failureReason, durationMs, finalObservation, remainingDurationMs, trailError, network, readiness, repairTrail, List.of(), List.of());
        }

        public ExplorationReport(
            String status,
            List<ExplorationRound> rounds,
            ExplorationScores scores,
            List<String> navigationTrail,
            String failureReason,
            long durationMs,
            JsonNode finalObservation,
            long remainingDurationMs,
            String trailError,
            JsonNode network,
            JsonNode readiness,
            JsonNode repairTrail,
            List<String> historicalMatchedIds,
            List<String> historicalRejectedIds
        ) {
            this.status = status;
            this.rounds = rounds == null ? List.of() : List.copyOf(rounds);
            this.scores = scores;
            this.navigationTrail = navigationTrail == null ? List.of() : List.copyOf(navigationTrail);
            this.failureReason = failureReason;
            this.durationMs = durationMs;
            this.finalObservation = finalObservation;
            this.remainingDurationMs = remainingDurationMs;
            this.trailError = trailError;
            this.network = network;
            this.readiness = readiness;
            this.repairTrail = repairTrail;
            this.historicalMatchedIds = historicalMatchedIds == null ? List.of() : List.copyOf(historicalMatchedIds);
            this.historicalRejectedIds = historicalRejectedIds == null ? List.of() : List.copyOf(historicalRejectedIds);
        }
    }

    public record Login(
        boolean enabled,
        String url,
        String usernameLocator,
        String passwordLocator,
        String submitLocator,
        String username,
        String password,
        String successType,
        String successTarget
    ) {
    }

    public record PageLocatorDiscoveryResponse(
        String pageUrl,
        List<PageLocatorEvidence> elements,
        ExplorationReport exploration,
        String sessionId,
        Boolean authenticated,
        List<String> historicalMatchedIds,
        List<String> historicalRejectedIds,
        String traceId,
        String screenshotUrl,
        JsonNode stepEvidence,
        String sourcePointId,
        JsonNode pageSnapshots,
        JsonNode unsupported
    ) {
        public PageLocatorDiscoveryResponse(String pageUrl, List<PageLocatorEvidence> elements) {
            this(pageUrl, elements, null, null, null, List.of(), List.of(), null, null, null, null, null, null);
        }

        public PageLocatorDiscoveryResponse(String pageUrl, List<PageLocatorEvidence> elements, ExplorationReport exploration,
                                            String sessionId, Boolean authenticated, List<String> historicalMatchedIds,
                                            List<String> historicalRejectedIds) {
            this(pageUrl, elements, exploration, sessionId, authenticated, historicalMatchedIds, historicalRejectedIds, null, null, null, null, null, null);
        }

        public PageLocatorDiscoveryResponse(String pageUrl, List<PageLocatorEvidence> elements, ExplorationReport exploration) {
            this(pageUrl, elements, exploration, null, null, List.of(), List.of(), null, null, null, null, null, null);
        }
    }

    public record PageLocatorEvidence(
        String id,
        String locator,
        String label,
        String role,
        String tagName,
        String type,
        String strategy,
        Boolean visible,
        Boolean enabled,
        Boolean receivesEvents,
        Integer matchedElementCount,
        String evidenceId,
        JsonNode locatorSpec,
        String framePath,
        String shadowPath,
        String frameUrl,
        String pageScope,
        String rowContext,
        Boolean targeted
    ) {
        public PageLocatorEvidence(String id, String locator, String label, String role, String tagName, String type, String strategy) {
            this(id, locator, label, role, tagName, type, strategy, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        public PageLocatorEvidence(String id, String locator, String label, String role, String tagName, String type, String strategy,
                                   Boolean visible, Boolean enabled, Boolean receivesEvents, Integer matchedElementCount) {
            this(id, locator, label, role, tagName, type, strategy, visible, enabled, receivesEvents, matchedElementCount,
                null, null, null, null, null, null, null, null);
        }
    }
}
