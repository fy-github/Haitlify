package com.haitlify.aitest.platform.jira;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class JiraIssueClientTest {

    @Test
    void fetchesIssuesViaSearchApiAndMapsFields() throws Exception {
        AtomicReference<String> authHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/api/2/search", exchange -> {
            authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, searchResponse());
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            JiraIssueClient client = client(server.getAddress().getPort());
            JiraIssueListResponse result = client.listByServiceAccount(
                new JiraIssueQuery("Haitlify API", "service-account", "超时", 0, 50)
            );

            assertEquals("service-account", result.authMode());
            assertEquals(2, result.total());
            assertEquals(2, result.items().size());

            JiraIssueItemResponse first = result.items().get(0);
            assertEquals("10001", first.id());
            assertEquals("Haitlify API", first.projectName());
            assertEquals("HAITLIFY-12", first.issueKey());
            assertEquals("项目树接口查询超时", first.summary());
            assertEquals("处理中", first.status());
            assertEquals("High", first.priority());
            assertEquals("张三", first.assignee());
            assertEquals("故障", first.issueType());
            assertEquals("2026-06-16T15:00:00.000+0800", first.updatedAt());

            assertEquals("未分配", result.items().get(1).assignee());

            assertTrue(authHeader.get().startsWith("Basic "));
            assertTrue(requestBody.get().contains("project = HAITLIFY order by created DESC"));
            assertTrue(requestBody.get().contains("超时"));
            assertTrue(requestBody.get().contains("issuekey"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void returnsEmptyWhenJiraIsDisabled() {
        JiraIssueClient client = new JiraIssueClient(
            () -> new JiraRuntimeConfig(false, "https://pm.example.test", "", "", Map.of()),
            new ObjectMapper()
        );
        JiraIssueListResponse result = client.listByServiceAccount(
            new JiraIssueQuery("Haitlify API", "service-account", null, 0, 50)
        );
        assertEquals(0, result.total());
        assertTrue(result.items().isEmpty());
    }

    @Test
    void returnsEmptyWhenProjectHasNoJqlMapping() {
        JiraIssueClient client = new JiraIssueClient(
            () -> new JiraRuntimeConfig(true, "https://pm.example.test", "u", "t", Map.of()),
            new ObjectMapper()
        );
        JiraIssueListResponse result = client.listByServiceAccount(
            new JiraIssueQuery("Unknown Project", "service-account", null, 0, 50)
        );
        assertEquals(0, result.total());
        assertTrue(result.items().isEmpty());
    }

    @Test
    void throwsUnavailableWhenJiraReturnsError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rest/api/2/search",
            exchange -> respond(exchange, 401, "{\"errorMessages\":[\"Invalid credentials\"]}"));
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            JiraIssueClient client = client(server.getAddress().getPort());
            assertThrows(JiraUnavailableException.class, () -> client.listByServiceAccount(
                new JiraIssueQuery("Haitlify API", "service-account", null, 0, 50)
            ));
        } finally {
            server.stop(0);
        }
    }

    private JiraIssueClient client(int port) {
        LinkedHashMap<String, String> jql = new LinkedHashMap<>();
        jql.put("HAITLIFY_API", "project = HAITLIFY order by created DESC");
        return new JiraIssueClient(
            () -> new JiraRuntimeConfig(true, "http://127.0.0.1:" + port, "jira-reader", "test-token", jql),
            new ObjectMapper()
        );
    }

    private String searchResponse() {
        return """
            {
              "total": 2,
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
                },
                {
                  "id": "10002",
                  "key": "HAITLIFY-13",
                  "fields": {
                    "summary": "列表分页异常",
                    "status": { "name": "待处理" },
                    "priority": { "name": "Medium" },
                    "assignee": null,
                    "issuetype": { "name": "缺陷" },
                    "updated": "2026-06-16T16:00:00.000+0800"
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
