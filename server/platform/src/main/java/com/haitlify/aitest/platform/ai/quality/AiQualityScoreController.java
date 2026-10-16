package com.haitlify.aitest.platform.ai.quality;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/multi-agent-runs")
class AiQualityScoreController {

    private final AiQualityScoreService qualityScoreService;

    AiQualityScoreController(AiQualityScoreService qualityScoreService) {
        this.qualityScoreService = qualityScoreService;
    }

    @GetMapping("/{runId}/quality-scores")
    AiQualityScoreListResponse listScores(@PathVariable String runId) {
        return qualityScoreService.listScores(runId);
    }
}
