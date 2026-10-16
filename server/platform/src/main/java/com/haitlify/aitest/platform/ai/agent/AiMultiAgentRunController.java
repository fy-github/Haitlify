package com.haitlify.aitest.platform.ai.agent;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/multi-agent-runs")
class AiMultiAgentRunController {

    private final AiMultiAgentRunService runService;
    private final AiAgentHandoffService handoffService;
    private final AiAgentCritiqueService critiqueService;
    private final AiAgentConflictService conflictService;
    private final AiAgentConsensusMergeService mergeService;

    AiMultiAgentRunController(
        AiMultiAgentRunService runService,
        AiAgentHandoffService handoffService,
        AiAgentCritiqueService critiqueService,
        AiAgentConflictService conflictService,
        AiAgentConsensusMergeService mergeService
    ) {
        this.runService = runService;
        this.handoffService = handoffService;
        this.critiqueService = critiqueService;
        this.conflictService = conflictService;
        this.mergeService = mergeService;
    }

    @PostMapping
    AiMultiAgentRunResponse createRun(@RequestBody CreateMultiAgentRunRequest request) {
        return runService.createRun(request);
    }

    @GetMapping("/{runId}")
    AiMultiAgentRunResponse getRun(@PathVariable String runId) {
        return runService.getRun(runId);
    }

    @GetMapping("/{runId}/graph")
    AiMultiAgentGraphResponse getGraph(@PathVariable String runId) {
        return runService.getGraph(runId);
    }

    @GetMapping("/{runId}/handoffs")
    AiAgentHandoffListResponse listHandoffs(@PathVariable String runId) {
        return handoffService.listHandoffs(runId);
    }

    @GetMapping("/{runId}/critiques")
    AiAgentCritiqueListResponse listCritiques(@PathVariable String runId) {
        return critiqueService.listCritiques(runId);
    }

    @GetMapping("/{runId}/conflicts")
    AiAgentConflictListResponse listConflicts(@PathVariable String runId) {
        return conflictService.listConflicts(runId);
    }

    @PostMapping("/{runId}/merge")
    AiAgentMergeResponse merge(@PathVariable String runId, @RequestBody MergeAgentRunRequest request) {
        return mergeService.merge(runId, request);
    }

    /** T09 bullet 8：人工中断与持久 checkpoint 恢复。 */
    @PostMapping("/{runId}/interrupt")
    AiMultiAgentRunResponse interrupt(@PathVariable String runId) {
        return runService.interruptRun(runId);
    }

    @PostMapping("/{runId}/resume")
    AiMultiAgentRunResponse resume(@PathVariable String runId) {
        return runService.resumeRun(runId);
    }
}
