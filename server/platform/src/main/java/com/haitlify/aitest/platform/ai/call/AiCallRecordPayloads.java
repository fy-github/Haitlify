package com.haitlify.aitest.platform.ai.call;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** Keeps observability payloads within MySQL TEXT limits without losing traceability. */
public final class AiCallRecordPayloads {
    private static final int MAX_TEXT_BYTES = 60_000;

    private AiCallRecordPayloads() {
    }

    public static String compactJson(ObjectMapper mapper, JsonNode payload) {
        return compactText(mapper, payload == null ? "null" : write(mapper, payload));
    }

    public static String compactText(ObjectMapper mapper, String payload) {
        String value = payload == null ? "null" : payload;
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes <= MAX_TEXT_BYTES) return value;

        ObjectNode summary = mapper.createObjectNode()
            .put("_truncated", true)
            .put("originalBytes", bytes)
            .put("sha256", sha256(value));
        return write(mapper, summary);
    }

    private static String write(ObjectMapper mapper, JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{}";
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) hex.append(String.format("%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            return "unavailable";
        }
    }
}
