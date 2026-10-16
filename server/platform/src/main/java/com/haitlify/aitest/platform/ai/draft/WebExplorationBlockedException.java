package com.haitlify.aitest.platform.ai.draft;

import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService.ExplorationReport;

/** Raised when browser exploration completed without matching the requested business target. */
public class WebExplorationBlockedException extends IllegalArgumentException {
    private final String pointId;
    private final String failureReason;
    private final ExplorationReport report;

    public WebExplorationBlockedException(String pointId, String failureReason, ExplorationReport report) {
        super(failureReason == null || failureReason.isBlank() ? "浏览器探索未命中目标元素" : failureReason);
        this.pointId = pointId;
        this.failureReason = failureReason == null ? "浏览器探索未命中目标元素" : failureReason;
        this.report = report;
    }

    public String pointId() { return pointId; }
    public String failureReason() { return failureReason; }
    public ExplorationReport report() { return report; }
}
