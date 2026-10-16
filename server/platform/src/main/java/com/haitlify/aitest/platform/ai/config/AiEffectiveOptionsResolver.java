package com.haitlify.aitest.platform.ai.config;

import org.springframework.stereotype.Service;

/**
 * T01：参数与超时的溯源解析器——每个最终值都携带 {@code {value, source, sourceVersion}}。
 *
 * <p>解析次序（主计划 §124）：平台安全上限约束所有来源；显式任务覆盖 &gt; 所选模型档案 &gt;
 * 档案参数 &gt; 协议默认（省略）。{@code null} 表示继承（无该来源）；{@code 0/false} 是真实值，
 * 绝不用 {@code value || default} 让其消失。
 */
@Service
public class AiEffectiveOptionsResolver {

    public record EffectiveOption<T>(T value, String source, String sourceVersion, boolean present) {

        static <T> EffectiveOption<T> of(T value, String source, String sourceVersion) {
            return new EffectiveOption<>(value, source, sourceVersion, value != null);
        }

        static <T> EffectiveOption<T> omitted(String sourceVersion) {
            return new EffectiveOption<>(null, SOURCE_OMITTED, sourceVersion, false);
        }

        public T orElse(T fallback) {
            return present ? value : fallback;
        }
    }

    public static final String SOURCE_PLATFORM_LIMIT = "platform_limit";
    public static final String SOURCE_TASK_OVERRIDE = "task_override";
    public static final String SOURCE_CONNECTION_DEFAULT = "connection_default";
    public static final String SOURCE_PROFILE = "profile";
    public static final String SOURCE_RUN_SNAPSHOT = "run_snapshot";
    public static final String SOURCE_OMITTED = "omitted";

    /**
     * 单次超时解析：显式覆盖（EXPLICIT）优先；null=继承连接默认；0=显式无限（仍受平台上限与 run 预算约束）。
     * 平台安全上限 min() 收紧任何来源（含显式 0）——上限命中时 source=platform_limit。
     */
    public EffectiveOption<Integer> resolveTimeout(Integer explicitOverrideMs, String overrideVersion,
                                                   int connectionDefaultMs, String connectionVersion,
                                                   Integer platformLimitMs, String limitVersion) {
        EffectiveOption<Integer> chosen;
        if (explicitOverrideMs != null) {
            chosen = EffectiveOption.of(explicitOverrideMs, SOURCE_TASK_OVERRIDE, overrideVersion);
        } else {
            chosen = EffectiveOption.of(connectionDefaultMs, SOURCE_CONNECTION_DEFAULT, connectionVersion);
        }
        if (platformLimitMs == null) {
            return chosen;
        }
        // 0 表示无限：平台上限同样收紧；正数取 min。
        int effective = chosen.value() == 0 ? platformLimitMs : Math.min(chosen.value(), platformLimitMs);
        if (effective == chosen.value() && !(chosen.value() == 0)) {
            return chosen;
        }
        return EffectiveOption.of(effective, SOURCE_PLATFORM_LIMIT, limitVersion);
    }

    /** 参数解析：显式值（含 false/0）> 档案参数；两者皆无 ⇒ OMITTED（wire 上缺席）。 */
    public <T> EffectiveOption<T> resolveParameter(T explicitValue, String explicitSource, String explicitVersion,
                                                   T profileValue, String profileVersion) {
        if (explicitValue != null) {
            return EffectiveOption.of(explicitValue, explicitSource, explicitVersion);
        }
        if (profileValue != null) {
            return EffectiveOption.of(profileValue, SOURCE_PROFILE, profileVersion);
        }
        return EffectiveOption.omitted(profileVersion);
    }

    /** 运行固定档案版本：run 记录的 rev 优先于当前最新（历史快照不可被新版本覆盖）。 */
    public EffectiveOption<Integer> resolveProfileVersion(Integer runPinnedVersion, String runVersion,
                                                          int latestVersion, String latestSource) {
        if (runPinnedVersion != null) {
            return EffectiveOption.of(runPinnedVersion, SOURCE_RUN_SNAPSHOT, runVersion);
        }
        return EffectiveOption.of(latestVersion, latestSource, latestSource);
    }
}
