package com.haitlify.aitest.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Encrypts and decrypts secrets stored at rest (AI API keys, Jira tokens) with AES-256-GCM.
 *
 * <p>The master key is read from the {@code APP_SECRET_KEY} environment variable (a Base64-encoded
 * 32-byte value). When it is absent the cipher degrades to a plaintext pass-through so existing
 * deployments keep working; a startup warning flags the missing key. Ciphertext is self-describing
 * ({@code enc:v1:<iv>.<ciphertext>}), so legacy plaintext rows and encrypted rows can coexist.</p>
 */
@Component
public class SecretCipher {

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKey key;

    public SecretCipher(@Value("${APP_SECRET_KEY:}") String base64Key) {
        this.key = resolveKey(base64Key);
        if (this.key == null) {
            log.warn("APP_SECRET_KEY 未配置，密钥将以明文存储。建议设置 32 字节 Base64 的 APP_SECRET_KEY 以启用静态加密。");
        }
    }

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        if (key == null) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + base64Url(iv) + "." + base64Url(ciphertext);
        } catch (Exception exception) {
            throw new IllegalStateException("无法加密密钥", exception);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            return stored;
        }
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }
        if (key == null) {
            throw new IllegalStateException("存在已加密的密钥但未配置 APP_SECRET_KEY，无法解密");
        }
        try {
            String body = stored.substring(PREFIX.length());
            int dot = body.indexOf('.');
            if (dot < 0) {
                throw new IllegalArgumentException("密文格式非法");
            }
            byte[] iv = base64UrlDecode(body.substring(0, dot));
            byte[] ciphertext = base64UrlDecode(body.substring(dot + 1));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("无法解密密钥", exception);
        }
    }

    private SecretKey resolveKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        byte[] bytes = Base64.getDecoder().decode(base64Key.trim());
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException(
                "APP_SECRET_KEY 必须是 " + KEY_BYTES + " 字节的 Base64 编码，当前为 " + bytes.length + " 字节"
            );
        }
        return new SecretKeySpec(bytes, "AES");
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private byte[] base64UrlDecode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
