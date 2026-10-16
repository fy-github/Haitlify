package com.haitlify.aitest.platform.jira;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Service
class JiraIssueService {

    private final JiraIssueClient jiraIssueClient;

    JiraIssueService(JiraIssueClient jiraIssueClient) {
        this.jiraIssueClient = jiraIssueClient;
    }

    JiraIssueListResponse list(JiraIssueQuery query) {
        if ("user-session".equals(query.authMode())) {
            throw new JiraUnsupportedAuthModeException("Jira user-session mode is not implemented yet");
        }

        try {
            return jiraIssueClient.listByServiceAccount(query);
        } catch (JiraUnavailableException exception) {
            return new JiraIssueListResponse("service-account", List.of(), 0);
        }
    }
}

@RestControllerAdvice
class JiraIssueExceptionHandler {

    @ExceptionHandler(JiraUnsupportedAuthModeException.class)
    @ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
    JiraErrorResponse handleUnsupportedAuthMode(JiraUnsupportedAuthModeException exception) {
        return new JiraErrorResponse("user-session", exception.getMessage());
    }
}
