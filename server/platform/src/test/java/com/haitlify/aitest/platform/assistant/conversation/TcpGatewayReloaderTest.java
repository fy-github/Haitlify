package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@link TcpGatewayReloader} 的<b>判定逻辑</b>：只走一个脚本化的 RPC 桩（{@link GatewayRpc}），不连网。
 *
 * <p>为什么判定要单独钉死：pin 的 {@code reload.mcp} 在开启确认策略时<b>成功返回</b>
 * {@code {status: "confirm_required", message: ...}}——那是一个成功的 RPC，但<b>什么都没重载</b>。
 * 把它当成功，平台就会宣称“本轮 MCP 已生效”而网关仍按旧条目（或空条目）建会话。成功的唯一证据是
 * {@code status == "reloaded"} 且 {@code loaded_rev} 非空（{@code server.py:_finish_reload}）。
 *
 * <p>这里同时钉住请求形状：方法名必须是 {@code reload.mcp}，必须带 {@code confirm=true}，默认预算 60s
 * （reload 会全局 shutdown + rediscover）。
 */
class TcpGatewayReloaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sendsReloadMcpWithConfirmTrueAndReturnsTheLoadedRevision() {
        RecordingRpc rpc = new RecordingRpc("""
            {"jsonrpc":"2.0","id":1,"result":{"status":"reloaded","loaded_rev":"0123456789ab"}}""");

        GatewayMcpReloadReceipt receipt = reloader(rpc).reload();

