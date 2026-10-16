package com.haitlify.aitest.platform.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private static final String KEY_32_BYTES = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTripsWhenKeyConfigured() {
        SecretCipher cipher = new SecretCipher(KEY_32_BYTES);
        String encrypted = cipher.encrypt("sk-abc123");

        assertTrue(encrypted.startsWith("enc:v1:"));
        assertNotEquals("sk-abc123", encrypted);
        assertEquals("sk-abc123", cipher.decrypt(encrypted));
    }

    @Test
    void producesDifferentCiphertextPerEncryption() {
        SecretCipher cipher = new SecretCipher(KEY_32_BYTES);
        assertNotEquals(cipher.encrypt("same-value"), cipher.encrypt("same-value"));
    }

    @Test
    void passesThroughPlaintextWhenKeyMissing() {
        SecretCipher cipher = new SecretCipher("");
        assertEquals("plain-secret", cipher.encrypt("plain-secret"));
        assertEquals("plain-secret", cipher.decrypt("plain-secret"));
    }

    @Test
    void decryptsLegacyPlaintextEvenWhenKeyConfigured() {
        SecretCipher cipher = new SecretCipher(KEY_32_BYTES);
        assertEquals("legacy-plaintext", cipher.decrypt("legacy-plaintext"));
    }

    @Test
    void encryptsBlankValueAsBlank() {
        SecretCipher cipher = new SecretCipher(KEY_32_BYTES);
        assertEquals("", cipher.encrypt(""));
        assertEquals(null, cipher.encrypt(null));
    }

    @Test
    void rejectsWrongLengthKey() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        assertThrows(IllegalStateException.class, () -> new SecretCipher(shortKey));
    }

    @Test
    void throwsWhenDecryptingEncryptedValueWithoutKey() {
        SecretCipher cipher = new SecretCipher("");
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("enc:v1:abc.def"));
    }
}
