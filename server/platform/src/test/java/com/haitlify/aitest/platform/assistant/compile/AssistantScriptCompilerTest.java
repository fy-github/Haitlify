package com.haitlify.aitest.platform.assistant.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/** 确定性编译：同样输入 → 同样 JSON 与 digest；白名单之外的动作/目标直接拒绝。 */
class AssistantScriptCompilerTest {

    private final AssistantScriptCompiler compiler = new AssistantScriptCompiler(new ObjectMapper(), true);

    private AssistantScriptCompiler.CaseSpec spec() {
        return new AssistantScriptCompiler.CaseSpec("case-1", "只读探索夹具", "http://127.0.0.1:19399/fixture.html",
            List.of(
                new AssistantScriptCompiler.Step("s1", "goto", "/fixture.html", null),
                new AssistantScriptCompiler.Step("s2", "input", "#menu-search", "威胁情报"),
                new AssistantScriptCompiler.Step("s3", "click", "#threat-menu", null),
                new AssistantScriptCompiler.Step("s4", "assertText", "[data-testid=\"counter\"]", "0")));
    }

    @Test
    void compilationIsDeterministic() {
        AssistantScriptCompiler.CompiledScript first = compiler.compile(spec());
        AssistantScriptCompiler.CompiledScript second = compiler.compile(spec());
        assertThat(first.payloadJson()).isEqualTo(second.payloadJson());
        assertThat(first.digest()).hasSize(64).isEqualTo(second.digest());
        assertThat(first.stepCount()).isEqualTo(4);
        assertThat(first.payloadJson()).contains("\"cases\"").contains("case-1").contains("assertText");
    }

    @Test
    void digestChangesWhenAnyInputChanges() {
        AssistantScriptCompiler.CompiledScript base = compiler.compile(spec());
        AssistantScriptCompiler.CaseSpec changed = new AssistantScriptCompiler.CaseSpec("case-1", "只读探索夹具",
            "http://127.0.0.1:19399/fixture.html",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "/fixture.html", null),
                new AssistantScriptCompiler.Step("s2", "input", "#menu-search", "威胁情报v2"),
                new AssistantScriptCompiler.Step("s3", "click", "#threat-menu", null),
                new AssistantScriptCompiler.Step("s4", "assertText", "[data-testid=\"counter\"]", "0")));
        assertThat(compiler.compile(changed).digest()).isNotEqualTo(base.digest());
    }

    @Test
    void rejectsActionsOutsideWhitelist() {
        AssistantScriptCompiler.CaseSpec bad = new AssistantScriptCompiler.CaseSpec("case-1", "x", "http://a/b",
            List.of(new AssistantScriptCompiler.Step("s1", "evaluate", "window.alert(1)", null)));
        assertThatThrownBy(() -> compiler.compile(bad))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不支持的动作");
    }

    @Test
    void rejectsNonHttpStartUrlAndUnsafeIds() {
        assertThatThrownBy(() -> compiler.compile(new AssistantScriptCompiler.CaseSpec("case-1", "x", "file:///etc/passwd",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "file:///etc/passwd", null)))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("http(s)");

        assertThatThrownBy(() -> compiler.compile(new AssistantScriptCompiler.CaseSpec("case 1; rm -rf /", "x",
            "http://a/b", List.of(new AssistantScriptCompiler.Step("s1", "goto", "http://a/b", null)))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("case.id");
    }

    @Test
    void rejectsDuplicateStepIdsAndEmptySteps() {
        assertThatThrownBy(() -> compiler.compile(new AssistantScriptCompiler.CaseSpec("case-1", "x", "http://a/b",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "http://a/b", null),
                new AssistantScriptCompiler.Step("s1", "click", "#a", null)))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("步骤 id 重复");

        assertThatThrownBy(() -> compiler.compile(new AssistantScriptCompiler.CaseSpec("case-1", "x", "http://a/b",
            List.of())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("至少需要一个步骤");
    }

    @Test
    void gotoRejectsDualTargetFields() {
        AssistantScriptCompiler.CaseSpec dual = new AssistantScriptCompiler.CaseSpec("case-1", "x", "http://a/b",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "file:///etc/passwd", "http://a/b")));
        assertThatThrownBy(() -> compiler.compile(dual))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("不允许同时提供 value");
    }

    @Test
    void unresolvedInputPlaceholderIsRejectedAtCompileTime() {
        AssistantScriptCompiler.CaseSpec unresolved = new AssistantScriptCompiler.CaseSpec("case-1", "x", "http://a/b",
            List.of(new AssistantScriptCompiler.Step("s1", "input", "#q", "${input.keyword}")));
        assertThatThrownBy(() -> compiler.compile(unresolved))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("未绑定");
    }

    @Test
    void gotoRejectsBackslashRelativeTargets() {
        AssistantScriptCompiler.CaseSpec spec = new AssistantScriptCompiler.CaseSpec("case-1", "x", "http://a/b",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "/\\evil.com", null)));
        assertThatThrownBy(() -> compiler.compile(spec))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("goto");
    }

    @Test
    void rejectsCaseWithoutAnyAssertion() {
        assertThatThrownBy(() -> compiler.compile(new AssistantScriptCompiler.CaseSpec("case-1", "x", "http://a/b",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "http://a/b", null),
                new AssistantScriptCompiler.Step("s2", "waitForVisible", "#a", null)))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("断言");
    }

    @Test
    void rejectsPrivateAndMetadataTargetsWhenPolicyIsStrict() {
        AssistantScriptCompiler strict = new AssistantScriptCompiler(new ObjectMapper(), false);
        for (String url : new String[] {"http://169.254.169.254/latest/meta-data/", "http://127.0.0.1:8080/admin",
            "http://localhost/", "http://[::1]/", "http://2130706433/", "http://0.0.0.0:19399/",
            "http://local.nip.io/"}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> strict.compile(
                    new AssistantScriptCompiler.CaseSpec("case-1", "x", url,
                        List.of(new AssistantScriptCompiler.Step("s1", "goto", "/", null),
                            new AssistantScriptCompiler.Step("s2", "assertVisible", "#a", null)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("私网");
        }
    }

    @Test
    void waitForTextRequiresAnExpectedTextAndIsWhitelisted() {
        AssistantScriptCompiler compiler = this.compiler;
        AssistantScriptCompiler.CaseSpec missing = new AssistantScriptCompiler.CaseSpec("case-dyn", "动态页",
            "http://127.0.0.1:19399/f.html",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "/f.html", null),
                new AssistantScriptCompiler.Step("s2", "waitForText", "#list", " "),
                new AssistantScriptCompiler.Step("s3", "assertVisible", "#list", null)));
        assertThatThrownBy(() -> compiler.compile(missing))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("期望文本");

        AssistantScriptCompiler.CaseSpec ok = new AssistantScriptCompiler.CaseSpec("case-dyn", "动态页",
            "http://127.0.0.1:19399/f.html",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "/f.html", null),
                new AssistantScriptCompiler.Step("s2", "waitForText", "#list", "共 3 条"),
                new AssistantScriptCompiler.Step("s3", "waitForNetworkIdle", "page", null),
                new AssistantScriptCompiler.Step("s4", "assertVisible", "#list", null)));
        assertThat(compiler.compile(ok).stepCount()).isEqualTo(4);
    }
}
