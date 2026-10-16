package com.haitlify.aitest.platform.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * T01：溯源解析器——主计划 §124 的四个核心示例 + null 继承/0 与 false 保留/平台上限收紧。
 */
class AiEffectiveOptionsResolverTest {

    private final AiEffectiveOptionsResolver resolver = new AiEffectiveOptionsResolver();

    @Test
    void explicitOverrideWinsOverConnectionDefault() {
        var option = resolver.resolveTimeout(120000, "policy@v1", 300000, "connection@v1", null, null);
        assertThat(option.value()).isEqualTo(120000);
        assertThat(option.source()).isEqualTo(AiEffectiveOptionsResolver.SOURCE_TASK_OVERRIDE);
        assertThat(option.sourceVersion()).isEqualTo("policy@v1");
    }

    @Test
    void nullInheritsConnectionDefault() {
        var option = resolver.resolveTimeout(null, null, 300000, "connection@v1", null, null);
        assertThat(option.value()).isEqualTo(300000);
        assertThat(option.source()).isEqualTo(AiEffectiveOptionsResolver.SOURCE_CONNECTION_DEFAULT);
        assertThat(option.sourceVersion()).isEqualTo("connection@v1");
    }

    @Test
    void zeroIsAnExplicitValueNeverDropped() {
        var option = resolver.resolveTimeout(0, "policy@v2", 300000, "connection@v1", null, null);
        assertThat(option.value()).isZero();
        assertThat(option.present()).isTrue();
        assertThat(option.source()).isEqualTo(AiEffectiveOptionsResolver.SOURCE_TASK_OVERRIDE);
    }

    @Test
    void platformLimitTightensEverySourceIncludingZero() {
        var clamped = resolver.resolveTimeout(120000, "policy@v1", 300000, "connection@v1", 100000, "platform@v1");
        assertThat(clamped.value()).isEqualTo(100000);
        assertThat(clamped.source()).isEqualTo(AiEffectiveOptionsResolver.SOURCE_PLATFORM_LIMIT);
        assertThat(clamped.sourceVersion()).isEqualTo("platform@v1");

        var notClamped = resolver.resolveTimeout(80000, "policy@v1", 300000, "connection@v1", 100000, "platform@v1");
        assertThat(notClamped.value()).isEqualTo(80000);
        assertThat(notClamped.source()).isEqualTo(AiEffectiveOptionsResolver.SOURCE_TASK_OVERRIDE);

        var unlimitedClamped = resolver.resolveTimeout(0, "policy@v1", 300000, "connection@v1", 100000, "platform@v1");
        assertThat(unlimitedClamped.value()).isEqualTo(100000);
        assertThat(unlimitedClamped.source()).isEqualTo(AiEffectiveOptionsResolver.SOURCE_PLATFORM_LIMIT);
    }

    @Test
    void falseIsAnExplicitParameterValueNotAMissingOne() {
        var explicitFalse = resolver.resolveParameter(false, AiEffectiveOptionsResolver.SOURCE_TASK_OVERRIDE,
            "task@v1", true, "profile@v1");
        assertThat(explicitFalse.value()).isFalse();
        assertThat(explicitFalse.source()).isEqualTo(AiEffectiveOptionsResolver.SOURCE_TASK_OVERRIDE);

        var profileFalse = resolver.resolveParameter(null, AiEffectiveOptionsResolver.SOURCE_TASK_OVERRIDE,
            "task@v1", false, "profile@v1");
        assertThat(profileFalse.value()).isFalse();
        assertThat(profileFalse.source()).isEqualTo(AiEffectiveOptionsResolver.SOURCE_PROFILE);
    }

    @Test
    void undeclaredParameterIsOmittedAndAbsentOnTheWire() {
        var omitted = resolver.resolveParameter(null, AiEffectiveOptionsResolver.SOURCE_TASK_OVERRIDE,
            "task@v1", null, "profile@v1");
        assertThat(omitted.present()).isFalse();
        assertThat(omitted.value()).isNull();
        assertThat(omitted.source()).isEqualTo(AiEffectiveOptionsResolver.SOURCE_OMITTED);
        assertThat(omitted.orElse("default-when-absent")).isEqualTo("default-when-absent");
    }

    @Test
    void runPinnedProfileVersionBeatsTheLatestVersion() {
        var pinned = resolver.resolveProfileVersion(3, "run@snap-1", 4, "latest");
        assertThat(pinned.value()).isEqualTo(3);
        assertThat(pinned.source()).isEqualTo(AiEffectiveOptionsResolver.SOURCE_RUN_SNAPSHOT);

        var latest = resolver.resolveProfileVersion(null, "run@snap-1", 4, "latest");
        assertThat(latest.value()).isEqualTo(4);
    }
}
