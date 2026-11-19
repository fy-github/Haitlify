package com.haitlify.aitest.platform.assistant.security.capability;

import org.junit.jupiter.api.Test;
import java.util.Base64;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class CapabilityTokenCodecTest {
    @Test
    void hashesPresentedTokensInFixedDomain() throws Exception {
        var codec = new CapabilityTokenCodec();
        var issued = codec.issue();
        String raw = issued.rawToken().reveal();
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        String expected = java.util.HexFormat.of().formatHex(digest.digest(
                ("haitlify:assistant:capability:v1\0" + raw).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        assertEquals(expected, codec.hashPresented(raw));
        assertEquals(issued.hash(), codec.hashPresented(raw));
        assertNotEquals(codec.hashPresented("A".repeat(43)), codec.hashPresented("B".repeat(42) + "A"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", " ", "short", "Bearer abc"})
    void rejectsMalformedPresentation(String raw) {
        assertEquals("Invalid capability token", assertThrows(IllegalArgumentException.class,
                () -> new CapabilityTokenCodec().hashPresented(raw)).getMessage());
    }

    @Test
    void rejectsNonCanonicalOrOversizedTokensWithoutEchoingInput() {
        var codec = new CapabilityTokenCodec();
        String canonical = "A".repeat(43);
        for (String raw : new String[] {" " + canonical, canonical + " ", canonical + "=",
                canonical + "\n", "A".repeat(42), "A".repeat(44), "A".repeat(100000),
                "A".repeat(42) + "B", "+" + "A".repeat(42), "/" + "A".repeat(42),
                "é" + "A".repeat(42), "\0" + "A".repeat(42)}) {
            assertEquals("Invalid capability token", assertThrows(IllegalArgumentException.class,
                    () -> codec.hashPresented(raw)).getMessage());
        }
    }

    @Test
    void issuesOpaqueRandomTokensWithoutLoggingSecrets() {
        var codec = new CapabilityTokenCodec();
        var seen = new HashSet<String>();
        for (int i = 0; i < 128; i++) {
            var issued = codec.issue();
            assertNotNull(issued);
            String raw = issued.rawToken().reveal();
            assertTrue(raw.matches("[A-Za-z0-9_-]{43}"));
            assertEquals(32, Base64.getUrlDecoder().decode(raw).length);
            assertTrue(seen.add(raw));
            assertEquals("CapabilityToken[REDACTED]", issued.rawToken().toString());
            assertFalse(issued.toString().contains(raw));
            assertTrue(issued.hash().matches("[0-9a-f]{64}"));
        }
    }
}
