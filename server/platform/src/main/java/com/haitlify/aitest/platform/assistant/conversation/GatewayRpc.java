package com.haitlify.aitest.platform.assistant.conversation;

import java.time.Duration;

import tools.jackson.databind.JsonNode;

/**
 * 一次 JSON-RPC 调用的最小落点：{@code HermesTcpGateway::rpc}。
 *
 * <p>为什么是接口而不是直接依赖 {@code HermesTcpGateway}：reloader 只需要「发一条 RPC 拿回原始回包」这一件
 * 事，把它收窄成一个函数式接口后，判定逻辑可以用脚本化回包测（不需要连网），而真实实现仍然是<b>同一条连接、
 * 同一套换行分隔帧约定</b>——这里不重写协议，只是把网关客户端已有的调用路径引进来。
 *
 * <p>约定：返回的是<b>原始</b>回包（JSON-RPC {@code error} 不抛出，由调用方判定）；传输失败抛
 * {@link RuntimeException}。
 */
@FunctionalInterface
interface GatewayRpc {

    JsonNode call(String method, JsonNode params, Duration timeout);
}
