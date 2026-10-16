package com.haitlify.aitest.platform.ai.skill;

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
class AiSkillControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearOverrides() {
        jdbcTemplate.update("delete from ai_skill_overrides");
        jdbcTemplate.update("delete from ai_skill_versions");
        jdbcTemplate.update("delete from ai_skills where is_custom = true");
    }

    @Test
    void listsBuiltInRequirementTestPointAnalysisSkill() throws Exception {
        mockMvc.perform(get("/api/ai/skills"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].skillId").value(hasItem("requirement-test-point-analysis")))
            .andExpect(jsonPath("$[*].skillId").value(hasItem("formal-web-case-generation")))
            .andExpect(jsonPath("$[0].builtIn").value(true))
            .andExpect(jsonPath("$[0].customized").value(false));

        mockMvc.perform(get("/api/ai/skills/{skillId}", "formal-web-case-generation"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.applicableCaseKinds[0]").value("web_automation"))
            .andExpect(jsonPath("$.outputJsonSchema.required[0]").value("cases"))
            .andExpect(jsonPath("$.promptTemplate").value(org.hamcrest.Matchers.containsString("manual")))
            .andExpect(jsonPath("$.promptTemplate").value(org.hamcrest.Matchers.containsString("禁止")));
    }

    @Test
    void savesOverrideAndReturnsEffectiveSkill() throws Exception {
        mockMvc.perform(put("/api/ai/skills/{skillId}/override", "requirement-test-point-analysis")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "需求测试点分析（项目自定义）",
                      "promptTemplate": "custom prompt",
                      "availableTools": ["extract-text-structure"],
                      "enabled": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("需求测试点分析（项目自定义）"))
            .andExpect(jsonPath("$.promptTemplate").value("custom prompt"))
            .andExpect(jsonPath("$.availableTools[0]").value("extract-text-structure"))
            .andExpect(jsonPath("$.builtIn").value(true))
            .andExpect(jsonPath("$.customized").value(true))
            .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        mockMvc.perform(get("/api/ai/skills/{skillId}", "requirement-test-point-analysis"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("需求测试点分析（项目自定义）"))
            .andExpect(jsonPath("$.customized").value(true));
    }

    @Test
    void deletingOverrideRestoresBuiltInDefault() throws Exception {
        mockMvc.perform(put("/api/ai/skills/{skillId}/override", "requirement-test-point-analysis")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "需求测试点分析（项目自定义）",
                      "promptTemplate": "custom prompt"
                    }
                    """))
            .andExpect(status().isOk());

        mockMvc.perform(delete("/api/ai/skills/{skillId}/override", "requirement-test-point-analysis"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("需求测试点分析"))
            .andExpect(jsonPath("$.promptTemplate").value(org.hamcrest.Matchers.containsString("你是需求测试点分析 Agent")))
            .andExpect(jsonPath("$.customized").value(false));
    }

    @Test
    void createsCustomSkillWithPathForAgentLookup() throws Exception {
        mockMvc.perform(post("/api/ai/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "skillId": "custom-web-login-skill",
                      "name": "Web登录补充Skill",
                      "description": "补充Web登录场景",
                      "skillPath": "/opt/haitlify/skills/web/login.md",
                      "promptTemplate": "请补充登录Web用例",
                      "enabled": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.skillId").value("custom-web-login-skill"))
            .andExpect(jsonPath("$.skillPath").value("/opt/haitlify/skills/web/login.md"))
            .andExpect(jsonPath("$.examples.skillPath").value("/opt/haitlify/skills/web/login.md"))
            .andExpect(jsonPath("$.builtIn").value(false));
    }
}
