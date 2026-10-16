package com.haitlify.aitest.platform.jira;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.jira")
public record JiraProperties(
    boolean enabled,
    String baseUrl,
    String defaultAuthMode,
    ServiceAccount serviceAccount,
    Map<String, String> projectJql
) {

    public JiraProperties {
        projectJql = projectJql == null ? new LinkedHashMap<>() : projectJql;
        serviceAccount = serviceAccount == null ? new ServiceAccount("", "") : serviceAccount;
    }

    record ServiceAccount(
        String username,
        String token
    ) {
    }
}
