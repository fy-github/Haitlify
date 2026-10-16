package com.haitlify.aitest.platform.webautomation;

import tools.jackson.databind.JsonNode;

/** A Runner completed the command but could not collect safe browser evidence. */
public class WebAutomationEvidenceException extends RuntimeException {

    private final int statusCode;
    private final String category;
    private final JsonNode details;

    public WebAutomationEvidenceException(int statusCode, String message, String category, JsonNode details) {
        super(message);
        this.statusCode = statusCode;
        this.category = category == null ? "" : category;
        this.details = details;
    }

    public int statusCode() { return statusCode; }
    public String category() { return category; }
    public JsonNode details() { return details; }
    public boolean retryable() { return details != null && details.path("retryable").asBoolean(false); }
}
