package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * <b>文档披露闸门</b>：{@code mcp/README.md} 关于「许可被占时会发生什么」的描述必须与实现一致。
 *
 * <p>为什么这需要一条用例：这条 README 曾经写着「非持有者以<b>无工具</b>运行」，而探针证明那是假的
 * ——非持有者的会话在创建时会绑定持有者的 URL，属于能力错配而不是降级。文档与实现不一致在这里不是笔误，
 * 而是把一个<b>能力错配</b>写成了一次可接受的取舍：读到它的人会据此认为并行对话是安全的。因此把
 * 「必须说出来」的三件事与「不许再说」的两句话都钉住：
 * <ul>
 *   <li>必须说：许可被占 → <b>显式拒绝</b>（503 + {@code MCP_PERMIT_BUSY}，不派发、不建会话）；</li>
 *   <li>必须说：条目在<b>会话创建时绑定</b>，因此并行对话<b>必须被拒绝</b>；</li>
 *   <li>必须说：多用户之前仍需要 <b>per-session 条目</b>与<b>部署级许可</b>；</li>
 *   <li>不许说：「以无工具运行 / that turn has no MCP tools」这类把拒绝伪装成降级的表述。</li>
 * </ul>
 *
 * <p>只读一个文件，不启 Spring、不碰数据库、不调模型。
 */
class GatewayMcpReadmePermitDisclosureTest {

    /** surefire 的 cwd 是模块根（{@code server/platform}）。 */
    private static final Path README = Path.of(
        "src/main/java/com/haitlify/aitest/platform/assistant/mcp/README.md");

    @Test
    void theReadmeSaysAParallelTurnIsRefusedRatherThanRunToolLess() throws IOException {
        String readme = Files.readString(README, StandardCharsets.UTF_8);

        assertThat(readme)
            .as("许可被占必须是显式拒绝，并且说清 503 与 MCP_PERMIT_BUSY")
            .contains("Non-holder → explicit refusal")
            .contains("HTTP 503")
            .contains("`MCP_PERMIT_BUSY`");
        assertThat(readme)
            .as("必须说明「不派发、不建会话」——这是拒绝与降级的区别")
            .contains("is **not dispatched**")
            .contains("no gateway session is created");
        assertThat(readme)
            .as("必须把「以无工具运行」明确标记为不安全（否则等于把能力错配写成取舍）")
            .contains("would **not** be a safe degradation");

        for (String forbidden : List.of("runs without tools", "no MCP tools for that turn", "tool-less")) {
            assertThat(readme)
                .as("不得再把非持有者描述成「无工具运行」：%s", forbidden)
                .doesNotContain(forbidden);
        }
    }

    @Test
    void theReadmeNamesWhatMultiUserDeploymentStillNeeds() throws IOException {
        String readme = Files.readString(README, StandardCharsets.UTF_8);

        assertThat(readme)
            .as("必须说清条目在会话创建时绑定，因此并行对话只能被拒绝")
            .contains("bound when the session is created")
            .contains("Concurrency policy — a parallel conversation is refused, not degraded");
        assertThat(readme)
            .as("多用户之前仍需两件事：per-session 条目 + 部署级许可")
            .contains("per-session MCP entries")
            .contains("deployment-level permit");
        assertThat(readme)
            .as("终态清除条目的顺序（仍持许可时 apply(null)，然后才释放）必须写在文档里")
            .contains("still holds the permit")
            .contains("`apply(null)`");
    }
}
