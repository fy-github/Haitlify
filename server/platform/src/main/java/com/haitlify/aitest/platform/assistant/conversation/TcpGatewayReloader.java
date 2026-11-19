package com.haitlify.aitest.platform.assistant.conversation;

import java.time.Duration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 真实的 {@link GatewayReloader}：向网关发 {@code reload.mcp}，参数 {@code {confirm: true}}。
 *
 * <p><b>与传输无关</b>：它只依赖「发一条 RPC、拿回原始回包」这一个函数（{@link GatewayRpc}），所以
 * {@code transport=tcp}（{@code HermesTcpGateway::rpc}）与 {@code transport=stdio}
 * （{@code HermesTuiGateway::rpc}）共用同一份判定；类名保留首字母 T 只是因为 TCP 是第一个落点。
 * 判定逻辑绝不能按传输分叉——那样「确认/缺 loaded_rev/传输失败」三条纪律就会慢慢走样。
 *
 * <p><b>为什么必须带 {@code confirm=true}</b>：pin 的 {@code reload.mcp} 会先过一次 prompt-cache 确认闸门
 * （{@code tui_gateway/methods_tools.py}）。不带 confirm 且确认策略开启时，它<b>正常返回</b>
 * {@code {status: "confirm_required", message}}——RPC 成功、什么都没重载。这里是自动化调用，没有人可以回
 * 「yes」，所以必须显式确认，并把 {@code confirm_required} 当<b>失败</b>上报
 * （{@link GatewayReloadException.Code#CONFIRM_REQUIRED}）。
 *
 * <p><b>成功判定</b>：{@code status == "reloaded"} 且 {@code loaded_rev} 非空
 * （{@code tui_gateway/server.py:_finish_reload}）。{@code coalesced: true} 照实转述——合并到一次已完成的
 * reload 是成功，但不是「刚刚又重载了一次」。
 *
 * <p><b>作用域</b>：不带 {@code session_id}，所以这是<b>全局</b>操作：网关会 shutdown + rediscover 所有 MCP
 * server，不区分会话（也因此 pin 的 compute-host/session 分支不会命中——那条路径不回 {@code loaded_rev}）。
 * 这正是控制面要串行化的原因：一次 reload 会重建整个注册表，预算按 60s 给。
 *
 * <p><b>脱敏</b>：异常只带原因码、不链接底层异常（传输层消息可能含 URL）；{@link #toString()} 固定脱敏。
 */
final class TcpGatewayReloader implements GatewayReloader {

    /** pin 的方法名（{@code tui_gateway/methods_tools.py}）。 */
    static final String METHOD = "reload.mcp";

    /** 默认预算：一次 reload 会全局 shutdown + rediscover，慢但正常的情况并不罕见。 */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private final GatewayRpc rpc;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    TcpGatewayReloader(GatewayRpc rpc, ObjectMapper objectMapper) {
        this(rpc, objectMapper, DEFAULT_TIMEOUT);
    }

    TcpGatewayReloader(GatewayRpc rpc, ObjectMapper objectMapper, Duration timeout) {
        if (rpc == null) {
            throw new IllegalArgumentException("gateway rpc required");
        }
        if (objectMapper == null) {
            throw new IllegalArgumentException("object mapper required");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("positive reload timeout required");
        }
        this.rpc = rpc;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
    }

    @Override
    public GatewayMcpReloadReceipt reload() {
        ObjectNode params = objectMapper.createObjectNode();
        // 必须显式确认：否则确认策略开启时网关只回 confirm_required，一个 server 都不会重新发现。
        params.put("confirm", true);
        JsonNode response;
        try {
            response = rpc.call(METHOD, params, timeout);
        } catch (RuntimeException transportFailure) {
            // 不链接 cause：传输层的消息里可能有 URL / lease id。
            throw new GatewayReloadException(GatewayReloadException.Code.TRANSPORT_FAILED);
        }
        if (response == null || !response.isObject()) {
            throw new GatewayReloadException(GatewayReloadException.Code.UNEXPECTED_STATUS);
        }
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new GatewayReloadException(GatewayReloadException.Code.RPC_ERROR);
        }
        JsonNode result = response.path("result");
        if (!result.isObject()) {
            throw new GatewayReloadException(GatewayReloadException.Code.UNEXPECTED_STATUS);
        }
        JsonNode statusNode = result.path("status");
        String status = statusNode.isTextual() ? statusNode.asString() : "";
        if ("confirm_required".equals(status)) {
            // 成功的 RPC ≠ 重载。这是本条链路上最容易误判成成功的一种回包。
            throw new GatewayReloadException(GatewayReloadException.Code.CONFIRM_REQUIRED);
        }
        if (!"reloaded".equals(status)) {
            throw new GatewayReloadException(GatewayReloadException.Code.UNEXPECTED_STATUS);
        }
        JsonNode revisionNode = result.path("loaded_rev");
        String loadedRev = revisionNode.isTextual() ? revisionNode.asString() : "";
        if (loadedRev.isBlank()) {
            throw new GatewayReloadException(GatewayReloadException.Code.MISSING_LOADED_REV);
        }
        JsonNode coalescedNode = result.path("coalesced");
        boolean coalesced = coalescedNode.isBoolean() && coalescedNode.booleanValue();
        try {
            return new GatewayMcpReloadReceipt(loadedRev, coalesced);
        } catch (IllegalArgumentException malformed) {
            // loaded_rev 存在但不是可用的形状：同样无法证明「加载了哪一版」。
            throw new GatewayReloadException(GatewayReloadException.Code.MISSING_LOADED_REV);
        }
    }

    /** {@code loaded_rev} 只能是短十六进制摘要（pin: {@code sha1(...)[:12]}）。 */
    static boolean isRevisionCharacter(int chr) {
        return (chr >= '0' && chr <= '9') || (chr >= 'a' && chr <= 'f') || (chr >= 'A' && chr <= 'F');
    }

    @Override
    public String toString() {
        return "TcpGatewayReloader[method=" + METHOD + ", timeout=" + timeout.toSeconds() + "s, redacted]";
    }
}
