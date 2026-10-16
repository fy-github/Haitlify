package com.haitlify.aitest.platform.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * L5①：override 写入口的 HTTP 边界——白名单拒绝必须是 400 invalid_request（而非框架默认 500），
 * 且未知类型零落库；并集白名单（策略默认集 ∪ 助手目录）保持两类目标可覆盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiRunPolicyControllerHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetTables() {
        jdbcTemplate.update("delete from ai_run_policies");
    }

    @Test
    void unknownOverrideTargetsAnswerFourHundredAndWriteNoRows() throws Exception {
        mockMvc.perform(put("/api/ai/run-policies/override/mystery_task")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"未知\",\"enabled\":true}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("invalid_request"))
            .andExpect(jsonPath("$.message").value(containsString("unknown_task_type")));

        assertThat(jdbcTemplate.queryForObject(
            "select count(*) from ai_run_policies where task_type = 'mystery_task'", Integer.class)).isZero();
    }

    @Test
    void knownOverrideTargetsAcrossBothSetsAreAccepted() throws Exception {
        // 策略默认集（不在助手目录）仍可覆盖。
        mockMvc.perform(put("/api/ai/run-policies/override/web_case_generation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Web 覆盖\",\"enabled\":true,\"retryBudget\":3}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taskType").value("web_case_generation"));

        // 助手目录命中：变体拼写归一化后取 canonical 拼写落库。
        mockMvc.perform(put("/api/ai/run-policies/override/{taskType}", "Bug_Analysis\u200B")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"缺陷覆盖\",\"enabled\":true,\"retryBudget\":4}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.taskType").value("bug_analysis"));

        assertThat(jdbcTemplate.queryForList(
            "select task_type from ai_run_policies where scope = 'task_override' order by task_type", String.class))
            .containsExactly("bug_analysis", "web_case_generation");
    }
}
