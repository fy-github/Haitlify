package com.haitlify.aitest.platform.assistant.security.capability;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Opaque bearer secrets. Persist only the hash; never log or serialize the revealed token. */
public final class CapabilityTokenCodec {
    private static final byte[] DOMAIN = "haitlify:assistant:capability:v1\0".getBytes(StandardCharsets.US_ASCII);
    private final SecureRandom random = new SecureRandom();

    public IssuedToken issue() {
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        return new IssuedToken(new CapabilityToken(raw), digest(raw));
    }

    /** Reject, never trim or normalize: one canonical textual representation per secret. */
    public String hashPresented(String raw) {
        if (raw == null || raw.length() != 43 || !raw.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid capability token");
        }
        byte[] decoded = Base64.getUrlDecoder().decode(raw);
        if (!Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(raw)) {
            throw new IllegalArgumentException("Invalid capability token");
        }
        return digest(raw);
    }

    private static String digest(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN);
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public record IssuedToken(CapabilityToken rawToken, String hash) {}

    public static final class CapabilityToken {
        private final String value;
        private CapabilityToken(String value) { this.value = value; }
        /** Explicit handoff to the authorized caller only. */
        public String reveal() { return value; }
        @Override public String toString() { return "CapabilityToken[REDACTED]"; }
    }
}
