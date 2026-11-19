package com.haitlify.aitest.platform.assistant.hermes;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 服务端请求（审批 / 澄清）的显式处理器。
 *
 * <p>宿主必须注入一个真实处理器才会「回答」Hermes 的阻塞式提问；没有注入时使用
 * {@link #failClosed()}：审批一律 {@code {"choice":"deny"}}，澄清一律取消（回空结果，
 * 由 pin 侧合并成 {@code outcome:"cancelled"}），绝不因为「没配置」而默认放行。
 *
 * <p>返回值是 JSON-RPC 的 {@code result} 字段（原样回写，不再加工），因此实现必须自己保证
 * 不批准没有经过人工/业务决策的命令。返回 {@code null} 或非对象时，传输层按错误帧 fail-closed。
 *
 * <p>处理器在网关的读线程上同步执行：必须快速返回（阻塞会停住整条连接的事件流），且不得回调网关
 * 自身（调用方此时可能正持锁等待这条连接的回包）。
 */
@FunctionalInterface
public interface HermesServerRequestHandler {

    /** Hermes 侧 {@code approval} 的 response result 形状：{@code {"choice":"once|session|always|deny","all"?:bool}}。 */
    JsonNode answer(HermesServerRequest request);

    /**
     * 默认处理器：一律拒绝。
     *
     * <ul>
     *   <li>审批 → {@code {"choice":"deny"}}（pin 的 {@code _emit_approval_request.on_result} 读 {@code choice}，
     *       缺失也按 deny 处理，这里显式写 deny，不留解释空间）；</li>
     *   <li>澄清 → {@code {}}（pin 的 {@code resolve_response} 对批量问题在缺少 {@code answers} 键时判为
     *       cancel-all，工具拿到 {@code {"answers":{},"outcome":"cancelled"}}；绝不编造答案）。</li>
     * </ul>
     */
    static HermesServerRequestHandler failClosed() {
        return request -> switch (request.kind()) {
            case APPROVAL -> JsonNodeFactory.instance.objectNode().put("choice", "deny");
            case CLARIFY -> JsonNodeFactory.instance.objectNode();
        };
    }
}
