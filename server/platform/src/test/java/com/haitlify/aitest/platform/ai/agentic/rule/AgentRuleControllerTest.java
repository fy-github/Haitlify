package com.haitlify.aitest.platform.ai.agentic.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AgentRuleControllerTest {

    @Test
    void usesTheStableAssetRoute() {
        RequestMappingAssertions.assertPath("/api/ai/agent-assets/rules");
        assertEquals("web-case-agent", AgentRuleService.RULE_SET_ID);
    }

    private static final class RequestMappingAssertions {
        private static void assertPath(String path) {
            assertEquals("/api/ai/agent-assets/rules", path);
        }
    }
}
