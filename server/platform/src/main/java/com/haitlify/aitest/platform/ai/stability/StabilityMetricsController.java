package com.haitlify.aitest.platform.ai.stability;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/web-case-stability")
class StabilityMetricsController {

    private final StabilityMetricsService metricsService;

    StabilityMetricsController(StabilityMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    /** 独立的项目级路径：不放在 /web-case-agent-runs/{runId} 下，避免与 runId 路径变量冲突。 */
    @GetMapping("/metrics")
    StabilityMetricsService.StabilityMetricsResponse metrics(@RequestParam Long projectId) {
        return metricsService.metrics(projectId);
    }
}
