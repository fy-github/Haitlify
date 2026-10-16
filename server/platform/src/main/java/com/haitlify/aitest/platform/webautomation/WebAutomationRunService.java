package com.haitlify.aitest.platform.webautomation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * WebAutomation 运行代理 + 执行结果的平台侧持久化（阶段 7 增量 2）。
 *
 * 运行由 runner 执行，平台在快照经过时把结果写入 {@code ai_web_automation_run_reports}：
 * - 创建时确定 project_id（请求可选携带；缺省为 NULL，行仍保留但不参与项目级指标）；
 * - 每次 getRun/controlRun 的快照更新状态/计数/耗时（终态 = completed/failed/stopped，由指标侧判定）；
 * - 以 run_id 唯一键幂等：重复轮询不产生重复行，project_id 保持创建时写入的值。
 */
@Service
class WebAutomationRunService {

    private final PlaywrightRunClient playwrightRunClient;
    private final JdbcTemplate jdbcTemplate;

    WebAutomationRunService(PlaywrightRunClient playwrightRunClient, JdbcTemplate jdbcTemplate) {
        this.playwrightRunClient = playwrightRunClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    WebAutomationRunSnapshotResponse createRun(WebAutomationRunRequest request) {
        WebAutomationRunSnapshotResponse snapshot = playwrightRunClient.createRun(request);
        persistSnapshot(snapshot, request == null ? null : request.projectId());
        return snapshot;
    }

    WebAutomationRunSnapshotResponse getRun(String runId) {
        WebAutomationRunSnapshotResponse snapshot = playwrightRunClient.getRun(runId);
        persistSnapshot(snapshot, null);
        return snapshot;
    }

    WebAutomationRunSnapshotResponse controlRun(String runId, String action) {
        WebAutomationRunSnapshotResponse snapshot = playwrightRunClient.controlRun(runId, action);
        persistSnapshot(snapshot, null);
        return snapshot;
    }

    WebAutomationCapabilitiesResponse capabilities() {
        return playwrightRunClient.capabilities();
    }

    WebAutomationSessionListResponse listSessions() {
        return playwrightRunClient.listSessions();
    }

    WebLoginDiscoverySnapshotResponse createLoginDiscovery(WebLoginDiscoveryRequest request) {
        return playwrightRunClient.createLoginDiscovery(request);
    }

    WebLoginDiscoverySnapshotResponse getLoginDiscovery(String discoveryId) {
        return playwrightRunClient.getLoginDiscovery(discoveryId);
    }

    WebLoginDiscoverySnapshotResponse confirmLoginDiscovery(
        String discoveryId,
        WebLoginDiscoveryConfirmRequest request
    ) {
        return playwrightRunClient.confirmLoginDiscovery(discoveryId, request);
    }

    WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse resumeEvidenceHandoff(
        String handoffId,
        WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest request
    ) {
        return playwrightRunClient.resumeEvidenceHandoff(handoffId, request);
    }

    void persistSnapshot(WebAutomationRunSnapshotResponse snapshot, Long projectId) {
        if (snapshot == null || snapshot.runId() == null || snapshot.runId().isBlank()) return;
        String status = snapshot.status() == null ? "" : snapshot.status().toLowerCase(Locale.ROOT);
        if (status.isBlank()) return;
        int total = snapshot.summary() == null ? 0 : snapshot.summary().total();
        int passed = snapshot.summary() == null ? 0 : snapshot.summary().passed();
        int failed = snapshot.summary() == null ? 0 : snapshot.summary().failed();
        int error = snapshot.summary() == null ? 0 : snapshot.summary().error();
        long totalDuration = snapshot.results() == null ? 0
            : snapshot.results().stream().mapToLong(item -> item == null ? 0 : item.durationMs()).sum();
        String sessionId = value(snapshot.sessionId());
        Timestamp now = Timestamp.from(Instant.now());
        try {
            jdbcTemplate.update("insert into ai_web_automation_run_reports(run_id, project_id, status, mode, selected_browser, total_cases, passed_cases, failed_cases, error_cases, total_case_duration_ms, session_id, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                snapshot.runId(), projectId, status, value(snapshot.mode()), value(snapshot.selectedBrowser()),
                total, passed, failed, error, totalDuration, sessionId, now, now);
        } catch (DuplicateKeyException duplicate) {
            jdbcTemplate.update("update ai_web_automation_run_reports set status = ?, total_cases = ?, passed_cases = ?, failed_cases = ?, error_cases = ?, total_case_duration_ms = ?, session_id = case when ? = '' then session_id else ? end, updated_at = ? where run_id = ?",
                status, total, passed, failed, error, totalDuration, sessionId, sessionId, now, snapshot.runId());
        }
    }

    private static String value(String input) {
        return input == null ? "" : input;
    }
}
