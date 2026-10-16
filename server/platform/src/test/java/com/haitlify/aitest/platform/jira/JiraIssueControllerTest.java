package com.haitlify.aitest.platform.jira;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.jira.base-url=http://127.0.0.1:19324")
class JiraIssueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JiraConfigRepository jiraConfigRepository;

    @BeforeEach
    void cleanJiraConfig() {
        jiraConfigRepository.deleteAll();
    }

    @Test
    void returnsNormalizedIssuesForServiceAccountMode() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 19324), 0);
        server.createContext("/rest/api/2/search", exchange -> respond(exchange, 200, searchResponse()));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            mockMvc.perform(get("/api/jira/issues")
                    .param("projectName", "Haitlify API")
                    .param("authMode", "service-account"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authMode").value("service-account"))
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].projectName").value("Haitlify API"))
                .andExpect(jsonPath("$.items[0].issueKey").value("HAITLIFY-12"))
                .andExpect(jsonPath("$.items[0].summary").value("项目树接口查询超时"))
                .andExpect(jsonPath("$.total").value(1));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsUserSessionModeUntilItIsImplemented() throws Exception {
        mockMvc.perform(get("/api/jira/issues")
                .param("projectName", "Haitlify API")
                .param("authMode", "user-session"))
            .andExpect(status().isNotImplemented())
            .andExpect(jsonPath("$.authMode").value("user-session"))
            .andExpect(jsonPath("$.message").value("Jira user-session mode is not implemented yet"));
    }

    private String searchResponse() {
        return """
            {
              "total": 1,
              "issues": [
                {
                  "id": "10001",
                  "key": "HAITLIFY-12",
                  "fields": {
                    "summary": "项目树接口查询超时",
                    "status": { "name": "处理中" },
                    "priority": { "name": "High" },
                    "assignee": { "name": "zhangsan", "displayName": "张三" },
                    "issuetype": { "name": "故障" },
                    "updated": "2026-06-16T15:00:00.000+0800"
                  }
                }
              ]
            }
            """;
    }

    private void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }
}
