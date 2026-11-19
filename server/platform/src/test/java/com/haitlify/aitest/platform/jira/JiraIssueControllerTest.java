package com.haitlify.aitest.platform.jira;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.sun.net.httpserver.HttpServer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JiraIssueControllerTest {

    /** 桩服务用临时端口：固定端口会和本机其它进程（如隔离栈的 19324）抢绑定，导致随机红灯。 */
    private static final HttpServer STUB = startStub();

    @DynamicPropertySource
    static void jiraBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("app.jira.base-url", () -> "http://127.0.0.1:" + STUB.getAddress().getPort());
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/rest/api/2/search",
                exchange -> {
                    byte[] payload = searchBody().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, payload.length);
                    exchange.getResponseBody().write(payload);
                    exchange.close();
                });
            server.setExecutor(Executors.newSingleThreadExecutor());
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("Jira 桩服务启动失败", ex);
        }
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

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

    private static String searchBody() {
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
}