        assertThat(rpc.methods).containsExactly("reload.mcp");
        assertThat(rpc.params.get(0).path("confirm").isBoolean()).isTrue();
        assertThat(rpc.params.get(0).path("confirm").booleanValue())
            .as("不带 confirm=true 时开启确认策略的网关只回 confirm_required，并不会重载")
            .isTrue();
        assertThat(rpc.timeouts.get(0))
            .as("reload 会全局 shutdown + rediscover，默认预算 60s")
            .isEqualTo(Duration.ofSeconds(60));
        assertThat(receipt.loadedRev()).isEqualTo("0123456789ab");
        assertThat(receipt.coalesced()).isFalse();
    }

    @Test
    void reportsCoalescingWhenTheGatewaySkippedADuplicateReload() {
        RecordingRpc rpc = new RecordingRpc("""
            {"jsonrpc":"2.0","id":1,"result":{"status":"reloaded","loaded_rev":"abcdef012345","coalesced":true}}""");

        GatewayMcpReloadReceipt receipt = reloader(rpc).reload();

        assertThat(receipt.coalesced()).isTrue();
        assertThat(receipt.loadedRev()).isEqualTo("abcdef012345");
    }

    /** 确认策略生效：RPC 成功但没重载 —— 明确失败，绝不返回回执。 */
    @Test
    void treatsConfirmRequiredAsAnExplicitFailureRatherThanAReload() {
        RecordingRpc rpc = new RecordingRpc("""
            {"jsonrpc":"2.0","id":1,"result":{"status":"confirm_required",
            "message":"⚠️ /reload-mcp invalidates the prompt cache"}}""");

        assertReloadCode(() -> reloader(rpc).reload(), GatewayReloadException.Code.CONFIRM_REQUIRED);
        assertThat(rpc.methods).as("失败也要证明请求确实发出过").containsExactly("reload.mcp");
    }

    /** reloaded 但没有 loaded_rev：无法证明「网关加载的是刚写的配置」，按失败处理。 */
    @Test
    void failsWhenTheLoadedRevisionIsMissingOrBlank() {
        for (String frame : List.of(
            """
                {"jsonrpc":"2.0","id":1,"result":{"status":"reloaded"}}""",
            """
                {"jsonrpc":"2.0","id":1,"result":{"status":"reloaded","loaded_rev":""}}""",
            """
                {"jsonrpc":"2.0","id":1,"result":{"status":"reloaded","loaded_rev":"   "}}""",
            """
                {"jsonrpc":"2.0","id":1,"result":{"status":"reloaded","loaded_rev":null}}""")) {
            assertReloadCode(() -> reloader(new RecordingRpc(frame)).reload(),
                GatewayReloadException.Code.MISSING_LOADED_REV);
        }
    }

    @Test
    void failsOnAJsonRpcErrorFrame() {
        RecordingRpc rpc = new RecordingRpc("""
            {"jsonrpc":"2.0","id":1,"error":{"code":5019,"message":"mcp reload failed"}}""");

        assertReloadCode(() -> reloader(rpc).reload(), GatewayReloadException.Code.RPC_ERROR);
    }

    @Test
    void failsOnAnUnexpectedOrMissingStatus() {
        for (String frame : List.of(
            """
                {"jsonrpc":"2.0","id":1,"result":{"status":"pending"}}""",
            """
                {"jsonrpc":"2.0","id":1,"result":{"loaded_rev":"0123456789ab"}}""",
            """
                {"jsonrpc":"2.0","id":1,"result":{"status":42,"loaded_rev":"0123456789ab"}}""",
            """
                {"jsonrpc":"2.0","id":1,"result":"ok"}""",
            """
                {"jsonrpc":"2.0","id":1}""")) {
            assertReloadCode(() -> reloader(new RecordingRpc(frame)).reload(),
                GatewayReloadException.Code.UNEXPECTED_STATUS);
        }
    }

    /** 传输层失败 → 传输码，且<b>不链接</b>底层异常（它的消息里可能有 URL）。 */
    @Test
    void failsOnTransportFailureWithoutChainingOrLeakingTheUpstreamMessage() {
        String endpoint = "https://assistant.internal/mcp-lease/currentLease02";
        RecordingRpc rpc = new RecordingRpc("");
        rpc.failure = new IllegalStateException("connect failed for " + endpoint);

        Throwable thrown = catchThrowable(() -> reloader(rpc).reload());

        assertThat(thrown).isInstanceOfSatisfying(GatewayReloadException.class,
            error -> assertThat(error.code()).isEqualTo(GatewayReloadException.Code.TRANSPORT_FAILED));
        assertThat(thrown.getCause()).isNull();
        assertThat(thrown).hasMessageNotContaining(endpoint);
        assertThat(thrown).hasMessageNotContaining("currentLease02");
    }

    @Test
    void rejectsMissingConstructorDependencies() {
        assertThatThrownBy(() -> new TcpGatewayReloader(null, MAPPER))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TcpGatewayReloader(new RecordingRpc(""), null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TcpGatewayReloader(new RecordingRpc(""), MAPPER, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** {@code toString()} 不带任何回包内容（可能含 URL），只说明身份与预算。 */
    @Test
    void toStringIsRedacted() {
        String text = reloader(new RecordingRpc("")).toString();

        assertThat(text).contains("reload.mcp").doesNotContain("http");
    }

    // ---------------------------------------------------------------- 夹具

    private static TcpGatewayReloader reloader(RecordingRpc rpc) {
        return new TcpGatewayReloader(rpc, MAPPER);
    }

    private static void assertReloadCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable,
                                         GatewayReloadException.Code code) {
        assertThatThrownBy(callable)
            .isInstanceOfSatisfying(GatewayReloadException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }

    /** 脚本化 RPC：记录方法/参数/预算，按脚本回一帧（或抛传输错误）。 */
    private static final class RecordingRpc implements GatewayRpc {

        private final List<String> methods = Collections.synchronizedList(new ArrayList<>());
        private final List<JsonNode> params = Collections.synchronizedList(new ArrayList<>());
        private final List<Duration> timeouts = Collections.synchronizedList(new ArrayList<>());
        private final String frame;
        private volatile RuntimeException failure;

        RecordingRpc(String frame) {
            this.frame = frame;
        }

        @Override
        public JsonNode call(String method, JsonNode parameters, Duration timeout) {
            methods.add(method);
            params.add(parameters);
            timeouts.add(timeout);
            if (failure != null) {
                throw failure;
            }
            return MAPPER.readTree(frame);
        }
    }
}
