package com.haitlify.aitest.platform.ai.runtime;

import java.io.IOException;
import java.net.http.HttpTimeoutException;

import com.haitlify.aitest.platform.ai.gateway.AiRetryReason;

/**
 * T05 失败分类器（规则版本 {@link #RULE_VERSION}）：把「HTTP 状态 / 网络异常 / 响应体语义 / 业务文案」
 * 映射到 {@link AiRetryReason} 与 retryable，规则精确且版本化——修改规则必须提升 RULE_VERSION
 * 并由 AiFailureRoutingTest 的版本钉住断言同步更新（业务 message 精确规则不许隐性漂移）。
 *
 * <p>v1 规则表（节选，完整以测试为准）：
 * <ul>
 *   <li>408/429 ⇒ TIMEOUT/RATE_LIMITED（可重试）；502/503/504 与其他 5xx ⇒ UPSTREAM_ERROR（可重试）</li>
 *   <li>401/403 ⇒ AUTH_DENIED；404 ⇒ MODEL_OR_ENDPOINT_NOT_FOUND；其余 4xx ⇒ INVALID_REQUEST（皆不可重试）</li>
 *   <li>无状态码：HttpTimeoutException ⇒ TIMEOUT；IOException ⇒ TRANSIENT_NETWORK（可重试）</li>
 *   <li>响应体：refusal 字段/content_filter ⇒ REFUSAL（不可重试）；finish_reason=length ⇒ INCOMPLETE_OUTPUT（不可重试）；
 *       空内容 ⇒ EMPTY_OUTPUT（可重试一次，空内容多为上游瞬态）</li>
 *   <li>文案（小写包含匹配，命中即优先于状态码）：no available channel/无可用渠道 ⇒ NO_CHANNEL（不可重试）；
 *       content policy/content_policy/safety filter ⇒ REFUSAL（不可重试）</li>
 * </ul>
 */
public final class AiFailureClassifier {

    public static final String RULE_VERSION = "v1";

    private AiFailureClassifier() {
    }

    public record Classification(AiRetryReason reason, boolean retryable, String ruleId) {
        public String category() {
            return reason.name();
        }
    }

    /** 网络异常/HTTP 状态码分类（statusCode=0 表示无响应）。文案级规则由调用方先查 {@link #classifyMessage}。 */
    public static Classification classify(int statusCode, Throwable error) {
        if (error instanceof HttpTimeoutException) {
            return of(AiRetryReason.TIMEOUT, "v1_network_timeout");
        }
        if (error instanceof IOException) {
            return of(AiRetryReason.TRANSIENT_NETWORK, "v1_network_io");
        }
        if (statusCode == 408) {
            return of(AiRetryReason.TIMEOUT, "v1_http_408_timeout");
        }
        if (statusCode == 429) {
            return of(AiRetryReason.RATE_LIMITED, "v1_http_429_rate_limited");
        }
        if (statusCode == 502 || statusCode == 503 || statusCode == 504) {
            return of(AiRetryReason.UPSTREAM_ERROR, "v1_http_502_503_504_transient");
        }
        if (statusCode >= 500) {
            return of(AiRetryReason.UPSTREAM_ERROR, "v1_http_5xx_upstream");
        }
        if (statusCode == 401 || statusCode == 403) {
            return of(AiRetryReason.AUTH_DENIED, "v1_http_401_403_auth");
        }
        if (statusCode == 404) {
            return of(AiRetryReason.MODEL_OR_ENDPOINT_NOT_FOUND, "v1_http_404_not_found");
        }
        if (statusCode > 0) {
            return of(AiRetryReason.INVALID_REQUEST, "v1_http_4xx_invalid");
        }
        return error == null
            ? of(AiRetryReason.INVALID_REQUEST, "v1_no_status_no_error")
            : of(AiRetryReason.TRANSIENT_NETWORK, "v1_network_unknown");
    }

    /** HTTP 2xx 但内容不可用：按响应体语义分类。 */
    public static Classification classifyUnusableBody(String finishReason, boolean refusalPresent) {
        if (refusalPresent || "content_filter".equalsIgnoreCase(finishReason)) {
            return of(AiRetryReason.REFUSAL, "v1_body_refusal");
        }
        if ("length".equalsIgnoreCase(finishReason)) {
            return of(AiRetryReason.INCOMPLETE_OUTPUT, "v1_body_finish_length");
        }
        return of(AiRetryReason.EMPTY_OUTPUT, "v1_body_empty_content");
    }

    /** 业务文案级规则（小写包含匹配）；不命中返回 null，由调用方回退到状态码分类。 */
    public static Classification classifyMessage(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String lowered = message.toLowerCase();
        if (lowered.contains("no available channel") || lowered.contains("无可用渠道") || lowered.contains("no channel available")) {
            return of(AiRetryReason.NO_CHANNEL, "v1_message_no_channel");
        }
        if (lowered.contains("content policy") || lowered.contains("content_policy") || lowered.contains("safety filter")) {
            return of(AiRetryReason.REFUSAL, "v1_message_content_policy");
        }
        return null;
    }

    private static Classification of(AiRetryReason reason, String ruleId) {
        return new Classification(reason, reason.retryable(), ruleId);
    }
}
