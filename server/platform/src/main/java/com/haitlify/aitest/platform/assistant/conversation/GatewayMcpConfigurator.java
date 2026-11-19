package com.haitlify.aitest.platform.assistant.conversation;

/**
 * 端口：把「本轮唯一可用」的 MCP 端点交给网关。
 *
 * <p>为什么需要这一层：真实模型经 Hermes 会话调用平台私有 MCP 时，网关侧只能有一个 MCP 服务器条目
 * （一个 URL、且不能带 grant token）。平台自己铸租约之后，必须有人把
 * {@code <base>/mcp-lease/<leaseId>} 写进那个条目并触发 reload；服务层只依赖本接口，真实实现
 * （{@link GatewayMcpLeaseConfigurator}）与测试桩可以互换，对话链路不需要知道那是文件、API 还是 CLI。
 *
 * <p><b>约定（实现方必须遵守）</b>：
 * <ol>
 *   <li>调用发生在<b>本轮武装之后、派发之前</b>：网关必须在会话建立前看到本轮 URL，否则模型这一轮
 *       拿到的是上一轮的（已撤销）URL，或者根本没有 MCP 服务器；</li>
 *   <li>{@code leaseEndpoint == null} 表示<b>本轮没有端点</b>（无 ACTIVE binding / 武装失败）。
 *       此时必须把网关的 MCP 条目置为不可用，<b>不得保留上一次的 URL、也不得跳过调用</b>——
 *       跳过等于让旧 URL 继续挂在配置里，那是「回退到旧能力」而不是 fail-closed；</li>
 *   <li>实现内部顺序固定为「先写条目，再 reload」（见 {@code mcp/README.md}）；
 *       写失败就不要 reload，直接抛出，由调用方按「本轮无 MCP 端点」处理。</li>
 * </ol>
 *
 * <p><b>谁能调用</b>：只有持有网关级 MCP 许可的那一轮（见
 * {@code AssistantConversationService}）。条目在会话创建时被绑定且全局唯一，所以非持有者连
 * {@code apply(null)} 都不许调用——那会清掉持有者的条目；许可被占期间它的提交会被<b>显式拒绝</b>
 * （不武装、不配置、不派发），因此<b>不存在</b>「非持有者以无工具运行」的那种会话：那个会话在创建时
 * 照样会绑定持有者的条目。这不是「反正撤销了会 401」能兜住的——条目被读走的那一刻能力就已经错配了。
 *
 * <p>调用点有三处，且都只在<b>持有许可</b>的那一轮上：本轮武装之后的写入
 * （{@code <base>/mcp-lease/<leaseId>}）、本轮<b>没有端点</b>时的 {@code null}（fail-closed 清除）、
 * 以及本轮<b>终态</b>时的 {@code null}（先清条目、再释放许可，见
 * {@code AssistantConversationService#clearGatewayEntryAndReleasePermit}）。
 *
 * <p>本接口默认<b>不装配</b>任何实现（{@code app.assistant.conversation.mcp-lease.configure-gateway=false}），
 * 因为真实实现会写网关配置文件并触发 reload；没有实现时服务层一次都不调用。
 */
interface GatewayMcpConfigurator {

    /**
     * 让网关的下一次会话看到本轮的 MCP 端点（或明确地没有端点）。
     *
     * @param leaseEndpoint 本轮租约 URL；{@code null} = 本轮无端点，必须置为不可用
     * @throws RuntimeException 写配置 / reload 失败；调用方只把它当作「本轮 MCP 不可用」，
     *                          绝不允许因此把对话轮次写成成功
     */
    void apply(String leaseEndpoint);
}
