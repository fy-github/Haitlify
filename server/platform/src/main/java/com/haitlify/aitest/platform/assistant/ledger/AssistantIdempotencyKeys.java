package com.haitlify.aitest.platform.assistant.ledger;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 受理账本的幂等键派生（A0 §5.7 / 计划 §5.4）。
 *
 * <p>键只在服务端生成：{@code HMAC-SHA256(masterKey, domain | tenant | operation | revision | action | digest)}。
 * 模型提交的任何 key 都没有入口——仓库不接受外部传入的键（测试用的包内方法仅用于模拟「模型换键绕过」）。
 *
 * <p>主密钥复用 {@code APP_SECRET_KEY}（与 {@code SecretCipher} 同一把，Base64 32 字节），并做域分离。
 * <b>缺密钥时在首次派生处失败</b>（不是启动失败，也不是降级成可预测值）：幂等键若可预测，唯一约束就形同虚设。
 */
@Component
public class AssistantIdempotencyKeys {

    static final String DOMAIN = "assistant-idempotency-v1";
    private static final int KEY_BYTES = 32;

    private final String configured;
    private volatile byte[] masterKey;

    public AssistantIdempotencyKeys(@Value("${APP_SECRET_KEY:}") String base64Key) {
        this.configured = base64Key == null ? "" : base64Key.trim();
    }

    /** 派生幂等键（64 位小写十六进制）。参数为空或格式非法时直接拒绝，不给「空参数退化成固定键」的机会。 */
    public String derive(long tenantId, String operationId, String targetRevision, String actionCode, String inputDigest) {
        requireText(operationId, "operationId");
        requireText(targetRevision, "targetRevision");
        requireText(actionCode, "actionCode");
        requireText(inputDigest, "inputDigest");
        String message = String.join("|", DOMAIN, Long.toString(tenantId), operationId,
            targetRevision, actionCode, inputDigest.toLowerCase(Locale.ROOT));
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKey(), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 不可用", ex);
        }
    }

    /** 懒加载 + 双重检查：密钥缺失/长度不对时抛错，错误信息不含密钥内容。 */
    private byte[] masterKey() {
        byte[] cached = masterKey;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (masterKey == null) {
                masterKey = decode();
            }
            return masterKey;
        }
    }

    private byte[] decode() {
        if (configured.isEmpty()) {
            throw new IllegalStateException(
                "缺少 APP_SECRET_KEY：受理账本的幂等键必须由服务端持有主密钥派生，未配置时拒绝生成键");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("APP_SECRET_KEY 不是合法的 Base64", ex);
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException(
                "APP_SECRET_KEY 必须是 " + KEY_BYTES + " 字节的 Base64，当前为 " + decoded.length + " 字节");
        }
        return decoded;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空：幂等键不接受空参数");
        }
    }
}
