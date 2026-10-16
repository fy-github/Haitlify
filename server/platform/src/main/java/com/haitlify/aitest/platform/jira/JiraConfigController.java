package com.haitlify.aitest.platform.jira;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!jira-readonly")
@RequestMapping("/api/jira/config")
class JiraConfigController {

    private final JiraConfigService jiraConfigService;

    JiraConfigController(JiraConfigService jiraConfigService) {
        this.jiraConfigService = jiraConfigService;
    }

    @GetMapping
    JiraConfigResponse getConfig() {
        return jiraConfigService.getConfig();
    }

    @PutMapping
    JiraConfigResponse saveConfig(@RequestBody SaveJiraConfigRequest request) {
        return jiraConfigService.saveConfig(request);
    }

    @PostMapping("/test-connection")
    @ResponseStatus(HttpStatus.OK)
    JiraConnectionTestResponse testConnection(@RequestBody TestJiraConfigRequest request) {
        return jiraConfigService.testConnection(request);
    }
}
