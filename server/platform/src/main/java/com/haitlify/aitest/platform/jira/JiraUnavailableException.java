package com.haitlify.aitest.platform.jira;

class JiraUnavailableException extends RuntimeException {

    JiraUnavailableException(String message) {
        super(message);
    }

    JiraUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
