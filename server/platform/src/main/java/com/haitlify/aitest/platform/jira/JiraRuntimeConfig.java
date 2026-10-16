package com.haitlify.aitest.platform.jira;

import java.util.LinkedHashMap;
import java.util.Map;

record JiraRuntimeConfig(
    boolean enabled,
    String baseUrl,
    String username,
    String token,
    Map<String, String> projectJql
) {

    JiraRuntimeConfig {
        projectJql = projectJql == null ? new LinkedHashMap<>() : projectJql;
    }
}
