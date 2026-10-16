package com.haitlify.aitest.platform.ai.agentic.locator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.UUID;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService;
import com.haitlify.aitest.platform.auth.ProjectAccessService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
@Transactional
public class LocatorKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(LocatorKnowledgeService.class);

    /** 实时复核阈值:一个 locator 累计失败达到该次数即判定失效并退役。 */
    private static final int STALE_AFTER_FAILURES = 2;
    private static final int HISTORICAL_EVIDENCE_LIMIT = 80;
    private static final int HISTORICAL_CANDIDATE_LIMIT = 20;
    private static final int VALIDATION_MATCH_BONUS = 3;
    private static final int VALIDATION_PAGE_MISS_PENALTY = 10;
    private static final Set<String> REPAIR_FAILURE_CATEGORIES = Set.of(
        "locator_not_found", "ambiguous_locator", "element_not_visible", "page_not_ready", "assertion_target_not_found"
    );

    private final JdbcTemplate jdbcTemplate;
    private final WebAutomationLocatorDiscoveryService discoveryService;
    private final ObjectMapper objectMapper;
    private final ProjectAccessService projectAccessService;

    LocatorKnowledgeService(JdbcTemplate jdbcTemplate, WebAutomationLocatorDiscoveryService discoveryService) {
        this(jdbcTemplate, discoveryService, new ObjectMapper(), new ProjectAccessService(jdbcTemplate));
    }

    @Autowired
    LocatorKnowledgeService(JdbcTemplate jdbcTemplate, WebAutomationLocatorDiscoveryService discoveryService, ObjectMapper objectMapper, ProjectAccessService projectAccessService) {
        this.jdbcTemplate = jdbcTemplate;
        this.discoveryService = discoveryService;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.projectAccessService = projectAccessService;
    }

    /** 安全保存一次浏览器探索摘要，不包含 DOM、截图、凭据、请求头或 Cookie。 */
    public record ExplorationKnowledge(
        List<String> navigationTrail,
        int totalScore,
        int rounds,
        String status,
        String pageFingerprint,
        String pageUrl
    ) {
        public ExplorationKnowledge(List<String> navigationTrail, int totalScore, int rounds, String status) {
            this(navigationTrail, totalScore, rounds, status, null, null);
        }
    }

    public LocatorKnowledgeListResponse list(Long projectId, String environmentId, String moduleName, String pageName, String keyword) {
        ensureProjectAccess(projectId);
        String term = keyword == null ? "" : keyword.trim();
        List<LocatorKnowledgeView> items = jdbcTemplate.query(
            "select * from ai_locator_knowledge_entries where project_id = ? and (? = '' or environment_id = ?) and (? = '' or module_name = ?) and (? = '' or page_name = ?) and (? = '' or natural_name like ? or locator like ?) order by module_name, page_name, natural_name",
            (rs, rowNum) -> new LocatorKnowledgeView(
                rs.getString("entry_id"), rs.getLong("project_id"), rs.getString("environment_id"), rs.getString("module_name"), rs.getString("page_name"),
                rs.getString("page_url"), rs.getString("page_fingerprint"), rs.getString("natural_name"), rs.getString("locator"), rs.getString("role_name"),
                rs.getString("accessible_name"), rs.getString("element_type"), rs.getString("source"), rs.getString("status"), rs.getInt("evidence_version"),
                rs.getTimestamp("collected_at").toInstant().toString(), rs.getTimestamp("last_verified_at") == null ? null : rs.getTimestamp("last_verified_at").toInstant().toString(),
                rs.getInt("hit_count"), rs.getInt("execution_success_count"), rs.getInt("execution_failure_count"), rs.getInt("confidence_score"), rs.getBoolean("confidence_locked"), rs.getTimestamp("last_hit_at") == null ? null : rs.getTimestamp("last_hit_at").toInstant().toString()
            ), projectId, environmentId == null ? "" : environmentId, environmentId == null ? "" : environmentId,
            moduleName == null ? "" : moduleName, moduleName == null ? "" : moduleName,
            pageName == null ? "" : pageName, pageName == null ? "" : pageName,
            term, "%" + term + "%", "%" + term + "%"
        );
        return new LocatorKnowledgeListResponse(items);
    }

    public LocatorKnowledgeListResponse collect(CollectLocatorKnowledgeRequest request) {
        if (request.projectId() == null || request.discovery() == null) throw new IllegalArgumentException("项目和浏览器采集参数不能为空");
        ensureProjectAccess(request.projectId());
        WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse discovered = discoveryService.discover(request.discovery());
        String fingerprint = computePageFingerprint(discovered.pageUrl(), discovered.elements());
        Instant now = Instant.now();
        for (WebAutomationLocatorDiscoveryService.PageLocatorEvidence item : discovered.elements()) {
            String entryId = "locator-" + UUID.randomUUID();
            jdbcTemplate.update("insert into ai_locator_knowledge_entries(entry_id, project_id, environment_id, module_name, page_name, page_url, page_fingerprint, natural_name, locator, role_name, accessible_name, element_type, source, status, evidence_version, collected_at, last_verified_at, metadata_json, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'browser', 'active', 1, ?, ?, '{}', ?, ?)",
                entryId, request.projectId(), value(request.environmentId()), value(request.moduleName()), value(request.pageName()), discovered.pageUrl(), fingerprint, value(item.label()), item.locator(), value(item.role()), value(item.label()), value(item.type()), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
            jdbcTemplate.update("insert into ai_locator_knowledge_history(history_id, entry_id, snapshot_json, changed_reason, created_at) values (?, ?, ?, 'browser_collection', ?)", "history-" + UUID.randomUUID(), entryId, "{\"locator\":\"" + escape(item.locator()) + "\"}", Timestamp.from(now));
        }
        return list(request.projectId(), request.environmentId(), request.moduleName(), request.pageName(), "");
    }

    public List<WebAutomationLocatorDiscoveryService.PageLocatorEvidence> searchEvidence(Long projectId, String environmentId, String moduleName, String pageName, String keyword) {
        return searchEvidence(projectId, environmentId, moduleName, pageName, keyword, null);
    }

    /**
     * Searches reusable evidence. When a current page fingerprint is supplied,
     * high-confidence entries are retired immediately if the structure changed.
     */
    public List<WebAutomationLocatorDiscoveryService.PageLocatorEvidence> searchEvidence(Long projectId, String environmentId, String moduleName, String pageName, String keyword, String currentPageFingerprint) {
        ensureProjectAccess(projectId);
        List<LocatorKnowledgeRow> rows = queryKnowledgeRows(projectId, environmentId, moduleName, pageName, keyword, HISTORICAL_EVIDENCE_LIMIT);
        if (value(currentPageFingerprint).isBlank()) {
            return rows.stream().map(LocatorKnowledgeRow::evidence).toList();
        }
        List<WebAutomationLocatorDiscoveryService.PageLocatorEvidence> reusable = new ArrayList<>();
        for (LocatorKnowledgeRow row : rows) {
            if (row.confidenceScore() >= 85 && hasExplorationMetadata(row.metadataJson()) && !value(row.pageFingerprint()).isBlank()
                && !row.pageFingerprint().equals(currentPageFingerprint)) {
                markStale(row.evidence().id());
                continue;
            }
            reusable.add(row.evidence());
        }
        return reusable;
    }

    /**
     * Returns reusable historical locators together with their confidence so the
     * browser can prioritize them as candidates. They are not evidence until the
     * current page DOM verifies the locator.
     *
     * <p>刻意以 REQUIRES_NEW 独立事务执行：调用方（证据收集的降级路径）会捕获读取失败并
     * 继续走浏览器取证，独立事务保证读取失败不会把调用方事务标记为 rollback-only，
     * 避免最终提交时抛出 UnexpectedRollbackException。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<LocatorKnowledgeCandidate> searchCandidates(Long projectId, String environmentId, String moduleName, String pageName, String keyword, String currentPageFingerprint) {
        ensureProjectAccess(projectId);
        // Candidate retrieval must not retire entries using a fingerprint supplied by
        // an AI plan. The browser computes the current fingerprint after UI loading;
        // only that observation can affect confidence or stale status.
        return queryKnowledgeRows(projectId, environmentId, moduleName, pageName, keyword, HISTORICAL_CANDIDATE_LIMIT).stream()
            .map(row -> new LocatorKnowledgeCandidate(
                row.entryId(), row.locator(), row.label(), row.role(), row.type(), row.confidenceScore(), row.pageUrl(), row.pageFingerprint(), row.projectId(), row.environmentId()
            ))
            .toList();
    }

    /**
     * 素材库候选检索 SQL。
     *
     * <p>转义约定：LIKE 的 ESCAPE 字符刻意使用 {@code !}（而非反斜杠）——反斜杠在
     * Java 文本块与 MySQL/H2 字符串字面量之间存在多重转义歧义：写成单反斜杠时 MySQL
     * 驱动会把紧随的引号视为被转义，连锁吞掉后面的占位符，在真实 MySQL 上抛
     * "Parameter index out of range"（H2 不复现）；而写成双反斜杠时 H2 会拒绝
     * （ESCAPE 必须是单个字符）。{@code !} 无转义语义，两种数据库与驱动解析均无歧义。
     *
     * <p>模式串的转义由 {@link #escapeLike(String)} 负责（{@code !}、{@code %}、
     * {@code _} 均用 {@code !} 前缀转义）。
     */
    static final String KNOWLEDGE_ROWS_SQL = """
                select entry_id, project_id, environment_id, locator, natural_name, accessible_name, role_name, element_type, page_url,
                       page_fingerprint, confidence_score, collected_at, last_verified_at, source, metadata_json
                from ai_locator_knowledge_entries
                where project_id = ?
                  and status = 'active'
                  and (? = '' or environment_id in (?, ''))
                  and (? = '' or module_name = ? or module_name like ? escape '!')
                  and (? = '' or page_name = ? or page_name like ? escape '!' or page_name like ? escape '!')
                  and (? = '' or natural_name like ? escape '!' or accessible_name like ? escape '!' or locator like ? escape '!')
                order by
                  case when environment_id = ? then 0 else 1 end,
                  case when last_verified_at is null then 1 else 0 end,
                  last_verified_at desc,
                  confidence_score desc,
                  module_name asc,
                  page_name asc,
                  collected_at desc
                limit ?
                """;

    private List<LocatorKnowledgeRow> queryKnowledgeRows(Long projectId, String environmentId, String moduleName, String pageName, String keyword, int limit) {
        if (projectId == null) return List.of();
        String normalizedEnvironmentId = value(environmentId);
        String normalizedModuleName = value(moduleName);
        String normalizedPageName = value(pageName);
        String normalizedKeyword = (!normalizedModuleName.isBlank() || !normalizedPageName.isBlank()) ? "" : value(keyword).strip();
        String moduleFamilyName = normalizePageFamily(normalizedModuleName);
        String pageFamilyName = normalizePageFamily(normalizedPageName);
        String escapedModuleFamily = escapeLike(moduleFamilyName);
        String escapedPageFamily = escapeLike(pageFamilyName);
        String keywordLike = "%" + escapeLike(normalizedKeyword) + "%";
        return jdbcTemplate.query(KNOWLEDGE_ROWS_SQL,
            (rs, rowNum) -> new LocatorKnowledgeRow(
                rs.getString("entry_id"),
                rs.getLong("project_id"),
                rs.getString("environment_id"),
                rs.getString("locator"),
                firstNonBlank(rs.getString("natural_name"), rs.getString("accessible_name")),
                rs.getString("role_name"),
                rs.getString("element_type"),
                rs.getString("page_url"),
                rs.getString("page_fingerprint"),
                rs.getInt("confidence_score"),
                rs.getTimestamp("collected_at") == null ? null : rs.getTimestamp("collected_at").toInstant().toString(),
                rs.getTimestamp("last_verified_at") == null ? null : rs.getTimestamp("last_verified_at").toInstant().toString(),
                "locatorKnowledge:" + value(rs.getString("source")),
                rs.getString("metadata_json")
            ),
            projectId,
            normalizedEnvironmentId, normalizedEnvironmentId,
            normalizedModuleName, normalizedModuleName, escapedModuleFamily + "%",
            normalizedPageName, normalizedPageName, escapedPageFamily + "%", "%" + escapedPageFamily + "%",
            normalizedKeyword, keywordLike, keywordLike, keywordLike,
            normalizedEnvironmentId,
            limit
        );
    }

    public record LocatorKnowledgeCandidate(
        String entryId,
        String locator,
        String label,
        String role,
        String type,
        int confidenceScore,
        String pageUrl,
        String pageFingerprint,
        Long projectId,
        String environmentId
    ) { }

    public enum ValidationState {
        NOT_ELIGIBLE,
        MATCHED,
        CONFIRMED_PAGE_MISS
    }

    /** Updates historical confidence only after a real browser DOM verification attempt. */
    public void recordBrowserValidation(List<String> candidateIds, List<String> matchedIds) {
        recordBrowserValidation(candidateIds, matchedIds, ValidationState.CONFIRMED_PAGE_MISS);
    }

    public void recordBrowserValidation(List<String> candidateIds, List<String> matchedIds, boolean eligible) {
        recordBrowserValidation(candidateIds, matchedIds, eligible ? ValidationState.CONFIRMED_PAGE_MISS : ValidationState.NOT_ELIGIBLE);
    }

    public void recordBrowserValidation(List<String> candidateIds, List<String> matchedIds, ValidationState validationState) {
        if (validationState == null || validationState == ValidationState.NOT_ELIGIBLE) {
            currentActor();
            return;
        }
        if (candidateIds == null || candidateIds.isEmpty()) {
            currentActor();
            return;
        }
        Set<String> matched = matchedIds == null ? Set.of() : new HashSet<>(matchedIds);
        authorizeEntryIds(candidateIds);
        Set<String> candidateSet = candidateIds.stream()
            .filter(id -> id != null && !id.isBlank()).collect(Collectors.toSet());
        if (!candidateSet.containsAll(matched)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问项目");
        }
        Instant now = Instant.now();
        for (String entryId : candidateIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList()) {
            ValidationState state = matched.contains(entryId) ? ValidationState.MATCHED : validationState;
            applyValidationState(entryId, state, now);
        }
    }

    private void applyValidationState(String entryId, ValidationState validationState, Instant now) {
        if (validationState == ValidationState.NOT_ELIGIBLE) return;
        LocatorKnowledgeView current = getView(entryId, true);
        int successes = current.executionSuccessCount();
        int failures = current.executionFailureCount();
        int confidence = current.confidenceScore();
        String status = current.status();
        if (validationState == ValidationState.MATCHED) {
            successes += 1;
            confidence = Math.min(100, confidence + VALIDATION_MATCH_BONUS);
        } else if (validationState == ValidationState.CONFIRMED_PAGE_MISS) {
            failures += 1;
            confidence = Math.max(0, confidence - VALIDATION_PAGE_MISS_PENALTY);
            if (failures >= STALE_AFTER_FAILURES) status = "stale";
        }
        jdbcTemplate.update(
            "update ai_locator_knowledge_entries set execution_success_count = ?, execution_failure_count = ?, confidence_score = ?, confidence_locked = ?, status = ?, updated_at = ? where entry_id = ?",
            successes, failures, confidence, confidence >= 85, status, Timestamp.from(now), entryId
        );
    }

    private record LocatorKnowledgeRow(
        String entryId,
        Long projectId,
        String environmentId,
        String locator,
        String label,
        String role,
        String type,
        String pageUrl,
        String pageFingerprint,
        int confidenceScore,
        String collectedAt,
        String lastVerifiedAt,
        String source,
        String metadataJson
    ) {
        WebAutomationLocatorDiscoveryService.PageLocatorEvidence evidence() {
            return new WebAutomationLocatorDiscoveryService.PageLocatorEvidence(entryId, locator, label, role, type, "", source, true, true, true, 1);
        }
    }

    private record SearchEvidenceRow(
        String entryId,
        WebAutomationLocatorDiscoveryService.PageLocatorEvidence evidence,
        String pageFingerprint,
        int confidenceScore,
        String metadataJson
    ) {}

    public void recordHit(String runId, String pointId, List<String> entryIds) {
        try {
            authorizeEntryIds(entryIds);
            Instant now = Instant.now();
            for (String entryId : entryIds == null ? List.<String>of() : entryIds.stream().distinct().toList()) {
                if (entryId == null || entryId.isBlank()) continue;
                Integer inserted = jdbcTemplate.update("insert into ai_agent_asset_usage(usage_id, run_id, point_id, asset_type, asset_id, outcome, created_at, updated_at) select ?, ?, ?, 'locator', ?, 'pending', ?, ? where not exists (select 1 from ai_agent_asset_usage where run_id = ? and point_id = ? and asset_type = 'locator' and asset_id = ?)", "usage-" + UUID.randomUUID(), runId, pointId, entryId, Timestamp.from(now), Timestamp.from(now), runId, pointId, entryId);
                if (inserted > 0) {
                    jdbcTemplate.update("update ai_locator_knowledge_entries set hit_count = hit_count + 1, last_hit_at = ?, updated_at = ? where entry_id = ?", Timestamp.from(now), Timestamp.from(now), entryId);
                    refreshQuality(entryId, 0, 0, now);
                }
            }
        } catch (DataAccessException failure) {
            throw new WebLocatorKnowledgePersistenceException(null, failure);
        }
    }

    public void recordExecutionOutcome(String runId, String pointId, boolean succeeded) {
        try {
            authorizeUsage(runId, pointId);
            Instant now = Instant.now();
            List<String> ids = jdbcTemplate.queryForList("select asset_id from ai_agent_asset_usage where run_id = ? and point_id = ? and asset_type = 'locator' and outcome = 'pending'", String.class, runId, pointId);
            for (String entryId : ids) {
                refreshQuality(entryId, succeeded ? 1 : 0, succeeded ? 0 : 1, now);
                if (!succeeded) markStaleAfterRepeatedFailure(entryId);
            }
            jdbcTemplate.update("update ai_agent_asset_usage set outcome = ?, updated_at = ? where run_id = ? and point_id = ? and asset_type = 'locator' and outcome = 'pending'", succeeded ? "passed" : "failed", Timestamp.from(now), runId, pointId);
        } catch (DataAccessException failure) {
            throw new WebLocatorKnowledgePersistenceException(null, failure);
        }
    }

    /** 实时复核:同一个 locator 连续失败超过阈值即退役(置 stale),下次生成会重新走浏览器采集校验,而不是继续复用失效定位。 */
    private void markStaleAfterRepeatedFailure(String entryId) {
        Integer failures = jdbcTemplate.queryForObject("select execution_failure_count from ai_locator_knowledge_entries where entry_id = ?", Integer.class, entryId);
        if (failures != null && failures >= STALE_AFTER_FAILURES) {
            log.warn("locator {} 累计失败 {} 次, 判定失效并退役, 下次生成将重新浏览器采集复核", entryId, failures);
            markStale(entryId);
        }
    }

    public int retireUnusedBefore(Instant cutoff) {
        return jdbcTemplate.update("update ai_locator_knowledge_entries set status = 'stale', updated_at = ? where status = 'active' and hit_count = 0 and collected_at < ?", Timestamp.from(Instant.now()), Timestamp.from(cutoff));
    }

    /** Stores evidence supplied by the agent's already-completed browser session without retaining HTML or credentials. */
    public void recordAgentEvidence(Long projectId, String environmentId, String moduleName, String pageName, String pageUrl, List<WebAutomationLocatorDiscoveryService.PageLocatorEvidence> elements) {
        recordAgentEvidence(projectId, environmentId, moduleName, pageName, pageUrl, elements, null);
    }

    public void recordAgentEvidence(Long projectId, String environmentId, String moduleName, String pageName, String pageUrl, List<WebAutomationLocatorDiscoveryService.PageLocatorEvidence> elements, ExplorationKnowledge explorationKnowledge) {
        ensureProjectAccess(projectId);
        try {
            Instant now = Instant.now();
            String fingerprint = computePageFingerprint(pageUrl, elements);
            String metadata = serializeExplorationKnowledge(explorationKnowledge, pageUrl, fingerprint);
            for (WebAutomationLocatorDiscoveryService.PageLocatorEvidence item : elements == null ? List.<WebAutomationLocatorDiscoveryService.PageLocatorEvidence>of() : elements) {
                Integer existing = jdbcTemplate.queryForObject("select count(*) from ai_locator_knowledge_entries where project_id = ? and environment_id = ? and module_name = ? and page_name = ? and locator = ?", Integer.class, projectId, value(environmentId), value(moduleName), value(pageName), item.locator());
                if (existing != null && existing > 0) {
                    if (explorationKnowledge == null) {
                        jdbcTemplate.update("update ai_locator_knowledge_entries set page_url = ?, page_fingerprint = ?, natural_name = ?, role_name = ?, accessible_name = ?, element_type = ?, status = 'active', last_verified_at = ?, updated_at = ? where project_id = ? and environment_id = ? and module_name = ? and page_name = ? and locator = ?", pageUrl, fingerprint, value(item.label()), value(item.role()), value(item.label()), value(item.type()), Timestamp.from(now), Timestamp.from(now), projectId, value(environmentId), value(moduleName), value(pageName), item.locator());
                    } else {
                        jdbcTemplate.update("update ai_locator_knowledge_entries set page_url = ?, page_fingerprint = ?, natural_name = ?, role_name = ?, accessible_name = ?, element_type = ?, status = 'active', last_verified_at = ?, metadata_json = ?, updated_at = ? where project_id = ? and environment_id = ? and module_name = ? and page_name = ? and locator = ?", pageUrl, fingerprint, value(item.label()), value(item.role()), value(item.label()), value(item.type()), Timestamp.from(now), metadata, Timestamp.from(now), projectId, value(environmentId), value(moduleName), value(pageName), item.locator());
                    }
                    continue;
                }
                String entryId = "locator-" + UUID.randomUUID();
                jdbcTemplate.update("insert into ai_locator_knowledge_entries(entry_id, project_id, environment_id, module_name, page_name, page_url, page_fingerprint, natural_name, locator, role_name, accessible_name, element_type, source, status, evidence_version, collected_at, last_verified_at, metadata_json, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'agent_browser', 'active', 1, ?, ?, ?, ?, ?)", entryId, projectId, value(environmentId), value(moduleName), value(pageName), pageUrl, fingerprint, value(item.label()), item.locator(), value(item.role()), value(item.label()), value(item.type()), Timestamp.from(now), Timestamp.from(now), metadata, Timestamp.from(now), Timestamp.from(now));
            }
        } catch (DataAccessException failure) {
            throw new WebLocatorKnowledgePersistenceException(pageUrl, failure);
        }
    }

    /** Compatibility overload for callers that place the optional knowledge before elements. */
    public void recordAgentEvidence(Long projectId, String environmentId, String moduleName, String pageName, String pageUrl, ExplorationKnowledge explorationKnowledge, List<WebAutomationLocatorDiscoveryService.PageLocatorEvidence> elements) {
        recordAgentEvidence(projectId, environmentId, moduleName, pageName, pageUrl, elements, explorationKnowledge);
    }

    public void markStale(String entryId) {
        try {
            LocatorKnowledgeView current = getView(entryId);
            if (current != null) ensureProjectAccess(current.projectId());
            jdbcTemplate.update("update ai_locator_knowledge_entries set status = 'stale', updated_at = ? where entry_id = ?", Timestamp.from(Instant.now()), entryId);
        } catch (DataAccessException failure) {
            throw new WebLocatorKnowledgePersistenceException(null, failure);
        }
    }

    public LocatorRevisionProposal createRepairProposal(
        String entryId,
        String replacementLocator,
        String pageFingerprintBefore,
        String pageFingerprintAfter,
        int confidence,
        String reason
    ) {
        return createRepairProposal(new CreateLocatorRepairProposalRequest(
            entryId, replacementLocator, "locator_not_found", pageFingerprintBefore,
            pageFingerprintAfter, confidence, reason, true, null, null, null, null
        ));
    }

    public LocatorRevisionProposal createRepairProposal(CreateLocatorRepairProposalRequest request) {
        if (request == null || request.entryId() == null || request.entryId().isBlank()
            || request.replacementLocator() == null || request.replacementLocator().isBlank()) {
            throw new IllegalArgumentException("Locator 修复提案缺少原始条目或新 Locator");
        }
        if (!REPAIR_FAILURE_CATEGORIES.contains(value(request.failureCategory()).strip())) {
            throw new IllegalArgumentException("unsupported repair failure category");
        }
        Actor actor = currentActor();
        LocatorKnowledgeView current = getView(request.entryId());
        ensureProjectAccess(current.projectId());
        Verification verification = requireVerification(request, current, actor.id());
        Instant now = Instant.now();
        String proposalId = "repair-" + UUID.randomUUID();
        int confidence = Math.max(0, Math.min(100, request.confidence()));
        Integer revision = jdbcTemplate.queryForObject("select coalesce(max(revision), 1) from ai_locator_knowledge_history where entry_id = ?", Integer.class, request.entryId());
        int nextRevision = (revision == null ? 1 : revision) + 1;
        String newLocator = request.replacementLocator().trim();
        String metadata = objectMapper.createObjectNode()
            .put("proposalId", proposalId).put("oldLocator", value(current.locator())).put("newLocator", newLocator)
            .put("failureCategory", request.failureCategory()).put("pageFingerprintBefore", value(request.pageFingerprintBefore()))
            .put("pageFingerprintAfter", verification.pageFingerprint()).put("confidence", confidence)
            .put("reason", value(request.reason())).put("revision", nextRevision).put("status", "pending_review").toString();
        int consumed = jdbcTemplate.update("update ai_locator_repair_verifications set used_at = ? where verification_id = ? and used_at is null and expires_at > ?",
            Timestamp.from(now), verification.verificationId(), Timestamp.from(now));
        if (consumed != 1) throw new IllegalStateException("repair verification is no longer usable");
        try {
            jdbcTemplate.update("insert into ai_locator_repair_proposals(proposal_id, entry_id, project_id, failure_category, old_locator, new_locator, page_fingerprint_before, page_fingerprint_after, confidence, reason, verification_json, revision, status, verification_id, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending_review', ?, ?, ?)",
                proposalId, request.entryId(), current.projectId(), request.failureCategory(), current.locator(), newLocator,
                current.pageFingerprint(), verification.pageFingerprint(), confidence, value(request.reason()), metadata, nextRevision,
                verification.verificationId(), Timestamp.from(now), Timestamp.from(now));
        } catch (DataAccessException failure) {
            jdbcTemplate.update("update ai_locator_repair_verifications set used_at = null where verification_id = ? and used_at = ?", verification.verificationId(), Timestamp.from(now));
            throw failure;
        }
        return mapProposal(proposalId);
    }

    public LocatorRepairCandidateVerification verifyRepairCandidate(String entryId, String candidateLocator, WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest request) {
        return verifyRepairCandidate(entryId, candidateLocator, null, null, request);
    }

    public LocatorRepairCandidateVerification verifyRepairCandidate(String entryId, String candidateLocator, String sourcePointId, String pageKey, WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest request) {
        if (entryId == null || entryId.isBlank() || candidateLocator == null || candidateLocator.isBlank() || request == null) {
            throw new IllegalArgumentException("repair verification requires entry, candidate, and browser request");
        }
        Actor actor = currentActor();
        LocatorKnowledgeView current = getView(entryId);
        ensureProjectAccess(current.projectId());
        String requestedSourcePointId = value(sourcePointId).strip();
        String requestSourcePointId = value(request.sourcePointId()).strip();
        if (!requestedSourcePointId.isBlank() && !requestSourcePointId.isBlank() && !requestedSourcePointId.equals(requestSourcePointId)) {
            throw new IllegalArgumentException("repair verification source point mismatch");
        }
        var evidence = discoveryService.discover(request);
        List<WebAutomationLocatorDiscoveryService.PageLocatorEvidence> matches = evidence == null || evidence.elements() == null
            ? List.of()
            : evidence.elements().stream().filter(item -> item != null && candidateLocator.equals(item.locator())).toList();
        boolean found = matches.size() == 1;
        WebAutomationLocatorDiscoveryService.PageLocatorEvidence match = found ? matches.get(0) : null;
        // 唯一性维度必须落库（复核方第十九轮 A2.21/A2.22）：布尔列只描述单个元素的状态，
        // 多元素命中（matchedElementCount≠1）时四个布尔仍是 true，创建/接受必须在写路径复检计数。
        int matchedElementCount = found
            ? (match.matchedElementCount() == null ? 0 : match.matchedElementCount())
            : matches.size();
        boolean candidateStateValid = found
            && Boolean.TRUE.equals(match.visible())
            && Boolean.TRUE.equals(match.enabled())
            && Boolean.TRUE.equals(match.receivesEvents())
            && matchedElementCount == 1;
        String fingerprint = computePageFingerprint(evidence == null ? "" : evidence.pageUrl(), evidence == null ? List.of() : evidence.elements());
        String actualPageKey = normalizePageKey(evidence == null ? "" : evidence.pageUrl());
        String requestedPageKey = normalizePageKey(pageKey);
        boolean requestedPageKeyMatches = value(pageKey).isBlank() || (!requestedPageKey.isBlank() && requestedPageKey.equals(actualPageKey));
        boolean correctPage = samePage(current.pageUrl(), evidence == null ? "" : evidence.pageUrl()) && requestedPageKeyMatches;
        boolean verified = candidateStateValid && correctPage;
        Instant now = Instant.now();
        String verificationId = "verification-" + UUID.randomUUID();
        jdbcTemplate.update("insert into ai_locator_repair_verifications(verification_id, entry_id, project_id, environment_id, source_point_id, page_key, page_fingerprint, page_url, candidate_locator, owner_id, expires_at, verified_at, visible, enabled, receives_events, correct_page, matched_element_count, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            verificationId, entryId, current.projectId(), value(current.environmentId()), requestedSourcePointId.isBlank() ? requestSourcePointId : requestedSourcePointId, actualPageKey, fingerprint,
            evidence == null ? "" : value(evidence.pageUrl()), candidateLocator, actor.id(), Timestamp.from(now.plusSeconds(300)), Timestamp.from(now),
            found && Boolean.TRUE.equals(match == null ? null : match.visible()), found && Boolean.TRUE.equals(match == null ? null : match.enabled()),
            found && Boolean.TRUE.equals(match == null ? null : match.receivesEvents()), correctPage, matchedElementCount, Timestamp.from(now));
        String failure = verified ? null : !found ? "locator_not_found" : !candidateStateValid ? "locator_not_actionable" : "wrong_page";
        return new LocatorRepairCandidateVerification(verified, candidateLocator, current.pageFingerprint(), fingerprint, failure, verificationId);
    }

    public LocatorRepairProposalListResponse listRepairProposals(Long projectId, String status) {
        ensureProjectAccess(projectId);
        return new LocatorRepairProposalListResponse(jdbcTemplate.query(
            "select proposal_id from ai_locator_repair_proposals where project_id = ? and (? = '' or status = ?) order by created_at desc",
            (rs, rowNum) -> mapProposal(rs.getString("proposal_id")), projectId, value(status), value(status)));
    }

    public LocatorRevisionProposal acceptRepairProposal(String proposalId, ReviewLocatorRepairProposalRequest request) {
        return reviewRepairProposal(proposalId, request, "accepted");
    }

    public LocatorRevisionProposal rejectRepairProposal(String proposalId, ReviewLocatorRepairProposalRequest request) {
        return reviewRepairProposal(proposalId, request, "rejected");
    }

    private LocatorRevisionProposal reviewRepairProposal(String proposalId, ReviewLocatorRepairProposalRequest request, String targetStatus) {
        Actor actor = currentActor();
        LocatorRevisionProposal before = mapProposalForUpdate(proposalId);
        if (!"pending_review".equals(before.status())) throw new IllegalStateException("repair proposal is not pending_review");
        ensureProjectAccess(projectIdForProposal(proposalId));
        Verification verification = "accepted".equals(targetStatus) ? requireStoredVerification(proposalId) : null;
        LocatorKnowledgeView entry = getView(before.entryId(), true);
        if (!entry.locator().equals(before.oldLocator()) || !entry.pageFingerprint().equals(before.pageFingerprintBefore())) throw new IllegalStateException("repair proposal no longer matches current locator revision");
        Instant now = Instant.now();
        String actorId = actor.id();
        String actorName = actor.name();
        String comment = request == null ? "" : value(request.comment());
        if ("accepted".equals(targetStatus)) {
            int changed = jdbcTemplate.update("update ai_locator_knowledge_entries set locator = ?, page_fingerprint = ?, evidence_version = evidence_version + 1, last_verified_at = ?, updated_at = ? where entry_id = ? and locator = ? and page_fingerprint = ?",
                before.newLocator(), before.pageFingerprintAfter(), Timestamp.from(now), Timestamp.from(now), before.entryId(), before.oldLocator(), before.pageFingerprintBefore());
            if (changed != 1) throw new IllegalStateException("locator revision changed concurrently");
            addHistory(before.entryId(), before.newLocator(), "repair_accept:" + proposalId, now, before.revision());
        }
        int changed = jdbcTemplate.update("update ai_locator_repair_proposals set status = ?, reviewed_by = ?, reviewed_name = ?, review_comment = ?, reviewed_at = ?, updated_at = ? where proposal_id = ? and status = 'pending_review'",
            targetStatus, actorId, actorName, comment, Timestamp.from(now), Timestamp.from(now), proposalId);
        if (changed != 1) throw new IllegalStateException("repair proposal is no longer pending_review");
        LocatorRevisionProposal after = mapProposal(proposalId);
        try {
            jdbcTemplate.update("insert into ai_locator_repair_audit(audit_id, proposal_id, action_type, before_json, after_json, actor_id, actor_name, comment, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "audit-" + UUID.randomUUID(), proposalId, targetStatus.equals("accepted") ? "accept" : "reject",
                objectMapper.writeValueAsString(before), objectMapper.writeValueAsString(after), actorId, actorName, comment, Timestamp.from(now));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
        return after;
    }

    private LocatorRevisionProposal mapProposal(String proposalId) {
        return jdbcTemplate.queryForObject("select proposal_id, entry_id, old_locator, new_locator, page_fingerprint_before, page_fingerprint_after, confidence, status, reason, revision, created_at from ai_locator_repair_proposals where proposal_id = ?",
            (rs, rowNum) -> new LocatorRevisionProposal(rs.getString("proposal_id"), rs.getString("entry_id"), rs.getString("old_locator"), rs.getString("new_locator"), rs.getString("page_fingerprint_before"), rs.getString("page_fingerprint_after"), rs.getInt("confidence"), rs.getString("status"), rs.getString("reason"), rs.getInt("revision"), rs.getTimestamp("created_at").toInstant().toString()), proposalId);
    }

    private LocatorRevisionProposal mapProposalForUpdate(String proposalId) {
        return jdbcTemplate.queryForObject("select proposal_id, entry_id, old_locator, new_locator, page_fingerprint_before, page_fingerprint_after, confidence, status, reason, revision, created_at from ai_locator_repair_proposals where proposal_id = ? for update",
            (rs, rowNum) -> new LocatorRevisionProposal(rs.getString("proposal_id"), rs.getString("entry_id"), rs.getString("old_locator"), rs.getString("new_locator"), rs.getString("page_fingerprint_before"), rs.getString("page_fingerprint_after"), rs.getInt("confidence"), rs.getString("status"), rs.getString("reason"), rs.getInt("revision"), rs.getTimestamp("created_at").toInstant().toString()), proposalId);
    }

    private long projectIdForProposal(String proposalId) {
        Long projectId = jdbcTemplate.queryForObject("select project_id from ai_locator_repair_proposals where proposal_id = ?", Long.class, proposalId);
        if (projectId == null) throw new IllegalArgumentException("repair proposal not found");
        return projectId;
    }

    private Verification requireVerification(CreateLocatorRepairProposalRequest request, LocatorKnowledgeView current, String ownerId) {
        if (request.verificationId() == null || request.verificationId().isBlank()) throw new IllegalArgumentException("repair verificationId is required");
        Verification verification = jdbcTemplate.queryForObject("select verification_id, entry_id, project_id, environment_id, source_point_id, page_key, page_fingerprint, candidate_locator, owner_id, expires_at, visible, enabled, receives_events, correct_page, matched_element_count from ai_locator_repair_verifications where verification_id = ? and used_at is null",
            (rs, rowNum) -> new Verification(rs.getString("verification_id"), rs.getString("entry_id"), rs.getLong("project_id"), rs.getString("environment_id"), rs.getString("source_point_id"), rs.getString("page_key"), rs.getString("page_fingerprint"), rs.getString("candidate_locator"), rs.getString("owner_id"), rs.getTimestamp("expires_at").toInstant(), null, rs.getBoolean("visible"), rs.getBoolean("enabled"), rs.getBoolean("receives_events"), rs.getBoolean("correct_page"), rs.getInt("matched_element_count")), request.verificationId());
        boolean environmentMatches = value(request.environmentId()).isBlank() || value(request.environmentId()).equals(value(verification.environmentId()));
        boolean sourceMatches = value(request.sourcePointId()).isBlank() || value(request.sourcePointId()).equals(value(verification.sourcePointId()));
        boolean pageKeyMatches = value(request.pageKey()).isBlank() || normalizePageKey(request.pageKey()).equals(normalizePageKey(verification.pageKey()));
        boolean beforeMatches = value(request.pageFingerprintBefore()).isBlank() || value(request.pageFingerprintBefore()).equals(value(current.pageFingerprint()));
        boolean afterMatches = value(request.pageFingerprintAfter()).isBlank() || value(request.pageFingerprintAfter()).equals(value(verification.pageFingerprint()));
        if (!verification.entryId().equals(current.entryId()) || verification.projectId() != current.projectId() || !value(current.environmentId()).equals(value(verification.environmentId()))
            || !verification.ownerId().equals(ownerId) || !verification.candidateLocator().equals(request.replacementLocator()) || !environmentMatches || !sourceMatches || !pageKeyMatches
            || !beforeMatches || !afterMatches || verification.expiresAt().isBefore(Instant.now()) || !verification.visible() || !verification.enabled() || !verification.receivesEvents() || !verification.correctPage()
            || verification.matchedElementCount() != 1) {
            throw new IllegalArgumentException("repair verification is invalid, expired, or mismatched");
        }
        return verification;
    }

    private Verification requireStoredVerification(String proposalId) {
        Verification verification = jdbcTemplate.queryForObject("select v.verification_id, v.entry_id, v.project_id, v.environment_id, v.source_point_id, v.page_key, v.page_fingerprint, v.candidate_locator, v.owner_id, v.expires_at, v.used_at, v.visible, v.enabled, v.receives_events, v.correct_page, v.matched_element_count from ai_locator_repair_verifications v join ai_locator_repair_proposals p on p.verification_id = v.verification_id where p.proposal_id = ? for update", (rs, rowNum) -> new Verification(rs.getString("verification_id"), rs.getString("entry_id"), rs.getLong("project_id"), rs.getString("environment_id"), rs.getString("source_point_id"), rs.getString("page_key"), rs.getString("page_fingerprint"), rs.getString("candidate_locator"), rs.getString("owner_id"), rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("used_at") == null ? null : rs.getTimestamp("used_at").toInstant(), rs.getBoolean("visible"), rs.getBoolean("enabled"), rs.getBoolean("receives_events"), rs.getBoolean("correct_page"), rs.getInt("matched_element_count")), proposalId);
        // 只复核「候选当时是否被证成」：过期时间只约束提案创建（requireVerification），
        // 人工评审可能晚于 300s 窗口到达——accept 复检 TTL 会无谓阻断合法确认（复核方第十九轮 A4.17）。
        if (!verification.visible() || !verification.enabled() || !verification.receivesEvents() || !verification.correctPage() || verification.matchedElementCount() != 1) {
            throw new IllegalStateException("repair verification is no longer valid");
        }
        return verification;
    }

    private void ensureProjectAccess(long projectId) {
        projectAccessService.requireAccess(projectId);
    }

    private void authorizeEntryIds(List<String> entryIds) {
        List<String> ids = entryIds == null ? List.of() : entryIds.stream()
            .filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) {
            currentActor();
            return;
        }
        List<Long> projectIds = jdbcTemplate.queryForList(
            "select project_id from ai_locator_knowledge_entries where entry_id in (" + placeholders(ids.size()) + ")",
            Long.class, ids.toArray());
        if (projectIds.size() != ids.size()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问项目");
        }
        projectIds.forEach(this::ensureProjectAccess);
    }

    private void authorizeUsage(String runId, String pointId) {
        List<Long> projectIds = jdbcTemplate.queryForList(
            "select distinct e.project_id from ai_agent_asset_usage u join ai_locator_knowledge_entries e on e.entry_id = u.asset_id where u.run_id = ? and u.point_id = ? and u.asset_type = 'locator'",
            Long.class, runId, pointId);
        if (projectIds.isEmpty()) {
            currentActor();
            return;
        }
        projectIds.forEach(this::ensureProjectAccess);
    }

    /** Authorizes run-scoped reads and writes before an asset usage lookup. */
    public void authorizeRun(String runId) {
        Long projectId = jdbcTemplate.queryForObject(
            "select project_id from ai_web_case_agent_runs where run_id = ?", Long.class, runId);
        if (projectId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问项目");
        ensureProjectAccess(projectId);
    }

    /** Authorizes artifact-scoped plan reads and writes. */
    public void authorizeArtifact(String artifactId) {
        Long projectId = jdbcTemplate.queryForObject(
            "select r.project_id from ai_web_case_agent_artifacts a join ai_web_case_agent_runs r on r.run_id = a.run_id where a.artifact_id = ?",
            Long.class, artifactId);
        if (projectId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权访问项目");
        ensureProjectAccess(projectId);
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private Actor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        return new Actor(authentication.getName(), authentication.getName());
    }

    private boolean samePage(String expected, String actual) { return !normalizePageKey(expected).isBlank() && normalizePageKey(expected).equals(normalizePageKey(actual)); }
    private String normalizePageKey(String rawUrl) {
        try {
            URI uri = new URI(value(rawUrl));
            if (uri.getHost() == null || uri.getScheme() == null) return "";
            String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath().replaceAll("/+$", "");
            if (path.isBlank()) path = "/";
            String fragment = uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment().split("[?#]", 2)[0];
            return uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://" + uri.getHost().toLowerCase(java.util.Locale.ROOT)
                + (uri.getPort() > 0 ? ":" + uri.getPort() : "") + path + fragment;
        } catch (URISyntaxException exception) {
            return "";
        }
    }
    private record Actor(String id, String name) { }
    private record Verification(String verificationId, String entryId, long projectId, String environmentId, String sourcePointId, String pageKey, String pageFingerprint, String candidateLocator, String ownerId, Instant expiresAt, Instant usedAt, boolean visible, boolean enabled, boolean receivesEvents, boolean correctPage, int matchedElementCount) { }

    public record LocatorRepairCandidateVerification(boolean verified, String candidateLocator, String pageFingerprintBefore, String pageFingerprintAfter, String failureCategory, String verificationId) {
        public LocatorRepairCandidateVerification(boolean verified, String candidateLocator, String pageFingerprintBefore, String pageFingerprintAfter, String failureCategory) { this(verified, candidateLocator, pageFingerprintBefore, pageFingerprintAfter, failureCategory, null); }
    }

    public record LocatorRevisionProposal(
        String proposalId,
        String entryId,
        String oldLocator,
        String newLocator,
        String pageFingerprintBefore,
        String pageFingerprintAfter,
        int confidence,
        String status,
        String reason,
        int revision,
        String createdAt
    ) { }

    public LocatorKnowledgeView create(SaveLocatorKnowledgeRequest request) {
        validate(request);
        ensureProjectAccess(request.projectId());
        Instant now = Instant.now();
        String entryId = "locator-" + UUID.randomUUID();
        jdbcTemplate.update("insert into ai_locator_knowledge_entries(entry_id, project_id, environment_id, module_name, page_name, page_url, page_fingerprint, natural_name, locator, role_name, accessible_name, element_type, source, status, evidence_version, collected_at, last_verified_at, metadata_json, created_at, updated_at) values (?, ?, ?, ?, ?, ?, '', ?, ?, ?, ?, ?, 'manual', 'active', 1, ?, ?, '{}', ?, ?)", entryId, request.projectId(), value(request.environmentId()), value(request.moduleName()), value(request.pageName()), value(request.pageUrl()), value(request.naturalName()), request.locator().trim(), value(request.roleName()), value(request.accessibleName()), value(request.elementType()), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
        addHistory(entryId, request.locator(), "manual_create", now);
        return getView(entryId);
    }

    public LocatorKnowledgeView update(String entryId, SaveLocatorKnowledgeRequest request) {
        validate(request);
        LocatorKnowledgeView before = getView(entryId);
        ensureProjectAccess(before.projectId());
        if (!before.projectId().equals(request.projectId())) throw new IllegalArgumentException("不能通过更新转移项目");
        if (before.confidenceLocked()) throw new IllegalStateException("高置信度素材已锁定，不能直接修改；请先停用并重新采集或新增替代素材");
        Instant now = Instant.now();
        jdbcTemplate.update("update ai_locator_knowledge_entries set project_id = ?, environment_id = ?, module_name = ?, page_name = ?, page_url = ?, natural_name = ?, locator = ?, role_name = ?, accessible_name = ?, element_type = ?, status = 'active', evidence_version = evidence_version + 1, last_verified_at = ?, updated_at = ? where entry_id = ?", request.projectId(), value(request.environmentId()), value(request.moduleName()), value(request.pageName()), value(request.pageUrl()), value(request.naturalName()), request.locator().trim(), value(request.roleName()), value(request.accessibleName()), value(request.elementType()), Timestamp.from(now), Timestamp.from(now), entryId);
        if (before.locator() != null && !before.locator().equals(request.locator().trim())) addHistory(entryId, request.locator(), "manual_update", now);
        return getView(entryId);
    }

    private void validate(SaveLocatorKnowledgeRequest request) {
        if (request == null || request.projectId() == null || request.locator() == null || request.locator().isBlank()) throw new IllegalArgumentException("项目和 Locator 不能为空");
    }

    private void addHistory(String entryId, String locator, String reason, Instant now) {
        addHistory(entryId, locator, reason, now, null);
    }

    private void addHistory(String entryId, String locator, String reason, Instant now, Integer revision) {
        String safeReason = limit(reason, 255);
        if (revision == null) {
            jdbcTemplate.update("insert into ai_locator_knowledge_history(history_id, entry_id, snapshot_json, changed_reason, created_at) values (?, ?, ?, ?, ?)", "history-" + UUID.randomUUID(), entryId, "{\"locator\":\"" + escape(locator) + "\"}", safeReason, Timestamp.from(now));
        } else {
            jdbcTemplate.update("insert into ai_locator_knowledge_history(history_id, entry_id, snapshot_json, changed_reason, revision, created_at) values (?, ?, ?, ?, ?, ?)", "history-" + UUID.randomUUID(), entryId, "{\"locator\":\"" + escape(locator) + "\"}", safeReason, revision, Timestamp.from(now));
        }
    }

    private LocatorKnowledgeView getView(String entryId) {
        return getView(entryId, false);
    }

    private LocatorKnowledgeView getView(String entryId, boolean forUpdate) {
        String sql = "select * from ai_locator_knowledge_entries where entry_id = ?" + (forUpdate ? " for update" : "");
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new LocatorKnowledgeView(rs.getString("entry_id"), rs.getLong("project_id"), rs.getString("environment_id"), rs.getString("module_name"), rs.getString("page_name"), rs.getString("page_url"), rs.getString("page_fingerprint"), rs.getString("natural_name"), rs.getString("locator"), rs.getString("role_name"), rs.getString("accessible_name"), rs.getString("element_type"), rs.getString("source"), rs.getString("status"), rs.getInt("evidence_version"), rs.getTimestamp("collected_at").toInstant().toString(), rs.getTimestamp("last_verified_at") == null ? null : rs.getTimestamp("last_verified_at").toInstant().toString(), rs.getInt("hit_count"), rs.getInt("execution_success_count"), rs.getInt("execution_failure_count"), rs.getInt("confidence_score"), rs.getBoolean("confidence_locked"), rs.getTimestamp("last_hit_at") == null ? null : rs.getTimestamp("last_hit_at").toInstant().toString()), entryId);
    }

    private void refreshQuality(String entryId, int successIncrement, int failureIncrement, Instant now) {
        LocatorKnowledgeView current = getView(entryId, true);
        int hits = current.hitCount();
        int successes = current.executionSuccessCount() + successIncrement;
        int failures = current.executionFailureCount() + failureIncrement;
        int recalculated = confidence(hits, successes, failures);
        int confidence = recalculated;
        if (successIncrement > 0) {
            // Preserve a strong historical score while rewarding a fresh browser
            // confirmation; stale plan data must never reset confidence downward.
            confidence = Math.min(100, Math.max(recalculated, current.confidenceScore()) + 3);
        } else if (failureIncrement > 0) {
            // A failed eligible UI validation reduces confidence gradually; the
            // repeated-failure threshold still controls retirement.
            confidence = Math.max(0, current.confidenceScore() - 10);
        } else {
            confidence = Math.max(recalculated, current.confidenceScore());
        }
        jdbcTemplate.update("update ai_locator_knowledge_entries set execution_success_count = ?, execution_failure_count = ?, confidence_score = ?, confidence_locked = ?, updated_at = ? where entry_id = ?", successes, failures, confidence, confidence >= 85, Timestamp.from(now), entryId);
    }

    private int confidence(int hits, int successes, int failures) { int executionScore = successes + failures == 0 ? 0 : (int) Math.round(30D * successes / (successes + failures)); return Math.min(100, 35 + Math.min(35, hits * 5) + executionScore); }

    public void revalidate(String entryId, com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest request) {
        ensureProjectAccess(getView(entryId).projectId());
        LocatorKnowledgeView entry = jdbcTemplate.queryForObject(
            "select * from ai_locator_knowledge_entries where entry_id = ?",
            (rs, rowNum) -> new LocatorKnowledgeView(rs.getString("entry_id"), rs.getLong("project_id"), rs.getString("environment_id"), rs.getString("module_name"), rs.getString("page_name"), rs.getString("page_url"), rs.getString("page_fingerprint"), rs.getString("natural_name"), rs.getString("locator"), rs.getString("role_name"), rs.getString("accessible_name"), rs.getString("element_type"), rs.getString("source"), rs.getString("status"), rs.getInt("evidence_version"), rs.getTimestamp("collected_at").toInstant().toString(), rs.getTimestamp("last_verified_at") == null ? null : rs.getTimestamp("last_verified_at").toInstant().toString(), rs.getInt("hit_count"), rs.getInt("execution_success_count"), rs.getInt("execution_failure_count"), rs.getInt("confidence_score"), rs.getBoolean("confidence_locked"), rs.getTimestamp("last_hit_at") == null ? null : rs.getTimestamp("last_hit_at").toInstant().toString()), entryId
        );
        WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse evidence = discoveryService.discover(request);
        String currentFingerprint = computePageFingerprint(evidence.pageUrl(), evidence.elements());
        if (!value(entry.pageFingerprint()).isBlank() && !entry.pageFingerprint().equals(currentFingerprint)) {
            log.warn("页面结构已变化(指纹不一致), locator {} 旧指纹 {} 新指纹 {}", entry.locator(), entry.pageFingerprint(), currentFingerprint);
            String metadata = jdbcTemplate.queryForObject("select metadata_json from ai_locator_knowledge_entries where entry_id = ?", String.class, entryId);
            if (hasExplorationMetadata(metadata)) {
                markStale(entryId);
                throw new IllegalArgumentException("页面结构已变化，已退役 locator: " + entry.locator());
            }
        }
        boolean found = evidence.elements().stream().anyMatch(item -> item.locator().equals(entry.locator()));
        if (!found) {
            markStale(entryId);
            throw new IllegalArgumentException("当前浏览器未重新验证 locator: " + entry.locator());
        }
        jdbcTemplate.update("update ai_locator_knowledge_entries set page_url = ?, page_fingerprint = ?, status = 'active', last_verified_at = ?, updated_at = ? where entry_id = ?", evidence.pageUrl(), currentFingerprint, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), entryId);
    }

    private boolean hasExplorationMetadata(String metadata) {
        if (metadata == null || metadata.isBlank()) return false;
        try {
            JsonNode root = objectMapper.readTree(metadata);
            return root != null && root.isObject() && root.has("navigationTrail") && root.has("status");
        } catch (Exception exception) {
            return false;
        }
    }

    private String value(String input) { return input == null ? "" : input; }

    private String serializeExplorationKnowledge(ExplorationKnowledge knowledge, String fallbackPageUrl, String fallbackFingerprint) {
        if (knowledge == null) return "{}";
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.set("navigationTrail", objectMapper.valueToTree(sanitizeNavigationTrail(knowledge.navigationTrail(), knowledge.pageUrl(), fallbackPageUrl)));
        metadata.put("totalScore", knowledge.totalScore());
        metadata.putObject("scores").put("totalScore", knowledge.totalScore());
        metadata.put("rounds", Math.max(0, knowledge.rounds()));
        metadata.put("status", limit(value(knowledge.status()).strip(), 64));
        String fingerprint = value(knowledge.pageFingerprint()).strip();
        if (fingerprint.isBlank()) fingerprint = value(fallbackFingerprint).strip();
        if (!fingerprint.isBlank()) metadata.put("pageFingerprint", limit(fingerprint, 255));
        String pageUrl = sanitizeSameSiteUrl(value(knowledge.pageUrl()).isBlank() ? fallbackPageUrl : knowledge.pageUrl(), fallbackPageUrl);
        if (!pageUrl.isBlank()) metadata.put("pageUrl", pageUrl);
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception exception) {
            log.warn("无法序列化探索知识，使用空 metadata", exception);
            return "{}";
        }
    }

    private List<String> sanitizeNavigationTrail(List<String> trail, String knowledgePageUrl, String fallbackPageUrl) {
        if (trail == null || trail.isEmpty()) return List.of();
        String sameSiteUrl = value(knowledgePageUrl).isBlank() ? fallbackPageUrl : knowledgePageUrl;
        List<String> safe = new ArrayList<>();
        for (String raw : trail) {
            String item = value(raw).strip();
            if (item.isBlank() || containsSecretToken(item) || item.contains("<") || item.contains(">")) continue;
            if (looksLikeUrl(item)) {
                String url = sanitizeSameSiteUrl(item, sameSiteUrl);
                if (url.isBlank()) continue;
                safe.add(limit(url, 512));
            } else {
                safe.add(limit(item, 256));
            }
            if (safe.size() >= 50) break;
        }
        return List.copyOf(safe);
    }

    private String sanitizeSameSiteUrl(String rawUrl, String pageUrl) {
        String candidate = value(rawUrl).strip();
        if (candidate.isBlank()) return "";
        if (candidate.startsWith("/") || candidate.startsWith("#/")) return candidate.split("\\?", 2)[0];
        try {
            URI target = new URI(candidate);
            URI base = new URI(value(pageUrl));
            if (target.getHost() == null || base.getHost() == null || !target.getHost().equalsIgnoreCase(base.getHost())) return "";
            String scheme = target.getScheme();
            if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return "";
            String path = target.getRawPath() == null ? "/" : target.getRawPath();
            String fragment = target.getRawFragment() == null ? "" : "#" + target.getRawFragment().split("\\?", 2)[0];
            String authority = target.getHost() + (target.getPort() > 0 ? ":" + target.getPort() : "");
            return limit(scheme + "://" + authority + path + fragment, 512);
        } catch (URISyntaxException exception) {
            return "";
        }
    }

    private boolean looksLikeUrl(String value) {
        return value.startsWith("/") || value.startsWith("#/") || value.matches("(?i)^https?://.*");
    }

    private boolean containsSecretToken(String value) {
        String normalized = value.replace("_", "").replace("-", "").toLowerCase(java.util.Locale.ROOT);
        return normalized.matches(".*(password|passwd|secret|secretkey|token|authtoken|accesstoken|apikey|authorization|headers?|credential|username).*" )
            || value.contains("{{") && value.contains("}}");
    }

    private String escapeLike(String input) {
        // 与 KNOWLEDGE_ROWS_SQL 的 `escape '!'` 配套：! 自身、% 与 _ 均用 ! 前缀转义；反斜杠是普通字符，无需处理。
        return value(input).replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private String limit(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : value(second);
    }
    private String normalizePageFamily(String value) {
        String normalized = value(value).trim();
        if (normalized.isBlank()) return "";
        return normalized
            .replace("页面", "")
            .replace("页", "")
            .replace("列表", "")
            .replace("详情", "")
            .replace("管理页", "管理")
            .replace("界面", "")
            .replace("视图", "");
    }
    private String escape(String input) { return value(input).replace("\\", "\\\\").replace("\"", "\\\""); }

    /** 页面的稳定结构指纹:对 (locator|role|tagName) 去重排序后哈希,不随元素顺序或 label 文案漂移。 */
    private String computePageFingerprint(String pageUrl, List<WebAutomationLocatorDiscoveryService.PageLocatorEvidence> elements) {
        String canonical = elements == null ? "" : elements.stream()
            .map(item -> value(item.locator()) + "|" + value(item.role()) + "|" + value(item.tagName()))
            .filter(sig -> !"||".equals(sig))
            .distinct()
            .sorted()
            .collect(Collectors.joining("\n"));
        String input = value(pageUrl) + "\n" + canonical;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
