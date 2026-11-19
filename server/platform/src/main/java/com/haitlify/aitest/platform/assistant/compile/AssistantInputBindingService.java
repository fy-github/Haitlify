package com.haitlify.aitest.platform.assistant.compile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/**
 * 输入绑定（计划 §6.4）：把需求里的普通输入填进已确认的用例结构。
 *
 * <p>占位符语法 {@code ${input.KEY}}；绑定是纯函数，产出新结构 + 绑定 digest。
 * 三条拒绝规则：
 * <ul>
 *   <li>占位符没有对应输入 ⇒ 拒绝（不允许留空跑）；</li>
 *   <li>输入值超长 / 含换行 ⇒ 拒绝（防止把整段脚本塞进步骤值）；</li>
 *   <li>值形似密钥（≥32 位十六进制/Base64 串）⇒ 拒绝（secretRef 走保险库，不进用例结构）。</li>
 * </ul>
 */
@Service
public class AssistantInputBindingService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{input\\.([A-Za-z0-9_.-]{1,64})\\}");
    private static final Pattern SECRET_SHAPED = Pattern.compile("^[A-Za-z0-9+/=_-]{32,}$");
    private static final int MAX_VALUE_LENGTH = 200;

    public record BoundSpec(AssistantScriptCompiler.CaseSpec spec, List<String> boundKeys, String bindingDigest) {}

    public BoundSpec bind(AssistantScriptCompiler.CaseSpec spec, Map<String, String> inputs) {
        if (spec == null) {
            throw new IllegalArgumentException("用例结构不能为空");
        }
        Map<String, String> safeInputs = inputs == null ? Map.of() : inputs;
        validateValues(safeInputs);

        List<AssistantScriptCompiler.Step> steps = new ArrayList<>();
        List<String> usedKeys = new ArrayList<>();
        for (AssistantScriptCompiler.Step step : spec.steps()) {
            String value = step.value();
            String target = step.target();
            if (target != null && target.contains("${input.")) {
                // 目标（含 goto 的 URL/选择器）不得被输入改写：否则确认的是 A 目标、执行的是 B 目标。
                throw new IllegalArgumentException("步骤 " + step.id() + " 的 target 不允许使用输入占位符");
            }
            if (value != null) {
                if (step.action().startsWith("assert") && value.contains("${input.")) {
                    // 断言的期望值必须是确认过的字面量：否则执行期可换成恒真条件。
                    throw new IllegalArgumentException("断言步骤 " + step.id() + " 的期望值不允许使用输入占位符");
                }
                value = substitute(value, safeInputs, usedKeys, "步骤 " + step.id());
            }
            steps.add(new AssistantScriptCompiler.Step(step.id(), step.action(), target, value));
        }
        AssistantScriptCompiler.CaseSpec bound = new AssistantScriptCompiler.CaseSpec(spec.id(), spec.name(),
            spec.startUrl(), steps);
        return new BoundSpec(bound, usedKeys.stream().distinct().sorted().toList(), digest(safeInputs));
    }

    private String substitute(String template, Map<String, String> inputs, List<String> usedKeys, String where) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = inputs.get(key);
            if (replacement == null) {
                throw new IllegalArgumentException(where + " 的占位符 ${input." + key + "} 没有对应输入");
            }
            usedKeys.add(key);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static void validateValues(Map<String, String> inputs) {
        inputs.forEach((key, value) -> {
            if (value == null) {
                throw new IllegalArgumentException("输入 " + key + " 的值不能为 null");
            }
            if (value.length() > MAX_VALUE_LENGTH) {
                throw new IllegalArgumentException("输入 " + key + " 超长（>" + MAX_VALUE_LENGTH + "）");
            }
            if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("输入 " + key + " 含换行，疑似脚本而非普通输入");
            }
            if (SECRET_SHAPED.matcher(value).matches()) {
                throw new IllegalArgumentException("输入 " + key + " 形似密钥，拒绝写入用例结构（请改用 secretRef）");
            }
        });
    }

    public static String digest(Map<String, String> inputs) {
        // 长度前缀编码：{"a":"1\nb=2"} 与 {"a":"1","b":"2"} 不能撞同一个摘要。
        TreeMap<String, String> sorted = new TreeMap<>(inputs);
        StringBuilder builder = new StringBuilder();
        sorted.forEach((key, value) -> builder.append(key.length()).append(':').append(key)
            .append('=').append(value.length()).append(':').append(value).append('\n'));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(builder.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
