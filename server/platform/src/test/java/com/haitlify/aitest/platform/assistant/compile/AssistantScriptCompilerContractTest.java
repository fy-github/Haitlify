package com.haitlify.aitest.platform.assistant.compile;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * 契约测试：平台允许的动作必须是 Runner 实际支持的子集。
 * 两侧手工维护，一旦漂移，草稿会「确认成功、运行必败」——所以直接读 Runner 源码比对。
 */
class AssistantScriptCompilerContractTest {

    private static final Pattern SUPPORTED_BLOCK =
        Pattern.compile("SUPPORTED_STEP_ACTIONS\\s*=\\s*new Set\\(\\[([^\\]]*)\\]", Pattern.DOTALL);

    @Test
    void allowedActionsAreSubsetOfRunnerSupportedActions() throws Exception {
        Path runnerCore = Path.of("..", "web-runner", "runner-core.mjs");
        assertThat(Files.exists(runnerCore)).as("找不到 Runner 源码：%s", runnerCore).isTrue();

        Matcher matcher = SUPPORTED_BLOCK.matcher(Files.readString(runnerCore));
        assertThat(matcher.find()).as("无法从 runner-core.mjs 解析 SUPPORTED_STEP_ACTIONS").isTrue();
        Set<String> runnerActions = new LinkedHashSet<>();
        for (String token : matcher.group(1).split(",")) {
            String cleaned = token.trim().replace("'", "").replace("\"", "");
            if (!cleaned.isEmpty()) {
                runnerActions.add(cleaned);
            }
        }

        assertThat(runnerActions).isNotEmpty();
        assertThat(AssistantScriptCompiler.ALLOWED_ACTIONS)
            .as("平台白名单必须是 Runner 支持集的子集；Runner=%s", runnerActions)
            .isSubsetOf(runnerActions);
    }
}
