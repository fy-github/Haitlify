package com.haitlify.aitest.platform.ai.agentic.locator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

class LocatorKnowledgeControllerTest {
    @Test
    void exposesRepairReviewEndpointsThroughService() {
        LocatorKnowledgeService service = mock(LocatorKnowledgeService.class);
        LocatorKnowledgeService.LocatorRevisionProposal proposal = new LocatorKnowledgeService.LocatorRevisionProposal(
            "repair-1", "entry-1", "button=查询", "button=搜索", "old", "new", 92, "pending_review", "locator failed", 2, "2026-09-09T00:00:00Z");
        when(service.acceptRepairProposal("repair-1", null)).thenReturn(proposal);
        LocatorKnowledgeController controller = new LocatorKnowledgeController(service);

        assertThat(controller.acceptRepairProposal("repair-1", null)).isSameAs(proposal);
        verify(service).acceptRepairProposal("repair-1", null);
    }

    @Test
    void listsRepairProposalsByProjectAndStatus() {
        LocatorKnowledgeService service = mock(LocatorKnowledgeService.class);
        LocatorRepairProposalListResponse response = new LocatorRepairProposalListResponse(List.of());
        when(service.listRepairProposals(7L, "pending_review")).thenReturn(response);
        LocatorKnowledgeController controller = new LocatorKnowledgeController(service);

        assertThat(controller.listRepairProposals(7L, "pending_review")).isSameAs(response);
        verify(service).listRepairProposals(7L, "pending_review");
    }
}
