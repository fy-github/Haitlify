package com.haitlify.aitest.platform.assistant.compile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** 输入绑定：占位符替换、未知键/超长/换行/密钥形态一律拒绝，digest 可复现。 */
class AssistantInputBindingServiceTest {

    private final AssistantInputBindingService binding = new AssistantInputBindingService();

    private AssistantScriptCompiler.CaseSpec spec() {
        return new AssistantScriptCompiler.CaseSpec("case-bind", "绑定夹具", "http://127.0.0.1:19399/f.html",
            List.of(
                new AssistantScriptCompiler.Step("s1", "input", "#menu-search", "${input.keyword}"),
                // 断言类步骤不允许占位符；这里用普通输入步骤承载第二个绑定值。
                new AssistantScriptCompiler.Step("s2", "input", "#company", "${input.expected}"),
                new AssistantScriptCompiler.Step("s3", "click", "#submit", null)));
    }

    @Test
    void substitutesPlaceholdersAndReportsKeys() {
        AssistantInputBindingService.BoundSpec bound = binding.bind(spec(),
            Map.of("keyword", "威胁情报", "expected", "共 3 条"));
        assertThat(bound.spec().steps().get(0).value()).isEqualTo("威胁情报");
        assertThat(bound.spec().steps().get(1).value()).isEqualTo("共 3 条");
        assertThat(bound.spec().steps().get(2).value()).isNull();
        assertThat(bound.boundKeys()).containsExactly("expected", "keyword");
        assertThat(bound.bindingDigest()).hasSize(64);
    }

    @Test
    void digestIsStableAndChangesWithValues() {
        String first = binding.bind(spec(), Map.of("keyword", "a", "expected", "b")).bindingDigest();
        String again = binding.bind(spec(), Map.of("expected", "b", "keyword", "a")).bindingDigest();
        String changed = binding.bind(spec(), Map.of("keyword", "a", "expected", "c")).bindingDigest();
        assertThat(first).isEqualTo(again);
        assertThat(changed).isNotEqualTo(first);
    }

    @Test
    void missingInputIsRejected() {
        assertThatThrownBy(() -> binding.bind(spec(), Map.of("keyword", "威胁情报")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("没有对应输入");
    }

    @Test
    void oversizedOrNewlineValuesAreRejected() {
        assertThatThrownBy(() -> binding.bind(spec(), Map.of("keyword", "x".repeat(201), "expected", "y")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("超长");
        assertThatThrownBy(() -> binding.bind(spec(), Map.of("keyword", "a\nrm -rf /", "expected", "y")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("换行");
    }

    @Test
    void secretShapedValuesAreRejected() {
        assertThatThrownBy(() -> binding.bind(spec(),
            Map.of("keyword", "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6", "expected", "y")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("密钥");
    }

    @Test
    void targetPlaceholdersAreRejected() {
        AssistantScriptCompiler.CaseSpec spec = new AssistantScriptCompiler.CaseSpec("case-bind", "绑定夹具",
            "http://127.0.0.1:19399/f.html",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "${input.next}", null)));
        assertThatThrownBy(() -> binding.bind(spec, Map.of("next", "https://attacker.example/")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("target");
    }

    @Test
    void digestSeparatesValuesThatWouldCollideUnderNaiveConcatenation() {
        // 旧实现 "a=1\nb=2\n" 会让这两个 map 撞同一个摘要。
        assertThat(AssistantInputBindingService.digest(Map.of("a", "1\nb=2")))
            .isNotEqualTo(AssistantInputBindingService.digest(Map.of("a", "1", "b", "2")));
    }
}
