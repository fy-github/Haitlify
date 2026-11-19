package com.haitlify.aitest.platform.jira;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.haitlify.aitest.platform.security.SecretCipher;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional
class JiraConfigService implements JiraConfigSource {

    private static final String DEFAULT_CONFIG_KEY = "default";
    private static final String DEFAULT_AUTH_MODE = "service-account";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final JiraConfigRepository repository;
    private final JiraProperties jiraProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final SecretCipher secretCipher;

    JiraConfigService(JiraConfigRepository repository, JiraProperties jiraProperties, ObjectMapper objectMapper, SecretCipher secretCipher) {
        this.repository = repository;
        this.jiraProperties = jiraProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newHttpClient();
        this.secretCipher = secretCipher;
    }

    JiraConfigResponse getConfig() {
        return repository.findByConfigKey(DEFAULT_CONFIG_KEY)
            .map(this::toResponse)
            .orElseGet(this::defaultResponse);
    }

    JiraConfigResponse saveConfig(SaveJiraConfigRequest request) {
        JiraConfig current = repository.findByConfigKey(DEFAULT_CONFIG_KEY).orElse(null);
        Instant now = Instant.now();

        String baseUrl = request.baseUrl() == null || request.baseUrl().isBlank()
            ? current == null ? jiraProperties.baseUrl() : current.getBaseUrl()
            : request.baseUrl().trim();
        String authMode = request.authMode() == null || request.authMode().isBlank()
            ? current == null ? blankToDefault(jiraProperties.defaultAuthMode(), DEFAULT_AUTH_MODE) : current.getAuthMode()
            : request.authMode().trim();
        String username = request.username() == null || request.username().isBlank()
            ? current == null ? jiraProperties.serviceAccount().username() : current.getUsername()
            : request.username().trim();
        String token = request.token() == null || request.token().isBlank()
            ? current == null ? jiraProperties.serviceAccount().token() : secretCipher.decrypt(current.getToken())
            : request.token().trim();
        Map<String, String> projectJql = request.projectJql() != null
            ? request.projectJql()
            : current == null ? jiraProperties.projectJql() : parseProjectJql(current.getProjectJqlJson());

        JiraConfig next = new JiraConfig(
            current == null ? null : current.getId(),
            DEFAULT_CONFIG_KEY,
            request.enabled(),
            blankToDefault(baseUrl, ""),
            authMode,
            username,
            secretCipher.encrypt(token),
            !token.isBlank(),
            toJson(projectJql),
            com.haitlify.aitest.platform.security.ActorResolver.currentOrSystem(),
            com.haitlify.aitest.platform.security.ActorResolver.currentOrSystemName(),
            current == null ? now : current.getCreatedAt(),
            now
        );

        return toResponse(repository.save(next));
    }

    JiraConnectionTestResponse testConnection(TestJiraConfigRequest request) {
        String baseUrl = request.baseUrl() == null ? "" : request.baseUrl().replaceAll("/$", "").trim();
        String username = request.username() == null ? "" : request.username().trim();
        String token = request.token() == null ? "" : request.token().trim();

        if (token.isBlank()) {
            JiraRuntimeConfig fallback = effectiveConfig();
            if (username.isBlank()) {
                username = fallback.username();
            }
            token = fallback.token();
        }

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/rest/api/2/myself"))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", basicAuth(username, token))
                .header("Accept", "application/json")
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                return new JiraConnectionTestResponse(false, "Jira 返回 HTTP " + response.statusCode());
            }
            return new JiraConnectionTestResponse(true, "连接成功");
        } catch (Exception exception) {
            return new JiraConnectionTestResponse(false, "无法连接 Jira：" + exception.getMessage());
        }
    }

    @Override
    public JiraRuntimeConfig effectiveConfig() {
        JiraConfig config = repository.findByConfigKey(DEFAULT_CONFIG_KEY).orElse(null);
        if (config != null) {
            return new JiraRuntimeConfig(
                config.isEnabled(),
                config.getBaseUrl(),
                config.getUsername(),
                secretCipher.decrypt(config.getToken()),
                parseProjectJql(config.getProjectJqlJson())
            );
        }
        return new JiraRuntimeConfig(
            jiraProperties.enabled(),
            jiraProperties.baseUrl(),
            jiraProperties.serviceAccount().username(),
            jiraProperties.serviceAccount().token(),
            jiraProperties.projectJql()
        );
    }

    private JiraConfigResponse toResponse(JiraConfig config) {
        return new JiraConfigResponse(
            config.isEnabled(),
            config.getBaseUrl(),
            config.getAuthMode(),
            config.getUsername(),
            config.isCredentialConfigured(),
            maskedToken(secretCipher.decrypt(config.getToken())),
            parseProjectJql(config.getProjectJqlJson()),
            config.getUpdatedAt() == null ? null : config.getUpdatedAt().toString()
        );
    }

    private JiraConfigResponse defaultResponse() {
        return new JiraConfigResponse(
            jiraProperties.enabled(),
            jiraProperties.baseUrl(),
            blankToDefault(jiraProperties.defaultAuthMode(), DEFAULT_AUTH_MODE),
            jiraProperties.serviceAccount().username(),
            !jiraProperties.serviceAccount().token().isBlank(),
            maskedToken(jiraProperties.serviceAccount().token()),
            jiraProperties.projectJql(),
            null
        );
    }

    private String maskedToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String trimmed = token.trim();
        if (trimmed.length() <= 4) {
            return "****";
        }
        return "****" + trimmed.substring(trimmed.length() - 4);
    }

    private String toJson(Map<String, String> projectJql) {
        try {
            return objectMapper.writeValueAsString(projectJql == null ? Map.of() : projectJql);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Invalid project JQL mapping", exception);
        }
    }

    private Map<String, String> parseProjectJql(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            Map<String, String> map = new LinkedHashMap<>();
            root.properties().forEach(entry -> map.put(entry.getKey(), entry.getValue().asText("")));
            return map;
        } catch (JacksonException exception) {
            return Map.of();
        }
    }

    private String basicAuth(String username, String token) {
        String credentials = username + ":" + token;
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

record SaveJiraConfigRequest(
    boolean enabled,
    String baseUrl,
    String authMode,
    String username,
    String token,
    Map<String, String> projectJql
) {
}

record TestJiraConfigRequest(
    String baseUrl,
    String username,
    String token
) {
}

record JiraConfigResponse(
    boolean enabled,
    String baseUrl,
    String authMode,
    String username,
    boolean credentialConfigured,
    String maskedToken,
    Map<String, String> projectJql,
    String updatedAt
) {
}

record JiraConnectionTestResponse(
    boolean success,
    String message
) {
}
