package com.haitlify.aitest.platform.ai.quality;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/web-case-agent-runs")
class WebCaseQualityRatingController {

    private final WebCaseQualityRatingService ratingService;
    private final WebCaseRegressionComparisonService comparisonService;
    private final WebCaseQualityInsightService insightService;

    WebCaseQualityRatingController(WebCaseQualityRatingService ratingService, WebCaseRegressionComparisonService comparisonService, WebCaseQualityInsightService insightService) {
        this.ratingService = ratingService;
        this.comparisonService = comparisonService;
        this.insightService = insightService;
    }

    @PostMapping("/{runId}/quality-rating")
    WebCaseQualityRatingResponse rate(@PathVariable String runId, @RequestBody RateWebCaseQualityRequest request) {
        return ratingService.rate(runId, request);
    }

    @GetMapping("/{runId}/quality-rating")
    WebCaseQualityRatingResponse get(@PathVariable String runId) {
        return ratingService.get(runId);
    }

    @GetMapping("/{runId}/regression-comparison")
    WebCaseRegressionComparisonResponse compare(@PathVariable String runId, @RequestParam(required = false) String baselineRunId) {
        return comparisonService.compare(runId, baselineRunId);
    }

    @GetMapping("/quality-ratings")
    WebCaseQualityRatingListResponse list(@RequestParam Long projectId) {
        return ratingService.list(projectId);
    }

    @GetMapping("/quality-insight")
    WebCaseQualityInsightResponse insight(@RequestParam Long projectId) {
        return insightService.insight(projectId);
    }
}
