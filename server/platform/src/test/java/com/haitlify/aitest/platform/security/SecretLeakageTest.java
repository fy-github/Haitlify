package com.haitlify.aitest.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;


import tools.jackson.databind.ObjectMapper;

/**
 * T14 批次 2：金丝雀泄漏测试。注入 canary API key / Authorization / cookie / 工具 secret /
 * 错误回显，扫描 API 响应、事件与导出载荷——除合法 transport Authorization（HTTP 传输层，
 * 不经过载荷）外，任何出站文本中都不得出现 canary。
 */
class SecretLeakageTest {

    /**
     * 金丝雀：只在测试内使用，形态同时覆盖 Bearer 与 sk- 两种规则。
     * 注意：本常量会以完整形态出现在断言里（这正是「泄漏检测」的定义），不是真实凭据。
     */
    private static final String CANARY = "s" + "k-" + "synthetic-canary-9f3e7a1b2c4d5e6f";

    private static final String AWS_CANARY = "A" + "KIA" + "SYNTHETIC-CANARY";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void maskingCoversEverySecretShapeThatCanAppearOutbound() {
        String dirty = "call failed: Authorization: Bearer " + CANARY
            + " cookie: session=abc123def456; api_key=zzz-9999-8888; url=https://x/y?token=" + CANARY
            + " aws=" + AWS_CANARY;
        String masked = PayloadSanitizer.mask(dirty);
        assertThat(masked).doesNotContain(CANARY);
        assertThat(masked).doesNotContain("abc123def456");
        assertThat(masked).doesNotContain("zzz-9999-8888");
        assertThat(masked).doesNotContain(AWS_CANARY);
        assertThat(masked).contains("***");
        // 非秘密文本原样保留。
        assertThat(PayloadSanitizer.mask("call failed: HTTP 500 at /v1/chat"))
            .isEqualTo("call failed: HTTP 500 at /v1/chat");
    }

    @Test
    void canaryScanFindsLeaksInAnyOutboundPayloadWithJsonPointers() throws Exception {
        String payload = "{\"run\":{\"summary\":\"failed with " + CANARY + "\"},"
            + "\"attempts\":[{\"detail\":\"ok\"},{\"requestHeaders\":{\"authorization\":\"Bearer "
            + CANARY + "\"}}],"
            + "\"export\":{\"notes\":[\"clean\",\"tool secret: " + CANARY + "\"]}}";
        var node = objectMapper.readTree(payload);
        List<String> hits = PayloadSanitizer.scan(node, CANARY);
        assertThat(hits).containsExactlyInAnyOrder(
            "/run/summary", "/attempts/1/requestHeaders/authorization", "/export/notes/1");

        // 脱敏后再扫描：必须干净（封口断言）。
        var masked = objectMapper.readTree(PayloadSanitizer.maskJson(node));
        assertThat(PayloadSanitizer.scan(masked, CANARY)).isEmpty();
    }

    @Test
    void aCleanPayloadReportsNoViolations() throws Exception {
        var node = objectMapper.readTree("""
            {"summary":"运行成功","model":"qwen-max","durationMs":812,
             "citations":["ev-1","ev-2"],"headers":{"accept":"application/json"}}
            """);
        assertThat(PayloadSanitizer.scan(node, CANARY)).isEmpty();
        // 只有传输层 Authorization 是合法例外：它不进入任何载荷（因此不会出现在这里）。
        assertThat(PayloadSanitizer.scan(node, "Bearer")).isEmpty();
    }
}
