package com.haitlify.aitest.platform.assistant.compile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

/**
 * 确定性编译：把「已确认的用例草稿」编译成 Runner 可执行的 payload（计划 §6.5 的版本化交接包）。
 *
 * <p>编译是纯函数：同样输入必得同样输出与同样 digest（受理账本的 input_digest 就是它的 sha256）。
 * 不允许在这里调模型——模型只能产出草稿，草稿经人工确认后才进入编译。
 *
 * <p>白名单式校验：动作类型、目标选择器、允许的 URL 协议；拒绝脚本注入字段（禁止把任意
 * JS/表达式塞进 payload 绕过 Runner 的动作语义）。
 */
@Service
public class AssistantScriptCompiler {

    /**
     * Runner 支持的动作用集合（subset of web-runner 的 SUPPORTED_STEP_ACTIONS）。
     * 契约由 `AssistantScriptCompilerContractTest` 直接读 Runner 源码比对——两侧集合不一致会让草稿
     * 「确认成功、运行必败」，所以必须由测试钉住，不能靠注释声明。
     */
    static final Set<String> ALLOWED_ACTIONS = Set.of(
        "goto", "input", "fill", "click", "select", "hover", "scrollIntoView",
        "assertVisible", "assertHidden", "assertText", "assertUrl", "assertEnabled", "assertDisabled",
        "waitForVisible", "waitForUrl", "waitForText", "waitForHidden", "waitForNetworkIdle");
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    // 反斜杠会被 WHATWG 当斜杠（可混淆真实主机）；userinfo（user@host）同样会造成「看到的主机 ≠ 访问的主机」。
    private static final Pattern ALLOWED_URL = Pattern.compile("^https?://[^\\s\"'<>\\\\@]+$");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\u0000-\\u001f]");

    /**
     * 执行目标也要过与探索同一套网络策略：否则「探索被拒的内网地址」可以直接写成用例去跑。
     * 测试夹具用 127.0.0.1，测试 profile 显式打开。
     */
    private final boolean allowPrivateHosts;

    public record Step(String id, String action, String target, String value) {}

    public record CaseSpec(String id, String name, String startUrl, List<Step> steps) {}

    public record CompiledScript(String payloadJson, String digest, int stepCount) {}

    private final ObjectMapper objectMapper;

    public AssistantScriptCompiler(ObjectMapper objectMapper,
                                   @Value("${app.assistant.network.allow-private-hosts:false}")
                                   boolean allowPrivateHosts) {
        this.objectMapper = objectMapper;
        this.allowPrivateHosts = allowPrivateHosts;
    }

    public CompiledScript compile(CaseSpec spec) {
        validate(spec);
        Map<String, Object> testCase = new LinkedHashMap<>();
        testCase.put("id", spec.id());
        testCase.put("name", spec.name());
        testCase.put("browser", "chromium");
        testCase.put("authenticationMode", "none");
        testCase.put("startUrl", spec.startUrl());
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Step step : spec.steps()) {
            Map<String, Object> compiled = new LinkedHashMap<>();
            compiled.put("id", step.id());
            compiled.put("action", step.action());
            compiled.put("targetType", "locator");
            compiled.put("target", step.target());
            if (step.value() != null) {
                compiled.put("value", step.value());
            }
            steps.add(compiled);
        }
        testCase.put("steps", steps);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", "sequential");
        payload.put("browserOverride", "chromium");
        payload.put("cases", List.of(testCase));
        String json = objectMapper.writeValueAsString(payload);
        return new CompiledScript(json, sha256(json), steps.size());
    }

    private void validate(CaseSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("用例草稿不能为空");
        }
        requireSafeId(spec.id(), "case.id");
        if (spec.name() == null || spec.name().isBlank()) {
            throw new IllegalArgumentException("case.name 不能为空");
        }
        if (spec.startUrl() == null || !ALLOWED_URL.matcher(spec.startUrl()).matches()) {
            throw new IllegalArgumentException("startUrl 必须是 http(s) URL：" + spec.startUrl());
        }
        requireAllowedHost(spec.startUrl(), "startUrl");
        if (spec.steps() == null || spec.steps().isEmpty()) {
            throw new IllegalArgumentException("用例至少需要一个步骤");
        }

        Set<String> seen = new java.util.HashSet<>();
        for (Step step : spec.steps()) {
            requireSafeId(step.id(), "step.id");
            if (!seen.add(step.id())) {
                throw new IllegalArgumentException("步骤 id 重复：" + step.id());
            }
            if (step.action() == null || !ALLOWED_ACTIONS.contains(step.action())) {
                throw new IllegalArgumentException("不支持的动作：" + step.action() + "（白名单：" + ALLOWED_ACTIONS + "）");
            }
            if (step.target() == null || step.target().isBlank()) {
                throw new IllegalArgumentException("步骤 " + step.id() + " 缺少 target");
            }
            rejectUnresolvedPlaceholder(step.value(), "步骤 " + step.id() + " 的 value");
            rejectUnresolvedPlaceholder(step.target(), "步骤 " + step.id() + " 的 target");
            if ("waitForText".equals(step.action()) && (step.value() == null || step.value().isBlank())) {
                throw new IllegalArgumentException("waitForText 必须给出期望文本（步骤 " + step.id() + "）");
            }
            if ("goto".equals(step.action())) {
                // goto 只认一个目标字段：编译与 Runner 必须看同一字段（Runner 读 target）。
                // value 非空会让「校验 value、执行 target」产生双字段绕过，因此直接拒绝。
                if (step.value() != null) {
                    throw new IllegalArgumentException("goto 不允许同时提供 value（避免校验与执行字段不一致）：" + step.id());
                }
                String target = step.target();
                boolean absolute = ALLOWED_URL.matcher(target).matches();
                // 反斜杠会被 WHATWG URL 当成斜杠（"/\\evil.com" → http://evil.com/），相对路径分支必须排除。
                boolean relative = target.startsWith("/") && !target.contains("://")
                    && !target.startsWith("//") && !target.contains("\\");
                if (!absolute && !relative) {
                    throw new IllegalArgumentException(
                        "goto 目标必须是 http(s) URL 或以单个 / 开头的相对路径：" + target);
                }
                if (absolute) {
                    requireAllowedHost(target, "步骤 " + step.id() + " 的 goto");
                }
            }
        }
        // 计划 §6.5：脚本必须至少有一个业务断言；只有导航/等待的用例不能算「通过」。
        // 放在逐步骤校验之后，单步格式错误优先暴露，不掩盖真正的错因。
        if (spec.steps().stream().noneMatch(step -> step.action() != null && step.action().startsWith("assert"))) {
            throw new IllegalArgumentException("用例至少需要一个断言步骤（assert*）；没有断言的脚本不能作为验收依据");
        }
    }

    /** 与 AssistantExplorationService 同一套判据：回环/私网/元数据地址一律拒绝。 */
    private void requireAllowedHost(String url, String field) {
        if (allowPrivateHosts) {
            return;
        }
        String host;
        try {
            host = java.net.URI.create(url).getHost();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(field + " 不是可解析的 URL：" + url);
        }
        if (host == null) {
            throw new IllegalArgumentException(field + " 缺少主机：" + url);
        }
        String lower = host.toLowerCase(java.util.Locale.ROOT).replaceAll("^\\[|\\]$", "");
        while (lower.endsWith(".")) {
            lower = lower.substring(0, lower.length() - 1);
        }
        if (lower.contains(":") && lower.lastIndexOf(':') < lower.length() - 1) {
            String tail = lower.substring(lower.lastIndexOf(':') + 1);
            if (tail.matches("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")) {
                lower = tail;
            }
        }
        boolean blocked = lower.matches("^[0-9]+$") || lower.matches("^0x[0-9a-f]+$")
            || lower.matches("^0[0-7]+(\\.[0-7]+)*$") || lower.matches("^(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}$")
            || lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".local")
            || lower.endsWith(".internal") || lower.endsWith(".nip.io") || lower.endsWith(".sslip.io")
            || lower.equals("::1") || lower.equals("::") || lower.equals("0.0.0.0")
            || lower.matches("^127\\..*") || lower.matches("^10\\..*")
            || lower.matches("^192\\.168\\..*") || lower.matches("^169\\.254\\..*")
            || lower.matches("^172\\.(1[6-9]|2[0-9]|3[01])\\..*");
        if (blocked) {
            throw new IllegalArgumentException(field + " 指向本机/私网地址，拒绝编译：" + host);
        }
    }

    /** 编译期还不允许残留占位符：绑定必须在编译之前完成，否则会把字面量 `${input.x}` 打进页面。 */
    private static void rejectUnresolvedPlaceholder(String value, String field) {
        if (value != null && value.contains("${input.")) {
            throw new IllegalArgumentException(field + " 仍有未绑定的输入占位符（先做输入绑定再编译）");
        }
        if (value != null && CONTROL_CHARS.matcher(value).find()) {
            throw new IllegalArgumentException(field + " 含控制字符");
        }
    }

    private static void requireSafeId(String value, String field) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " 只能包含字母数字与 ._-（≤64）：" + value);
        }
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
