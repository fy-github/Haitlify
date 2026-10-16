package com.haitlify.aitest.platform.ai.analysis;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import com.haitlify.aitest.platform.ai.analysis.chunk.AiAnalysisChunkPlanningService;
import com.haitlify.aitest.platform.ai.analysis.chunk.AiAnalysisChunkPlanningService.AiAnalysisChunkPlanRequest;
import com.haitlify.aitest.platform.ai.analysis.chunk.AiAnalysisChunkPlanningService.AiAnalysisChunkPlanResponse;
import com.haitlify.aitest.platform.ai.event.AiRunEventListResponse;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api")
class AiAnalysisRunController {

    private final AiAnalysisRunService aiAnalysisRunService;
    private final AiAnalysisChunkPlanningService chunkPlanningService;
    private final AiAnalysisWorker aiAnalysisWorker;
    private final AiAnalysisRunDetailService detailService;
    private final AiAnalysisSchedulerMetricsService schedulerMetricsService;

    AiAnalysisRunController(
        AiAnalysisRunService aiAnalysisRunService,
        AiAnalysisChunkPlanningService chunkPlanningService,
        AiAnalysisWorker aiAnalysisWorker,
        AiAnalysisRunDetailService detailService,
        AiAnalysisSchedulerMetricsService schedulerMetricsService
    ) {
        this.aiAnalysisRunService = aiAnalysisRunService;
        this.chunkPlanningService = chunkPlanningService;
        this.aiAnalysisWorker = aiAnalysisWorker;
        this.detailService = detailService;
        this.schedulerMetricsService = schedulerMetricsService;
    }

    @GetMapping("/ai/scheduler-metrics")
    Map<String, Object> schedulerMetrics(@RequestParam(required = false) Long projectId) {
        return schedulerMetricsService.metrics(projectId);
    }

    @PostMapping("/ai/analysis-runs")
    AiAnalysisRunResponse createRun(@RequestBody CreateAiAnalysisRunRequest request) {
        return aiAnalysisRunService.createRun(request);
    }

    @GetMapping("/ai/analysis-runs/{runId}")
    AiAnalysisRunResponse getRun(@PathVariable String runId) {
        return aiAnalysisRunService.getRun(runId);
    }

    @GetMapping("/projects/{projectId}/ai-analysis-runs")
    AiAnalysisRunListResponse listProjectRuns(@PathVariable Long projectId) {
        return aiAnalysisRunService.listProjectRuns(projectId);
    }

    @PostMapping("/ai/analysis-runs/{runId}/retry")
    AiAnalysisRunResponse retryRun(@PathVariable String runId) {
        return aiAnalysisRunService.retryRun(runId);
    }

    @PostMapping("/ai/analysis-runs/{runId}/cancel")
    AiAnalysisRunResponse cancelRun(@PathVariable String runId) {
        return aiAnalysisRunService.cancelRun(runId);
    }

    @PostMapping("/ai/analysis-runs/{runId}/plan-chunks")
    AiAnalysisChunkPlanResponse planChunks(@PathVariable String runId, @RequestBody AiAnalysisChunkPlanRequest request) {
        return chunkPlanningService.planChunks(runId, request);
    }

    @PostMapping("/ai/analysis-runs/{runId}/resume")
    AiAnalysisWorkerResult resumeRun(@PathVariable String runId) {
        return aiAnalysisWorker.resumeRun(runId);
    }

    @GetMapping("/ai/analysis-runs/{runId}/coverage-summary")
    AiCoverageSummaryResponse coverageSummary(@PathVariable String runId) {
        return detailService.coverageSummary(runId);
    }

    @GetMapping("/ai/analysis-runs/{runId}/unclear-questions")
    AiUnclearQuestionListResponse unclearQuestions(@PathVariable String runId) {
        return detailService.unclearQuestions(runId);
    }

    @GetMapping("/ai/analysis-runs/{runId}/tool-calls")
    AiToolCallListResponse toolCalls(@PathVariable String runId) {
        return detailService.toolCalls(runId);
    }

    /** T11：afterSeq 断线续接——只补拉严格更新的事件，可安全轮询。 */
    @GetMapping("/ai/analysis-runs/{runId}/events")
    AiRunEventListResponse runEvents(@PathVariable String runId,
                                     @RequestParam(value = "afterSeq", required = false) Long afterSeq) {
        return detailService.runEvents(runId, afterSeq);
    }
}
