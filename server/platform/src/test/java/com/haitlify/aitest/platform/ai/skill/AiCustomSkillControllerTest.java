package com.haitlify.aitest.platform.ai.skill;

import static org.hamcrest.Matchers.hasItem;
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
class AiCustomSkillControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearSkills() {
        jdbcTemplate.update("delete from ai_skill_versions");
        jdbcTemplate.update("delete from ai_skills");
        jdbcTemplate.update("delete from ai_skill_overrides");
    }

    @Test
    void createsUpdatesVersionsAndDiffsCustomSkill() throws Exception {
        mockMvc.perform(post("/api/ai/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "skillId": "custom-web-form-analysis",
                      "name": "Web 表单测试点分析",
                      "description": "面向 Web 表单需求的自定义 Skill",
                      "applicableCaseKinds": ["functional"],
                      "applicableFileTypes": ["text", "markdown"],
                      "promptTemplate": "prompt v1",
                      "outputJsonSchema": {
                        "type": "object",
                        "properties": {
                          "draftTestPoints": { "type": "array" }
                        }
                      },
                      "availableTools": ["extract-text-structure"],
                      "examples": [],
                      "enabled": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.skillId").value("custom-web-form-analysis"))
            .andExpect(jsonPath("$.builtIn").value(false))
            .andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.promptTemplate").value("prompt v1"));

        mockMvc.perform(get("/api/ai/skills"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].skillId").value(hasItem("custom-web-form-analysis")));

        mockMvc.perform(put("/api/ai/skills/{skillId}", "custom-web-form-analysis")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "promptTemplate": "prompt v2",
                      "availableTools": ["extract-text-structure", "extract-field-rules"],
                      "applicableCaseKinds": ["functional", "interface"]
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.skillId").value("custom-web-form-analysis"))
            .andExpect(jsonPath("$.version").value(2))
            .andExpect(jsonPath("$.promptTemplate").value("prompt v2"))
            .andExpect(jsonPath("$.availableTools[1]").value("extract-field-rules"));

        mockMvc.perform(get("/api/ai/skills/{skillId}/versions", "custom-web-form-analysis"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].version").value(1))
            .andExpect(jsonPath("$.items[1].version").value(2))
            .andExpect(jsonPath("$.items[1].promptTemplate").value("prompt v2"));

        mockMvc.perform(get("/api/ai/skills/{skillId}/diff", "custom-web-form-analysis")
                .param("fromVersion", "1")
                .param("toVersion", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.skillId").value("custom-web-form-analysis"))
            .andExpect(jsonPath("$.changedFields").value(hasItem("promptTemplate")))
            .andExpect(jsonPath("$.changedFields").value(hasItem("availableTools")))
            .andExpect(jsonPath("$.changes.promptTemplate.before").value("prompt v1"))
            .andExpect(jsonPath("$.changes.promptTemplate.after").value("prompt v2"))
            .andExpect(jsonPath("$.changes.availableTools.after[1]").value("extract-field-rules"));
    }

    @Test
    void publishValidationRejectsUnknownToolsAndBadSchemas() throws Exception {
        // 未知内部工具 id ⇒ 4xx。
        mockMvc.perform(post("/api/ai/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"skillId": "custom-bad-tool", "name": "Bad", "availableTools": ["not-a-real-tool"]}
                    """))
            .andExpect(status().is4xxClientError());

        // 未知 MCP 服务引用 ⇒ 4xx。
        mockMvc.perform(post("/api/ai/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"skillId": "custom-bad-mcp", "name": "Bad", "availableTools": ["mcp:ghost-server:some_tool"]}
                    """))
            .andExpect(status().is4xxClientError());

        // outputJsonSchema 形状非法（type=array）⇒ 4xx。
        mockMvc.perform(post("/api/ai/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"skillId": "custom-bad-schema", "name": "Bad", "outputJsonSchema": {"type": "array"}}
                    """))
            .andExpect(status().is4xxClientError());

        // 全部被拒 ⇒ 不落库。
        Integer count = jdbcTemplate.queryForObject(
            "select count(*) from ai_skills where skill_id like 'custom-bad-%'", Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(0, count);
    }

    @Test
    void disabledMcpServerCannotBackANewSkillCapability() throws Exception {
        jdbcTemplate.update("delete from ai_mcp_servers where server_id = 'skill-mcp-disabled'");
        jdbcTemplate.update("""
            insert into ai_mcp_servers (server_id, project_id, name, server_type, endpoint, auth_mode,
                available_tools_json, tool_allowlist_json, project_scope_json, write_operations_allowed,
                timeout_ms, enabled, audit_enabled, sandbox_config_json, actor_id, actor_name, created_at, updated_at)
            values ('skill-mcp-disabled', null, 'disabled', 'http', 'http://127.0.0.1:1/mcp', 'none',
                '[]', '[{"name":"fixture_tool","readOnly":true}]', '{"mode":"all"}', false,
                30000, false, true, '{}', 'system-dev', 'Development User', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """);

        mockMvc.perform(post("/api/ai/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"skillId": "custom-disabled-mcp", "name": "X", "availableTools": ["mcp:skill-mcp-disabled:fixture_tool"]}
                    """))
            .andExpect(status().is4xxClientError());

        jdbcTemplate.update("update ai_mcp_servers set enabled = true where server_id = 'skill-mcp-disabled'");
        mockMvc.perform(post("/api/ai/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"skillId": "custom-enabled-mcp", "name": "X", "availableTools": ["mcp:skill-mcp-disabled:fixture_tool"]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableTools[0]").value("mcp:skill-mcp-disabled:fixture_tool"));
        jdbcTemplate.update("delete from ai_mcp_servers where server_id = 'skill-mcp-disabled'");
    }

    @Test
    void disabledSkillCannotResolvePromptContextForNewTasks() throws Exception {
        mockMvc.perform(post("/api/ai/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"skillId": "custom-disabled-skill", "name": "Disabled", "promptTemplate": "x",
                     "enabled": false}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.enabled").value(false));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> promptContextService.resolve("custom-disabled-skill"));
    }

    @Autowired
    private AiSkillPromptContextService promptContextService;

    @Test
    void versionHistoryIsImmutableAcrossUpdates() throws Exception {
        mockMvc.perform(post("/api/ai/skills")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"skillId": "custom-immutable", "name": "Immutable", "promptTemplate": "v1 prompt",
                     "availableTools": ["extract-text-structure"]}
                    """))
            .andExpect(status().isOk());

        mockMvc.perform(put("/api/ai/skills/{skillId}", "custom-immutable")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"promptTemplate": "v2 prompt", "availableTools": ["extract-field-rules"]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(2));

        // 版本 1 的存量行不得被改写（不可变历史 + 运行快照的依据）。
        String v1Prompt = jdbcTemplate.queryForObject(
            "select prompt_template from ai_skill_versions where skill_id = 'custom-immutable' and version = 1",
            String.class);
        org.junit.jupiter.api.Assertions.assertEquals("v1 prompt", v1Prompt);
        String v2Prompt = jdbcTemplate.queryForObject(
            "select prompt_template from ai_skill_versions where skill_id = 'custom-immutable' and version = 2",
            String.class);
        org.junit.jupiter.api.Assertions.assertEquals("v2 prompt", v2Prompt);
    }
}
