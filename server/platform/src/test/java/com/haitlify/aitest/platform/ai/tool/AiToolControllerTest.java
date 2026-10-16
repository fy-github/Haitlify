package com.haitlify.aitest.platform.ai.tool;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiToolControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listsInternalAgentToolsWithSchemas() throws Exception {
        mockMvc.perform(get("/api/ai/tools"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].toolId").value("extract-text-structure"))
            .andExpect(jsonPath("$.items[0].readOnly").value(true))
            .andExpect(jsonPath("$.items[0].inputSchema.type").value("object"))
            .andExpect(jsonPath("$.items[?(@.toolId == 'extract-requirement-keywords')]").isNotEmpty())
            .andExpect(jsonPath("$.items[?(@.toolId == 'extract-field-rules')]").isNotEmpty())
            .andExpect(jsonPath("$.items[?(@.toolId == 'extract-state-rules')]").isNotEmpty())
            .andExpect(jsonPath("$.items[?(@.toolId == 'match-case-template')]").isNotEmpty())
            .andExpect(jsonPath("$.items[?(@.toolId == 'check-coverage-gaps')]").isNotEmpty());
    }

    @Test
    void dryRunsRequirementKeywordExtractor() throws Exception {
        mockMvc.perform(post("/api/ai/tools/extract-requirement-keywords/dry-run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "text": "用户点击保存项目后，系统必须校验项目名称。管理员可以删除草稿项目，删除后状态变为已删除。"
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.toolId").value("extract-requirement-keywords"))
            .andExpect(jsonPath("$.readOnly").value(true))
            .andExpect(jsonPath("$.result.operations[0]").value("保存项目"))
            .andExpect(jsonPath("$.result.operations[1]").value("删除草稿项目"))
            .andExpect(jsonPath("$.result.roles[0]").value("管理员"))
            .andExpect(jsonPath("$.result.keywords[?(@ == '项目名称')]").isNotEmpty());
    }

    @Test
    void unknownToolReturnsValidationError() throws Exception {
        mockMvc.perform(post("/api/ai/tools/unknown-tool/dry-run")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"hello\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Unknown AI tool: unknown-tool"));
    }
}
