package com.haitlify.aitest.platform.ai.agent;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiAgentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearOverrides() {
        jdbcTemplate.update("delete from ai_agent_definitions");
    }

    @Test
    void listsFiveBuiltInRoleAgents() throws Exception {
        mockMvc.perform(get("/api/ai/agents"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(5))
            .andExpect(jsonPath("$[*].agentId").value(hasItem("requirement-understanding")))
            .andExpect(jsonPath("$[*].agentId").value(hasItem("test-design")))
            .andExpect(jsonPath("$[*].agentId").value(hasItem("quality-review")))
            .andExpect(jsonPath("$[*].agentId").value(hasItem("automation-suggestion")))
            .andExpect(jsonPath("$[*].agentId").value(hasItem("merge-coordinator")))
            .andExpect(jsonPath("$[0].builtIn").value(true))
            .andExpect(jsonPath("$[0].customized").value(false));
    }

    @Test
    void builtInAgentHasRoleToolsPromptAndOutputSchema() throws Exception {
        mockMvc.perform(get("/api/ai/agents/{agentId}", "quality-review"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agentId").value("quality-review"))
            .andExpect(jsonPath("$.agentRole").value("quality_review"))
            .andExpect(jsonPath("$.promptTemplate").value(org.hamcrest.Matchers.containsString("质量评审 Agent")))
            .andExpect(jsonPath("$.allowedTools[0]").value("extract-text-structure"))
            .andExpect(jsonPath("$.outputJsonSchema.type").value("object"))
            .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void overridingBuiltInAgentChangesEffectivePrompt() throws Exception {
        mockMvc.perform(put("/api/ai/agents/{agentId}/override", "test-design")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "测试设计 Agent（项目自定义）",
                      "promptTemplate": "custom test design prompt",
                      "allowedTools": ["extract-requirement-keywords"],
                      "enabled": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("测试设计 Agent（项目自定义）"))
            .andExpect(jsonPath("$.promptTemplate").value("custom test design prompt"))
            .andExpect(jsonPath("$.allowedTools[0]").value("extract-requirement-keywords"))
            .andExpect(jsonPath("$.builtIn").value(true))
            .andExpect(jsonPath("$.customized").value(true))
            .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        mockMvc.perform(get("/api/ai/agents/{agentId}", "test-design"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.promptTemplate").value("custom test design prompt"))
            .andExpect(jsonPath("$.customized").value(true));
    }

    @Test
    void deletingOverrideRestoresBuiltInPrompt() throws Exception {
        mockMvc.perform(put("/api/ai/agents/{agentId}/override", "test-design")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "promptTemplate": "custom test design prompt"
                    }
                    """))
            .andExpect(status().isOk());

        mockMvc.perform(delete("/api/ai/agents/{agentId}/override", "test-design"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.promptTemplate").value(org.hamcrest.Matchers.containsString("测试设计 Agent")))
            .andExpect(jsonPath("$.customized").value(false));
    }

    @Test
    void createsCustomAgentWithSkillPathLookup() throws Exception {
        mockMvc.perform(post("/api/ai/agents")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "agentId": "custom-web-review",
                      "name": "自定义Web评审Agent",
                      "description": "按指定路径寻找Skill并评审Web产物",
                      "agentRole": "web_review",
                      "promptTemplate": "根据指定Skill评审Web用例",
                      "allowedTools": {
                        "skillPath": "/opt/haitlify/skills/web/review.md",
                        "skillIds": ["custom-web-login-skill"]
                      },
                      "enabled": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.agentId").value("custom-web-review"))
            .andExpect(jsonPath("$.allowedTools.skillPath").value("/opt/haitlify/skills/web/review.md"))
            .andExpect(jsonPath("$.allowedTools.skillIds[0]").value("custom-web-login-skill"))
            .andExpect(jsonPath("$.builtIn").value(false));

        mockMvc.perform(get("/api/ai/agents"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].agentId").value(hasItem("custom-web-review")));
    }
}
