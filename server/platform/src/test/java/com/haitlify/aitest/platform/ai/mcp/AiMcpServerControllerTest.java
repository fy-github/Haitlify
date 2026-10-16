package com.haitlify.aitest.platform.ai.mcp;

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
class AiMcpServerControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearMcpServers() {
        jdbcTemplate.update("delete from ai_mcp_servers");
    }

    @Test
    void createsReadsUpdatesAndDeletesMcpServerConfig() throws Exception {
        mockMvc.perform(post("/api/ai/mcp-servers")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "serverId": "playwright-local",
                      "name": "Playwright 本地服务",
                      "serverType": "stdio",
                      "endpoint": "npx @modelcontextprotocol/server-playwright",
                      "availableTools": ["browser_navigate", "browser_snapshot"],
                      "toolAllowlist": ["browser_snapshot"],
                      "sandboxConfig": {
                        "network": "disabled",
                        "filesystem": "read-only"
                      },
                      "enabled": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.serverId").value("playwright-local"))
            .andExpect(jsonPath("$.name").value("Playwright 本地服务"))
            .andExpect(jsonPath("$.writeOperationsAllowed").value(false))
            .andExpect(jsonPath("$.toolAllowlist[0]").value("browser_snapshot"))
            .andExpect(jsonPath("$.sandboxConfig.network").value("disabled"));

        mockMvc.perform(get("/api/ai/mcp-servers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].serverId").value(hasItem("playwright-local")));

        mockMvc.perform(put("/api/ai/mcp-servers/{serverId}", "playwright-local")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                      "name": "Playwright 只读服务",
                      "serverType": "stdio",
                      "endpoint": "npx @modelcontextprotocol/server-playwright",
                      "toolAllowlist": ["browser_snapshot", "browser_console_messages"],
                      "writeOperationsAllowed": true,
                      "enabled": true
                    }
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Playwright 只读服务"))
            .andExpect(jsonPath("$.toolAllowlist[1]").value("browser_console_messages"))
            .andExpect(jsonPath("$.writeOperationsAllowed").value(false));

        mockMvc.perform(delete("/api/ai/mcp-servers/{serverId}", "playwright-local"))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/ai/mcp-servers"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isEmpty());
    }
}
