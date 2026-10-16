package com.haitlify.aitest.platform.ai.agentic.locator;

import org.springframework.dao.DataAccessException;

/** Signals that browser locator evidence could not be persisted. */
final class WebLocatorKnowledgePersistenceException extends DataAccessException implements LocatorKnowledgePersistenceFailure {
    private final String pageUrl;

    WebLocatorKnowledgePersistenceException(String pageUrl, Throwable cause) {
        super("素材库持久化失败", cause);
        this.pageUrl = sanitizePageUrl(pageUrl);
    }

    String pageUrl() {
        return pageUrl;
    }

    private static String sanitizePageUrl(String value) {
        if (value == null || value.isBlank()) return "";
        try {
            java.net.URI uri = java.net.URI.create(value);
            StringBuilder safe = new StringBuilder();
            if (uri.getScheme() != null) safe.append(uri.getScheme()).append("://");
            if (uri.getRawAuthority() != null) safe.append(uri.getHost() == null ? "" : uri.getHost());
            if (uri.getPort() > 0) safe.append(':').append(uri.getPort());
            String path = uri.getRawPath();
            safe.append(path == null || path.isBlank() ? "/" : path);
            return safe.toString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}
