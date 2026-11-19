package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * <b>占位表摘除的类型纪律</b>：{@code activeTurns} 的值是 {@code ActiveTurn}，因此
 * {@code Map.remove(key, value)} 的第二个实参<b>必须是</b> {@code ActiveTurn}。
 *
 * <p>为什么这需要一条用例：这里曾经有两处
 * {@code activeTurns.remove(conversationId, attempt.id())}——{@code attempt.id()} 是 {@code String}，
 * 于是 {@code ConcurrentHashMap} 会选中 {@code remove(Object, Object)}，类型不匹配、恒不命中。它编译通过、
 * 单测全绿、看起来还把「失败后不留占位」写在了代码里，实际上一次都没生效。这类缺陷在这里不是无害的：
 * 占位残留 = 该对话此后每次提交都 409 到进程重启（{@code activeTurns.containsKey} 是提交闸门）。
 *
 * <p>为什么用源码扫描而不是黑盒断言：那两个调用点在可达的状态空间里<b>不可能</b>命中——{@code failed} /
 * {@code refusedWithoutWire} 的全部调用点，要么早于 {@code activeTurns.put}（同步窗口），要么晚于
 * {@code handOff} / {@code awaitReply} 里已经做过的正确摘除。也就是说「恒不命中」的缺陷用运行时断言无法
 * 与「正确」区分开。能被机械判定的是<b>形状</b>：每个 {@code activeTurns.remove(...)} 的第二个实参都必须是
 * {@code active}（从 {@code entry.getValue()} / 局部变量拿到的 {@link AssistantConversationService.ActiveTurn}）
 * 或 {@code new ActiveTurn(...)}。比「删掉那两行」更强：删掉之后没人保证下一次写入不会再写成 String。
 *
 * <p>只读一个源文件，不启 Spring、不碰数据库、不调模型。
 */
class AssistantConversationActiveTurnRemovalTest {

    /** surefire 的 cwd 是模块根（{@code server/platform}）。 */
    private static final Path SERVICE = Path.of(
        "src/main/java/com/haitlify/aitest/platform/assistant/conversation/AssistantConversationService.java");

    @Test
    void everyActiveTurnsRemovalPassesAnActiveTurnAsTheValue() throws IOException {
        List<String> removals = removalCalls(codeOnly(Files.readString(SERVICE, StandardCharsets.UTF_8)));

        assertThat(removals)
            .as("占位表的摘除点必须存在（否则闸门永远不会被放开）")
            .isNotEmpty();
        for (String call : removals) {
            String value = secondArgument(call);
            assertThat(value)
                .as("activeTurns.remove 的第二个实参必须是 ActiveTurn，不能是消息 id（恒不命中）：%s", call)
                .satisfiesAnyOf(
                    argument -> assertThat(argument).startsWith("active"),
                    argument -> assertThat(argument).startsWith("new ActiveTurn("));
        }
    }

    /**
     * 失败出口必须走按消息 id 匹配的辅助方法：`failed` / `refusedWithoutWire` 都要能摘掉「本条消息」的
     * 占位，而不能依赖一个恒不命中的 {@code remove(k, v)}。
     */
    @Test
    void theFailureExitsRemoveTheirOwnPlaceholderById() throws IOException {
        String source = codeOnly(Files.readString(SERVICE, StandardCharsets.UTF_8));

        assertThat(source)
            .as("必须有一个按消息 id 匹配的摘除辅助方法（computeIfPresent 语义）")
            .contains("private void removeActiveTurn(String conversationId, String messageId)")
            .contains("activeTurns.computeIfPresent(conversationId,");
        assertThat(source)
            .as("failed() 与 refusedWithoutWire() 两个失败出口都必须用它")
            .contains("removeActiveTurn(attempt.conversationId(), attempt.id())");
        assertThat(count(source, "removeActiveTurn(attempt.conversationId(), attempt.id())"))
            .as("两个失败出口各一处（多一处/少一处都说明有人改回了 remove(k, String)）")
            .isEqualTo(2);
    }

    /** 允许的写法之外，任何 `activeTurns.remove(` 的数量都不该超过上面枚举到的那些。 */
    @Test
    void theServiceHasNoTypeMismatchedPlaceholderRemoval() throws IOException {
        String source = codeOnly(Files.readString(SERVICE, StandardCharsets.UTF_8));

        assertThat(source)
            .as("不得再出现 String 作 value 的占位摘除（恒不命中）")
            .doesNotContain("activeTurns.remove(conversationId, attempt.id())")
            .doesNotContain("activeTurns.remove(attempt.conversationId(), attempt.id())")
            .doesNotContain("activeTurns.remove(conversationId, messageId)");
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 去掉注释后的源码：注释里可以（也应该）写出反例，扫描只针对<b>代码</b>。
     *
     * <p>注释与字符串都按状态机跳过，避免把 {@code /* ... *}{@code /} 里的示例当成真实调用。
     */
    private static String codeOnly(String source) {
        StringBuilder code = new StringBuilder(source.length());
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inString = false;
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (inLineComment) {
                if (current == '\n') {
                    inLineComment = false;
                    code.append(current);
                }
                index++;
                continue;
            }
            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    inBlockComment = false;
                    index += 2;
                    continue;
                }
                if (current == '\n') {
                    code.append(current);
                }
                index++;
                continue;
            }
            if (inString) {
                code.append(current);
                if (current == '\\') {
                    code.append(next);
                    index += 2;
                    continue;
                }
                if (current == '"') {
                    inString = false;
                }
                index++;
                continue;
            }
            if (current == '/' && next == '/') {
                inLineComment = true;
                index += 2;
                continue;
            }
            if (current == '/' && next == '*') {
                inBlockComment = true;
                index += 2;
                continue;
            }
            if (current == '"') {
                inString = true;
            }
            code.append(current);
            index++;
        }
        return code.toString();
    }

    /** 抽出所有 `activeTurns.remove(` 调用（含跨行的实参），按源码顺序返回。 */
    private static List<String> removalCalls(String source) {
        List<String> calls = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = source.indexOf("activeTurns.remove(", from);
            if (at < 0) {
                return calls;
            }
            int open = source.indexOf('(', at);
            int depth = 0;
            int index = open;
            for (; index < source.length(); index++) {
                char current = source.charAt(index);
                if (current == '(') {
                    depth++;
                } else if (current == ')') {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                }
            }
            calls.add(source.substring(at, index + 1));
            from = index + 1;
        }
    }

    /** 顶层逗号切分后取第二个实参（去掉首尾空白与换行）。 */
    private static String secondArgument(String call) {
        String arguments = call.substring(call.indexOf('(') + 1, call.length() - 1);
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char character : arguments.toCharArray()) {
            if (character == '(' || character == '{') {
                depth++;
            } else if (character == ')' || character == '}') {
                depth--;
            }
            if (character == ',' && depth == 0) {
                parts.add(current.toString().strip());
                current.setLength(0);
                continue;
            }
            current.append(character);
        }
        parts.add(current.toString().strip());
        return parts.size() > 1 ? parts.get(1) : "";
    }

    private static int count(String source, String needle) {
        int total = 0;
        int from = 0;
        while (true) {
            int at = source.indexOf(needle, from);
            if (at < 0) {
                return total;
            }
            total++;
            from = at + needle.length();
        }
    }
}