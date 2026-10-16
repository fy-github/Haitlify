package com.haitlify.aitest.platform.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;

import org.junit.jupiter.api.Test;

import com.haitlify.aitest.platform.ai.gateway.AiGateway;
import com.haitlify.aitest.platform.ai.gateway.AiGenerationResult;
import com.haitlify.aitest.platform.ai.gateway.ResolvedAiGatewaySettings;

import tools.jackson.databind.ObjectMapper;

/**
 * T15 发布开关：按任务类型/项目灰度启用统一 Runtime——被禁范围在发出任何请求前阻断，
 * 绝不做旁路直连或并行发送双份真实付费请求；空配置 = 全部启用。
 */
class AiRuntimeSwitchTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AiTaskExecutionFacade facade(String disabledTaskTypes, String disabledProjects,
                                         AiGateway gateway, AiAttemptRepository attempts) {
        com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService settingsService =
            mock(com.haitlify.aitest.platform.ai.gateway.AiGatewaySettingsService.class);
        when(settingsService.loadDefaultTextSettings()).thenReturn(
            new ResolvedAiGatewaySettings("openai", "http://localhost/", "m", "", 60_000));
        AiExecutionLedger ledger = mock(AiExecutionLedger.class);
        when(ledger.remainingBudgetMs(any(String.class), anyLong(), any(Clock.class))).thenReturn(60_000L);
        when(attempts.begin(any(), any(), any(), any(), any(), any())).thenReturn("attempt-1");
        return new AiTaskExecutionFacade(settingsService, gateway, ledger, attempts, null, 0, 0,
            disabledTaskTypes, disabledProjects);
    }

    @Test
    void aDisabledTaskTypeIsBlockedHonestlyBeforeAnyRequest() {
        AiGateway gateway = mock(AiGateway.class);
        AiAttemptRepository attempts = mock(AiAttemptRepository.class);
        AiTaskExecutionFacade facade = facade("bug_analysis", "", gateway, attempts);

        AiGenerationResult result = facade.generate(new AiTaskExecutionFacade.TaskExecution(
            "bug_analysis", 1L, "tester", "run-1", objectMapper.readTree("{\"x\":1}"), null));
        assertThat(result.success()).isFalse();
        assertThat(result.retryReason()).isEqualTo("runtime_disabled");
        assertThat(result.errorMessage()).contains("runtime_disabled_task_type");
        verify(gateway, never()).structuredGenerate(any(), any());
    }

    @Test
    void aDisabledProjectIsBlockedWhileOtherProjectsStillRun() {
        AiGateway gateway = mock(AiGateway.class);
        when(gateway.structuredGenerate(any(), any())).thenReturn(
            new AiGenerationResult(true, "{}", "req", 1, 1, null, false, "", 0));
        AiAttemptRepository attempts = mock(AiAttemptRepository.class);
        AiTaskExecutionFacade facade = facade("", "7", gateway, attempts);

        AiGenerationResult disabled = facade.generate(new AiTaskExecutionFacade.TaskExecution(
            "bug_analysis", 7L, "tester", "run-7", objectMapper.readTree("{\"x\":1}"), null));
        assertThat(disabled.retryReason()).isEqualTo("runtime_disabled");
        verify(gateway, never()).structuredGenerate(any(), any());

        AiGenerationResult enabled = facade.generate(new AiTaskExecutionFacade.TaskExecution(
            "bug_analysis", 8L, "tester", "run-8", objectMapper.readTree("{\"x\":1}"), null));
        assertThat(enabled.success()).isTrue();
        verify(gateway).structuredGenerate(any(), any());
    }

    @Test
    void anEmptySwitchEnablesEverything() {
        AiGateway gateway = mock(AiGateway.class);
        when(gateway.structuredGenerate(any(), any())).thenReturn(
            new AiGenerationResult(true, "{}", "req", 1, 1, null, false, "", 0));
        AiAttemptRepository attempts = mock(AiAttemptRepository.class);
        AiTaskExecutionFacade facade = facade("", "", gateway, attempts);

        AiGenerationResult result = facade.generate(new AiTaskExecutionFacade.TaskExecution(
            "bug_analysis", 1L, "tester", "run-1", objectMapper.readTree("{\"x\":1}"), null));
        assertThat(result.success()).isTrue();
    }
}
