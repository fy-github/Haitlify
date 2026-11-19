package com.haitlify.aitest.platform.assistant.hermes;

import tools.jackson.databind.JsonNode;

/**
 * Hermes 发给宿主的服务端请求（server→client JSON-RPC request）。
 *
 * <p>固定 pin（{@code tui_gateway} d923a5eb48）的帧形：{@code
 * {"jsonrpc":"2.0","id":"srq-<12 hex>","method":"approval"|"clarify","params":{...}}}。
 * id 由网关用字符串生成（{@code tui_gateway/server_requests.py:67}），回包必须原样带回同一
 * id 与同一 JSON 类型；客户端不得自行编造 id。
 *
 * <p>只有宿主真正理解的阻塞式提问才建模在这里（审批 {@code approval}、澄清 {@code clarify}）。
 * 其他方法（预览、sudo、secret、tour、主题等）当前一律按未知方法 -32601 拒绝，不能因为「看起来
 * 无害」就代为应答——应答等于代替用户做决定。
 */
public record HermesServerRequest(Kind kind, JsonNode id, String method, String sessionId,
                                  JsonNode params, JsonNode raw) {

    /** {@code approval}：危险命令等待用户裁决；{@code clarify}：工具要求用户补充信息。 */
    public enum Kind { APPROVAL, CLARIFY }

    public HermesServerRequest {
        if (kind == null) {
            throw new IllegalArgumentException("只有已识别的服务端请求才能派发");
        }
        if (id == null || id.isMissingNode() || id.isNull()) {
            throw new IllegalArgumentException("服务端请求缺少 id，无法回包关联");
        }
        if (method == null || method.isBlank()) {
            throw new IllegalArgumentException("服务端请求缺少 method");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("服务端请求缺少 session_id，无法归属会话");
        }
    }

    /** 已识别的方法名映射；未知方法返回 {@code null}（调用方必须回 -32601）。 */
    public static Kind kindOf(String method) {
        if ("approval".equals(method)) {
            return Kind.APPROVAL;
        }
        if ("clarify".equals(method)) {
            return Kind.CLARIFY;
        }
        return null;
    }

    /** 审批的关联 id（{@code params.request_id}）；缺失或非文本时为空串，绝不用 RPC id 冒充。 */
    public String requestId() {
        JsonNode value = params == null ? null : params.get("request_id");
        return value != null && value.isTextual() && !value.asString().isBlank() ? value.asString() : "";
    }
}
