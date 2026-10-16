package com.haitlify.aitest.platform.jira;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class JiraIssueClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final List<String> SEARCH_FIELDS = List.of(
        "summary", "status", "priority", "assignee", "issuetype", "updated"
    );

    private final JiraConfigSource configSource;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    JiraIssueClient(JiraConfigSource configSource, ObjectMapper objectMapper) {
        this.configSource = configSource;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    }

    JiraIssueListResponse listByServiceAccount(JiraIssueQuery query) {
        JiraRuntimeConfig config = configSource.effectiveConfig();
        if (!config.enabled()) {
            return empty();
        }

        String jql = resolveProjectJql(query.projectName(), config.projectJql());
        if (jql.isBlank()) {
            return empty();
        }

        String finalJql = appendKeyword(jql, query.keyword());

        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl().replaceAll("/$", "") + "/rest/api/2/search"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", basicAuth(config.username(), config.token()))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(searchBody(finalJql, query)))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new JiraUnavailableException("Jira API returned HTTP " + response.statusCode());
            }
            return parse(response.body(), query.projectName());
        } catch (JiraUnavailableException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new JiraUnavailableException("Jira API is unavailable", exception);
        }
    }

    private JiraIssueListResponse parse(String payload, String projectName) throws Exception {
        JsonNode root = objectMapper.readTree(payload);
        int total = root.path("total").asInt(0);
        List<JiraIssueItemResponse> items = new ArrayList<>();
        for (JsonNode issue : root.path("issues")) {
            JsonNode fields = issue.path("fields");
            items.add(new JiraIssueItemResponse(
                issue.path("id").asText(""),
                projectName,
                issue.path("key").asText(""),
                fields.path("summary").asText(""),
                fields.path("status").path("name").asText(""),
                fields.path("priority").path("name").asText(""),
                assignee(fields.path("assignee")),
                fields.path("issuetype").path("name").asText(""),
                fields.path("updated").asText("")
            ));
        }
        return new JiraIssueListResponse("service-account", items, total);
    }

    private String assignee(JsonNode assignee) {
        if (assignee == null || assignee.isMissingNode() || assignee.isNull()) {
            return "未分配";
        }
        String displayName = assignee.path("displayName").asText("");
        return displayName.isBlank() ? "未分配" : displayName;
    }

    private String basicAuth(String username, String token) {
        String credentials = username + ":" + token;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private String searchBody(String jql, JiraIssueQuery query) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jql", jql);
        body.put("startAt", Math.max(query.startAt(), 0));
        body.put("maxResults", clampMaxResults(query.maxResults()));
        body.put("fields", SEARCH_FIELDS);
        return objectMapper.writeValueAsString(body);
    }

    private int clampMaxResults(int maxResults) {
        return Math.max(1, Math.min(maxResults, 1000));
    }

    private String appendKeyword(String jql, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return jql;
        }
        String escaped = keyword.replace("\\", "\\\\").replace("\"", "\\\"");
        return jql + " AND (summary ~ \"" + escaped + "\" OR issuekey = \"" + escaped + "\")";
    }

    private JiraIssueListResponse empty() {
        return new JiraIssueListResponse("service-account", List.of(), 0);
    }

    private String resolveProjectJql(String projectName, Map<String, String> projectJql) {
        String direct = projectJql.getOrDefault(projectName, "");
        if (!direct.isBlank()) return direct;

        String normalized = projectName
            .replaceAll("[^A-Za-z0-9]+", "_")
            .replaceAll("^_+|_+$", "")
            .toUpperCase();
        return projectJql.getOrDefault(normalized, "");
    }
}
