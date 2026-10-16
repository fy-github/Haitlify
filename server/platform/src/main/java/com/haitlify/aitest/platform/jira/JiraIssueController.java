package com.haitlify.aitest.platform.jira;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jira/issues")
class JiraIssueController {

    private final JiraIssueService jiraIssueService;

    JiraIssueController(JiraIssueService jiraIssueService) {
        this.jiraIssueService = jiraIssueService;
    }

    @GetMapping
    JiraIssueListResponse list(
        @RequestParam String projectName,
        @RequestParam(defaultValue = "service-account") String authMode,
        @RequestParam(required = false) String keyword,
        @RequestParam(defaultValue = "0") int startAt,
        @RequestParam(defaultValue = "50") int maxResults
    ) {
        return jiraIssueService.list(new JiraIssueQuery(projectName, authMode, keyword, startAt, maxResults));
    }
}

@ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
class JiraUnsupportedAuthModeException extends RuntimeException {

    JiraUnsupportedAuthModeException(String message) {
        super(message);
    }
}

record JiraIssueQuery(
    String projectName,
    String authMode,
    String keyword,
    int startAt,
    int maxResults
) {
}

record JiraIssueListResponse(
    String authMode,
    List<JiraIssueItemResponse> items,
    int total
) {
}

record JiraIssueItemResponse(
    String id,
    String projectName,
    String issueKey,
    String summary,
    String status,
    String priority,
    String assignee,
    String issueType,
    String updatedAt
) {
}

record JiraErrorResponse(
    String authMode,
    String message
) {
}
