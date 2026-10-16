package com.haitlify.aitest.platform.ai.agentic.locator;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/ai/agent-assets/locator-knowledge")
class LocatorKnowledgeController {

    private final LocatorKnowledgeService service;

    LocatorKnowledgeController(LocatorKnowledgeService service) { this.service = service; }

    @GetMapping
    LocatorKnowledgeListResponse list(@RequestParam Long projectId, @RequestParam(required = false) String environmentId, @RequestParam(required = false) String moduleName, @RequestParam(required = false) String pageName, @RequestParam(required = false) String keyword) {
        return service.list(projectId, environmentId, moduleName, pageName, keyword);
    }

    @PostMapping("/collect")
    LocatorKnowledgeListResponse collect(@RequestBody CollectLocatorKnowledgeRequest request) { return service.collect(request); }

    @PostMapping("/repair-proposals")
    LocatorKnowledgeService.LocatorRevisionProposal createRepairProposal(@RequestBody CreateLocatorRepairProposalRequest request) { return service.createRepairProposal(request); }

    @PostMapping("/repair-verifications")
    LocatorKnowledgeService.LocatorRepairCandidateVerification verifyRepairCandidate(@RequestBody VerifyLocatorRepairCandidateRequest request) {
        return service.verifyRepairCandidate(request.entryId(), request.candidateLocator(), request.sourcePointId(), request.pageKey(), request.discovery());
    }

    @GetMapping("/repair-proposals")
    LocatorRepairProposalListResponse listRepairProposals(@RequestParam Long projectId, @RequestParam(required = false) String status) { return service.listRepairProposals(projectId, status); }

    @PostMapping("/repair-proposals/{proposalId}/accept")
    LocatorKnowledgeService.LocatorRevisionProposal acceptRepairProposal(@PathVariable String proposalId, @RequestBody(required = false) ReviewLocatorRepairProposalRequest request) { return service.acceptRepairProposal(proposalId, request); }

    @PostMapping("/repair-proposals/{proposalId}/reject")
    LocatorKnowledgeService.LocatorRevisionProposal rejectRepairProposal(@PathVariable String proposalId, @RequestBody(required = false) ReviewLocatorRepairProposalRequest request) { return service.rejectRepairProposal(proposalId, request); }

    @PostMapping("/{entryId}/stale")
    void stale(@PathVariable String entryId) { service.markStale(entryId); }


    @PostMapping
    LocatorKnowledgeListResponse create(@RequestBody SaveLocatorKnowledgeRequest request) { return new LocatorKnowledgeListResponse(List.of(service.create(request))); }

    @PutMapping("/{entryId}")
    LocatorKnowledgeView update(@PathVariable String entryId, @RequestBody SaveLocatorKnowledgeRequest request) { return service.update(entryId, request); }

    @PostMapping("/{entryId}/revalidate")
    void revalidate(@PathVariable String entryId, @RequestBody com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest request) { service.revalidate(entryId, request); }
}
