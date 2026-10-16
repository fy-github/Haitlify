package com.haitlify.aitest.platform.ai.rule;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import java.time.Instant;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiRuleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void clearRules() {
        jdbcTemplate.update("delete from ai_rules");
        jdbcTemplate.update("delete from projects where code = 'AI_RULE_PROJECT'");
        Instant now = Instant.now();
        jdbcTemplate.update(
            "insert into projects(code, name, description, active, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
            "AI_RULE_PROJECT", "AI Rule Project", "", true, now, now
        );
        projectId = jdbcTemplate.queryForObject(
            "select id from projects where code = 'AI_RULE_PROJECT'",
            Long.class
        );
    }

    @Test
    void returnsHardDefaultRules() throws Exception {
        mockMvc.perform(get("/api/ai/rules"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.forcedHumanReview").value(true))
            .andExpect(jsonPath("$.sourceEvidenceRequired").value(true))
            .andExpect(jsonPath("$.formalGenerationStatuses[0]").value("confirmed"))
            .andExpect(jsonPath("$.formalGenerationStatuses[1]").value("modified"));
    }

    @Test
    void savingRulesIncrementsVersionAndKeepsMandatoryReview() throws Exception {
        mockMvc.perform(put("/api/ai/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "forcedHumanReview": false,
                      "sourceEvidenceRequired": true,
                      "inferredRequiresConfirmation": false,
                      "allowedCaseKinds": ["interface", "web_automation"],
                      "formalGenerationStatuses": ["ai_generated"],
                      "forbiddenPatterns": ["无来源依据"],
                      "coverageDimensions": ["功能", "异常"]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.forcedHumanReview").value(true))
            .andExpect(jsonPath("$.sourceEvidenceRequired").value(true))
            .andExpect(jsonPath("$.inferredRequiresConfirmation").value(false))
            .andExpect(jsonPath("$.allowedCaseKinds[0]").value("interface"))
            .andExpect(jsonPath("$.allowedCaseKinds[1]").value("web_automation"))
            .andExpect(jsonPath("$.formalGenerationStatuses[0]").value("confirmed"))
            .andExpect(jsonPath("$.formalGenerationStatuses[1]").value("modified"));
    }

    @Test
    void mergesProjectOverridesAndReturnsEffectiveVersionMetadata() throws Exception {
        mockMvc.perform(put("/api/ai/rules")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "allowedCaseKinds": ["web_automation"],
                      "coverageDimensions": ["功能", "异常"],
                      "rules": {
                        "selectorPolicy": "role",
                        "requireAssertion": true
                      }
                    }
                    """))
            .andExpect(status().isOk());

        mockMvc.perform(put("/api/ai/rules")
                .param("projectId", String.valueOf(projectId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "coverageDimensions": ["功能", "权限"],
                      "rules": {
                        "selectorPolicy": "testId"
                      }
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.projectId").value(projectId))
            .andExpect(jsonPath("$.systemVersion").value(2))
            .andExpect(jsonPath("$.projectVersion").value(1))
            .andExpect(jsonPath("$.effectiveVersion").value("system-v2+project-v1"))
            .andExpect(jsonPath("$.allowedCaseKinds[0]").value("web_automation"))
            .andExpect(jsonPath("$.coverageDimensions[0]").value("功能"))
            .andExpect(jsonPath("$.coverageDimensions[1]").value("权限"))
            .andExpect(jsonPath("$.rules.selectorPolicy").value("testId"))
            .andExpect(jsonPath("$.rules.requireAssertion").value(true));

        mockMvc.perform(get("/api/ai/rules").param("projectId", String.valueOf(projectId)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.effectiveVersion").value("system-v2+project-v1"))
            .andExpect(jsonPath("$.rules.selectorPolicy").value("testId"))
            .andExpect(jsonPath("$.rules.requireAssertion").value(true));
    }
}
