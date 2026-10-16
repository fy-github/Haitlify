package com.haitlify.aitest.platform.ai.agentic;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import org.junit.jupiter.api.Test;

import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.AgentRuleSnapshot;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentMode;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentEventType;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentRunStatus;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentRunResponse;
import com.haitlify.aitest.platform.ai.agentic.WebCaseAgentModels.WebCaseAgentStage;
import com.haitlify.aitest.platform.ai.agentic.memory.AgentMemoryService;
import com.haitlify.aitest.platform.ai.agentic.locator.LocatorKnowledgeService;
import com.haitlify.aitest.platform.ai.draft.AgentWebCaseGenerationFacade;
import com.haitlify.aitest.platform.ai.draft.WebExplorationBlockedException;
import com.haitlify.aitest.platform.ai.quality.WebCaseQualityFeedbackService;
import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService;
import com.haitlify.aitest.platform.webautomation.WebAutomationEvidenceException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

class WebCaseAgentDispatcherTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentWebCaseGenerationFacade mockGeneratorFacade() {
        AgentWebCaseGenerationFacade generator = mock(AgentWebCaseGenerationFacade.class);
        return generator;
    }

    @Test
    void propagatesCapturedActorToWorkerAndRestoresWorkerContext() {
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        doAnswer(invocation -> {
            org.assertj.core.api.Assertions.assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("caller");
            throw new IllegalStateException("stop after context assertion");
        }).when(runService).markRunning("run-context");
        when(runService.isCancelled("run-context")).thenReturn(true);
        SecurityContext callerContext = SecurityContextHolder.createEmptyContext();
        callerContext.setAuthentication(new UsernamePasswordAuthenticationToken("caller", "n/a", List.of()));
        SecurityContext workerContext = SecurityContextHolder.createEmptyContext();
        workerContext.setAuthentication(new UsernamePasswordAuthenticationToken("worker", "n/a", List.of()));
        SecurityContextHolder.setContext(workerContext);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, mock(WebCaseAgentEventService.class), mockGeneratorFacade(), mock(AgentMemoryService.class), mock(LocatorKnowledgeService.class), mock(WebCaseQualityFeedbackService.class), mock(WebAutomationLocatorDiscoveryService.class));

        dispatcher.dispatchWithContext("run-context", null, callerContext);

        verify(runService, timeout(1000)).markRunning("run-context");
        org.assertj.core.api.Assertions.assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("worker");
        SecurityContextHolder.clearContext();
    }

    @Test
    void classifiesAllSemanticCompilationFailuresAsRetryableButExcludesOperationalFailures() {
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(null, null, null, null, null, null, null);
        for (String message : List.of(
            "元素断言未命中自然语言步骤",
            "不能只使用 assertUrl",
            "locator 未由本批浏览器采集：text=用户",
            "断言与预期结果均退化为 DSL",
            "测试目标涉及页面元素/展示/顺序，执行步骤不能只使用 assertUrl"
        )) {
            org.assertj.core.api.Assertions.assertThat(dispatcher.isRetryableSemanticMismatch(message)).as(message).isTrue();
        }
        for (String message : List.of("认证失败", "网络连接超时", "provider unavailable", "任务已取消", "JSON 输出无效")) {
            org.assertj.core.api.Assertions.assertThat(dispatcher.isRetryableSemanticMismatch(message)).as(message).isFalse();
        }
    }

    @Test
    void planModeGeneratesNaturalLanguageArtifactsWithoutCollectingBrowserEvidence() {
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                "point-1", "日志管理 Tab 展示", "日志管理", "日志管理页面", "/project",
                List.of("进入日志管理页面", "查看日志 Tab"), "展示全部日志、告警日志", "验证日志 Tab 展示"
            )),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode())
        );
        when(runService.ruleVersion("run-1")).thenReturn(1);
        when(runService.projectId("run-1")).thenReturn(1L);
        when(runService.requestedPointIds("run-1")).thenReturn(List.of("point-1"));
        when(runService.ruleSnapshot("run-1")).thenReturn(rules);
        when(runService.mode("run-1")).thenReturn(WebCaseAgentMode.PLAN);
        when(runService.get("run-1")).thenReturn(new WebCaseAgentRunResponse(
            "run-1", 1L, "analysis-1", WebCaseAgentRunStatus.RUNNING, WebCaseAgentStage.PREFLIGHT,
            List.of("point-1"), List.of("point-1"), List.of(), 1, null, "2026-08-12T00:00:00Z", ""
        ));
        when(memories.promptContext(1L)).thenReturn(List.of());
        when(generator.plan(any(), any(), any(), any())).thenReturn(plan);

        dispatcher.dispatch("run-1", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-1"), "env-1", "https://app-dev.example.test/",
            "chromium", "1440x900", null, new WebCaseAgentModels.BrowserDiscoveryRequest(
                true, 10_000, "domcontentloaded", null, java.util.Map.of(), List.of(), null
            )
        ));

        verify(runService, timeout(1000)).completePlan("run-1", plan.plans());
        verify(generator).plan(any(), any(), any(), any());
        verify(runService).saveNaturalPlanArtifacts(any(), any(), any());
        verify(generator, never()).resolveEvidence(any(), any(), any(), any(), anyLong());
        verify(generator, never()).compile(any(), any(), any(), any(), any(), any());
        verify(browserDiscovery, never()).discover(any());
    }

    @Test
    void planModeInjectsQualityFeedbackIntoMemoryContext() {
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                "point-1", "日志管理 Tab 展示", "日志管理", "日志管理页面", "/project",
                List.of("进入日志管理页面", "查看日志 Tab"), "展示全部日志、告警日志", "验证日志 Tab 展示"
            )),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode())
        );
        when(runService.ruleVersion("run-1")).thenReturn(1);
        when(runService.projectId("run-1")).thenReturn(1L);
        when(runService.requestedPointIds("run-1")).thenReturn(List.of("point-1"));
        when(runService.ruleSnapshot("run-1")).thenReturn(rules);
        when(runService.mode("run-1")).thenReturn(WebCaseAgentMode.PLAN);
        when(runService.get("run-1")).thenReturn(new WebCaseAgentRunResponse(
            "run-1", 1L, "analysis-1", WebCaseAgentRunStatus.RUNNING, WebCaseAgentStage.PREFLIGHT,
            List.of("point-1"), List.of(), List.of(), 1, null, "2026-08-12T00:00:00Z", ""
        ));
        when(memories.promptContext(1L)).thenReturn(List.of("[project] 既有经验"));
        when(qualityFeedback.feedbackContext(1L, "run-1")).thenReturn(List.of("[quality_signal] 覆盖度偏弱"));
        when(generator.plan(any(), any(), any(), any())).thenReturn(plan);

        dispatcher.dispatch("run-1", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-1"), "env-1", "https://app-dev.example.test/",
            "chromium", "1440x900", null, new WebCaseAgentModels.BrowserDiscoveryRequest(
                true, 10_000, "domcontentloaded", null, java.util.Map.of(), List.of(), null
            )
        ));

        verify(runService, timeout(1000)).completePlan("run-1", plan.plans());
        verify(generator).plan(any(), any(), any(), eq(List.of("[project] 既有经验", "[quality_signal] 覆盖度偏弱")));
    }

    @Test
    void planModeDegradesToNoHintsWhenFeedbackComputationFails() {
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan(
                "point-1", "日志管理 Tab 展示", "日志管理", "日志管理页面", "/project",
                List.of("进入日志管理页面", "查看日志 Tab"), "展示全部日志、告警日志", "验证日志 Tab 展示"
            )),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode())
        );
        when(runService.ruleVersion("run-1")).thenReturn(1);
        when(runService.projectId("run-1")).thenReturn(1L);
        when(runService.requestedPointIds("run-1")).thenReturn(List.of("point-1"));
        when(runService.ruleSnapshot("run-1")).thenReturn(rules);
        when(runService.mode("run-1")).thenReturn(WebCaseAgentMode.PLAN);
        when(runService.get("run-1")).thenReturn(new WebCaseAgentRunResponse(
            "run-1", 1L, "analysis-1", WebCaseAgentRunStatus.RUNNING, WebCaseAgentStage.PREFLIGHT,
            List.of("point-1"), List.of(), List.of(), 1, null, "2026-08-12T00:00:00Z", ""
        ));
        when(memories.promptContext(1L)).thenReturn(List.of("[project] 既有经验"));
        when(qualityFeedback.feedbackContext(1L, "run-1")).thenThrow(new IllegalStateException("db down"));
        when(generator.plan(any(), any(), any(), any())).thenReturn(plan);

        dispatcher.dispatch("run-1", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-1"), "env-1", "https://app-dev.example.test/",
            "chromium", "1440x900", null, new WebCaseAgentModels.BrowserDiscoveryRequest(
                true, 10_000, "domcontentloaded", null, java.util.Map.of(), List.of(), null
            )
        ));

        verify(runService, timeout(1000)).completePlan("run-1", plan.plans());
        verify(generator).plan(any(), any(), any(), eq(List.of("[project] 既有经验")));
    }

    @Test
    void compileModeUsesConfirmedPlanInsteadOfPlanningAgain() {
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-1", "日志管理 Tab 展示", "日志管理", "日志管理页面", "/project", List.of("进入日志管理页面"), "日志 Tab 可见", "验证日志 Tab")),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode())
        );
        AgentWebCaseGenerationFacade.BrowserEvidenceBundle evidence = new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
            objectMapper.createArrayNode(), List.of("known-log-tab"), 1, 1, false, "matched", 1, 80, 10_000L
        );
        List<AgentWebCaseGenerationFacade.GeneratedWebCase> generated = List.of(new AgentWebCaseGenerationFacade.GeneratedWebCase(
            "point-1", "日志管理 Tab 展示", "日志管理", "web_automation", "日志管理页面", "/project", "chromium", "1440x900", "已登录", "验证日志 Tab",
            "assertVisible text=全部日志", "assertVisible text=全部日志", "日志 Tab 可见", "进入日志管理页面", "日志 Tab 可见", "requirements.txt"
        ));

        when(runService.ruleVersion("run-1")).thenReturn(1);
        when(runService.projectId("run-1")).thenReturn(1L);
        when(runService.requestedPointIds("run-1")).thenReturn(List.of("point-1"));
        when(runService.ruleSnapshot("run-1")).thenReturn(rules);
        when(runService.mode("run-1")).thenReturn(WebCaseAgentMode.COMPILE);
        when(runService.planRunId("run-1")).thenReturn("plan-run-1");
        when(runService.confirmedPlanBatch("plan-run-1")).thenReturn(plan);
        when(runService.get("run-1")).thenReturn(new WebCaseAgentRunResponse(
            "run-1", 1L, "analysis-1", WebCaseAgentRunStatus.RUNNING, WebCaseAgentStage.PREFLIGHT,
            List.of("point-1"), List.of("point-1"), List.of(), 1, null, "2026-08-12T00:00:00Z", ""
        ));
        when(runService.artifactCount("run-1")).thenReturn(1);
        when(memories.promptContext(1L)).thenReturn(List.of());
        when(generator.resolveEvidence(any(), any(), any(), any(), anyLong())).thenReturn(evidence);
        when(generator.compile(any(), any(), any(), any(), any(), any())).thenReturn(generated);

        dispatcher.dispatch("run-1", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-1"), "env-1", "https://app-dev.example.test/", "chromium", "1440x900", null, null, WebCaseAgentMode.COMPILE, "plan-run-1"
        ));

        verify(runService, timeout(1000)).succeed("run-1", 1);
        verify(runService).confirmedPlanBatch("plan-run-1");
        verify(generator, never()).plan(any(), any(), any());
        verify(generator).resolveEvidence(eq(List.of("point-1")), any(), any(), any(), anyLong());
        verify(generator).compile(any(), any(), any(), any(), any(), any());
        // 证据闭环:交付的用例必须绑定其生成时命中的页面证据,而非空数组。
        verify(runService, timeout(1000)).saveArtifacts(eq("run-1"), any(), eq(List.of("known-log-tab")));
    }

    @Test
    void compileModeDoesNotPersistArtifactsWhenGeneratedWebCaseOnlyContainsAssertUrlForContentObjective() {
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-invalid", "设备状态展示", "设备", "设备管理", "/devices", List.of("进入设备管理页"), "展示设备状态", "验证设备状态")),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode())
        );
        AgentWebCaseGenerationFacade.BrowserEvidenceBundle evidence = new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
            objectMapper.createArrayNode(), List.of("device-status"), 1, 1, false, "matched", 1, 70, 10_000L
        );
        when(runService.ruleVersion("run-invalid")).thenReturn(1);
        when(runService.projectId("run-invalid")).thenReturn(1L);
        when(runService.requestedPointIds("run-invalid")).thenReturn(List.of("point-invalid"));
        when(runService.ruleSnapshot("run-invalid")).thenReturn(rules);
        when(runService.mode("run-invalid")).thenReturn(WebCaseAgentMode.COMPILE);
        when(runService.planRunId("run-invalid")).thenReturn("plan-run-invalid");
        when(runService.confirmedPlanBatch("plan-run-invalid")).thenReturn(plan);
        when(runService.artifactCount("run-invalid")).thenReturn(0);
        when(runService.get("run-invalid")).thenReturn(new WebCaseAgentRunResponse(
            "run-invalid", 1L, "analysis-1", WebCaseAgentRunStatus.RUNNING, WebCaseAgentStage.PREFLIGHT,
            List.of("point-invalid"), List.of(), List.of(), 1, null, "2026-08-12T00:00:00Z", ""
        ));
        when(memories.promptContext(1L)).thenReturn(List.of());
        when(generator.resolveEvidence(any(), any(), any(), any(), anyLong())).thenReturn(evidence);
        when(generator.compile(any(), any(), any(), any(), any(), any()))
            .thenThrow(new IllegalArgumentException("测试目标涉及页面元素/展示/状态，执行步骤不能只使用 assertUrl"));

        dispatcher.dispatch("run-invalid", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-invalid"), "env-1", "https://test.example.com", "chromium", "1440x900", null, null, WebCaseAgentMode.COMPILE, "plan-run-invalid"
        ));

        verify(runService, timeout(1000)).markPointBlocked("run-invalid", "point-invalid");
        verify(runService, never()).saveArtifacts(anyString(), any(), any());
        verify(runService, timeout(1000)).block("run-invalid", "所有测试点都未产出可执行 Web 用例");
    }

    @Test
    void forwardsTheConfiguredNetworkPolicyModeToTheDiscoveryOptions() {
        // 用户要求的「黑名单（默认）/白名单」模式必须从请求一路带到取证选项，否则 runner 永远按白名单跑。
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-mode", "首页概览", "首页", "首页", "/", List.of("进入首页"), "概览可见", "验证概览")),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode())
        );
        AgentWebCaseGenerationFacade.BrowserEvidenceBundle evidence = new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
            objectMapper.createArrayNode(), List.of("point-mode"), 1, 1, false, "matched", 1, 70, 10_000L
        );
        when(runService.ruleVersion("run-mode")).thenReturn(1);
        when(runService.projectId("run-mode")).thenReturn(1L);
        when(runService.requestedPointIds("run-mode")).thenReturn(List.of("point-mode"));
        when(runService.ruleSnapshot("run-mode")).thenReturn(rules);
        when(runService.mode("run-mode")).thenReturn(WebCaseAgentMode.COMPILE);
        when(runService.planRunId("run-mode")).thenReturn("plan-run-mode");
        when(runService.confirmedPlanBatch("plan-run-mode")).thenReturn(plan);
        when(runService.artifactCount("run-mode")).thenReturn(0);
        when(runService.get("run-mode")).thenReturn(new WebCaseAgentRunResponse(
            "run-mode", 1L, "analysis-1", WebCaseAgentRunStatus.RUNNING, WebCaseAgentStage.PREFLIGHT,
            List.of("point-mode"), List.of(), List.of(), 1, null, "2026-08-12T00:00:00Z", ""
        ));
        when(memories.promptContext(1L)).thenReturn(List.of());
        when(generator.resolveEvidence(any(), any(), any(), any(), anyLong())).thenReturn(evidence);

        var discovery = new WebCaseAgentModels.BrowserDiscoveryRequest(
            true, 10_000, "domcontentloaded", null, null, null,
            List.of("https://api.example.com"), null, true, null, null, null, "", "blocklist"
        );
        dispatcher.dispatch("run-mode", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-mode"), "env-1", "https://app.example.com", "chromium", "1440x900", null, discovery, WebCaseAgentMode.COMPILE, "plan-run-mode"
        ));

        ArgumentCaptor<AgentWebCaseGenerationFacade.BrowserDiscoveryOptions> options = ArgumentCaptor.forClass(AgentWebCaseGenerationFacade.BrowserDiscoveryOptions.class);
        verify(generator, timeout(1000)).resolveEvidence(any(), any(), options.capture(), any(), anyLong());
        org.assertj.core.api.Assertions.assertThat(options.getValue().networkPolicyMode()).isEqualTo("blocklist");
    }

    @Test
    void defaultsToHeadlessWhenBrowserDiscoveryOmitsTheFlag() {
        // 实测 2026-10-14：API 调用方传了 browserDiscovery 但省略 headless 时，旧映射把 null 当 false 下发
        // 有头模式，容器里的 Chromium 直接启动失败（browserType.launch: Target page ... has been closed）。
        // 未显式关闭就必须默认无头；前端显式传值的行为不变。
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-mode", "首页概览", "首页", "首页", "/", List.of("进入首页"), "概览可见", "验证概览")),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode())
        );
        AgentWebCaseGenerationFacade.BrowserEvidenceBundle evidence = new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
            objectMapper.createArrayNode(), List.of("point-mode"), 1, 1, false, "matched", 1, 70, 10_000L
        );
        when(runService.ruleVersion("run-headless")).thenReturn(1);
        when(runService.projectId("run-headless")).thenReturn(1L);
        when(runService.requestedPointIds("run-headless")).thenReturn(List.of("point-mode"));
        when(runService.ruleSnapshot("run-headless")).thenReturn(rules);
        when(runService.mode("run-headless")).thenReturn(WebCaseAgentMode.COMPILE);
        when(runService.planRunId("run-headless")).thenReturn("plan-run-headless");
        when(runService.confirmedPlanBatch("plan-run-headless")).thenReturn(plan);
        when(runService.artifactCount("run-headless")).thenReturn(0);
        when(runService.get("run-headless")).thenReturn(new WebCaseAgentRunResponse(
            "run-headless", 1L, "analysis-1", WebCaseAgentRunStatus.RUNNING, WebCaseAgentStage.PREFLIGHT,
            List.of("point-mode"), List.of(), List.of(), 1, null, "2026-08-12T00:00:00Z", ""
        ));
        when(memories.promptContext(1L)).thenReturn(List.of());
        when(generator.resolveEvidence(any(), any(), any(), any(), anyLong())).thenReturn(evidence);

        var discovery = new WebCaseAgentModels.BrowserDiscoveryRequest(
            null, 10_000, "domcontentloaded", null, null, null,
            List.of("https://api.example.com"), null, true, null, null, null, "", "blocklist"
        );
        dispatcher.dispatch("run-headless", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-mode"), "env-1", "https://app.example.com", "chromium", "1440x900", null, discovery, WebCaseAgentMode.COMPILE, "plan-run-headless"
        ));

        ArgumentCaptor<AgentWebCaseGenerationFacade.BrowserDiscoveryOptions> options = ArgumentCaptor.forClass(AgentWebCaseGenerationFacade.BrowserDiscoveryOptions.class);
        verify(generator, timeout(1000)).resolveEvidence(any(), any(), options.capture(), any(), anyLong());
        org.assertj.core.api.Assertions.assertThat(options.getValue().headless()).isTrue();
    }

    @Test
    void evidenceCaptchaFailureNamesBlockedOriginsAndWhereToAllowThem() {
        // 用户实测：界面只显示「验证码拦截」，真因（登录接口/验证码脚本被网络白名单 abort）
        // 只藏在 details_json 里。失败信息必须点名被拦来源并给出放行建议。
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-blocked", "首页概览", "首页", "首页", "/", List.of("进入首页"), "概览可见", "验证概览")),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode())
        );
        var details = objectMapper.createObjectNode();
        var network = details.putObject("network");
        network.putArray("unknownBlocked").addObject()
            .put("origin", "https://api.app-dev.example.test").put("path", "/v4/uc/n/auth/login").put("resourceType", "xhr");
        network.putArray("optionalBlocked").addObject()
            .put("origin", "https://o.alicdn.com").put("path", "/captcha-frontend/aliyunCaptcha/AliyunCaptcha.js").put("resourceType", "script");
        details.put("retryable", false);

        when(runService.ruleVersion("run-blocked")).thenReturn(1);
        when(runService.projectId("run-blocked")).thenReturn(1L);
        when(runService.requestedPointIds("run-blocked")).thenReturn(List.of("point-blocked"));
        when(runService.ruleSnapshot("run-blocked")).thenReturn(rules);
        when(runService.mode("run-blocked")).thenReturn(WebCaseAgentMode.COMPILE);
        when(runService.planRunId("run-blocked")).thenReturn("plan-run-blocked");
        when(runService.confirmedPlanBatch("plan-run-blocked")).thenReturn(plan);
        when(runService.artifactCount("run-blocked")).thenReturn(0);
        when(runService.get("run-blocked")).thenReturn(new WebCaseAgentRunResponse(
            "run-blocked", 1L, "analysis-1", WebCaseAgentRunStatus.RUNNING, WebCaseAgentStage.PREFLIGHT,
            List.of("point-blocked"), List.of(), List.of(), 1, null, "2026-08-12T00:00:00Z", ""
        ));
        when(memories.promptContext(1L)).thenReturn(List.of());
        when(generator.resolveEvidence(any(), any(), any(), any(), anyLong()))
            .thenThrow(new WebAutomationEvidenceException(503, "captcha required", "captcha_required", details));

        dispatcher.dispatch("run-blocked", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-blocked"), "env-1", "https://app-dev.example.test", "chromium", "1440x900", null, null, WebCaseAgentMode.COMPILE, "plan-run-blocked"
        ));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(events, timeout(1000)).appendDetailed(eq("run-blocked"), eq(WebCaseAgentStage.EVIDENCE),
            eq(WebCaseAgentEventType.ERROR), eq("error"), message.capture(), eq("point-blocked"), eq("browser-discovery"),
            eq(0L), eq(1), any(), eq("captcha_required"), any());
        org.assertj.core.api.Assertions.assertThat(message.getValue())
            .contains("被网络白名单拦截")
            .contains("https://api.app-dev.example.test/v4/uc/n/auth/login")
            .contains("https://o.alicdn.com/captcha-frontend/aliyunCaptcha/AliyunCaptcha.js")
            .contains("认证与放行白名单");
        verify(runService, timeout(1000)).markPointBlocked("run-blocked", "point-blocked");
    }

    @Test
    void browserEvidenceCapturePersistsExplorationKnowledgeMetrics() {
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-knowledge", "日志查询", "日志管理", "日志页面", "/logs", List.of("进入日志页面"), "查询按钮可见", "验证查询按钮")),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode())
        );
        var elements = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
            .put("id", "query")
            .put("locator", "button=查询")
            .put("label", "查询")
            .put("role", "button")
            .put("tagName", "button")
            .put("strategy", "role"));
        var pageOneExploration = objectMapper.createObjectNode()
            .put("status", "matched")
            .set("navigationTrail", objectMapper.createArrayNode().add("https://test.example.com/#/logs").add("click button=查询"))
            .set("rounds", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("round", 1)
                    .put("pageUrl", "https://test.example.com/#/logs?access_token=secret")
                    .put("fingerprint", "fp-1")
                    .put("actionType", "click")
                    .put("actionTarget", "button=查询")
                    .set("action", objectMapper.createObjectNode().put("type", "click").put("target", "button=查询").put("locator", "button=查询").put("token", "secret"))
                    .set("scores", objectMapper.createObjectNode().put("pageScore", 35).put("elementScore", 25).put("stateScore", 10).put("totalScore", 70))
                    .set("observation", objectMapper.createObjectNode().put("locator", "button=查询").put("text", "页面内部机密"))
                    .set("actionFailure", objectMapper.createObjectNode().put("failureReason", "unsafe_overlay").put("failureType", "unsafe_overlay").put("retryable", false).put("message", "原始长错误不应落库")))
                .add(objectMapper.createObjectNode().put("round", 2).put("pageUrl", "https://test.example.com/#/logs#results")
                    .set("scores", objectMapper.createObjectNode().put("pageScore", 35).put("elementScore", 25).put("stateScore", 10).put("totalScore", 70))))
            .set("scores", objectMapper.createObjectNode().put("totalScore", 88));
        var pageTwoExploration = objectMapper.createObjectNode()
            .put("status", "partial")
            .set("navigationTrail", objectMapper.createArrayNode().add("https://test.example.com/#/alerts").add("click text=告警"))
            .set("rounds", objectMapper.createArrayNode().add(objectMapper.createObjectNode()))
            .set("scores", objectMapper.createObjectNode().put("totalScore", 42));
        var rawEvidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
            .put("source", "browserDiscovery")
            .put("sourcePointId", "point-knowledge")
            .put("moduleName", "日志管理")
            .put("pageName", "日志页面")
            .put("pageUrl", "https://test.example.com/#/logs")
            .set("historicalMatchedIds", objectMapper.createArrayNode().add("known-query"))
            .set("historicalRejectedIds", objectMapper.createArrayNode().add("stale-alert"))
            .set("elements", elements)
            .set("exploration", pageOneExploration))
            .add(objectMapper.createObjectNode()
                .put("source", "browserDiscovery")
                .put("sourcePointId", "point-knowledge")
                .put("moduleName", "告警")
                .put("pageName", "告警页面")
                .put("pageUrl", "https://test.example.com/#/alerts")
                .set("elements", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                    .put("id", "alert")
                    .put("locator", "text=告警")
                    .put("label", "告警")
                    .put("role", "link")
                    .put("tagName", "a")
                    .put("strategy", "text")))
                .set("exploration", pageTwoExploration));
        var explorationReport = objectMapper.createObjectNode().put("status", "global-should-not-be-used");
        AgentWebCaseGenerationFacade.BrowserEvidenceBundle evidence = new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
            rawEvidence, List.of(), 2, 2, true, "matched", 2, 88, 10_000L, explorationReport
        );
        List<AgentWebCaseGenerationFacade.GeneratedWebCase> generated = List.of(new AgentWebCaseGenerationFacade.GeneratedWebCase(
            "point-knowledge", "日志查询", "日志管理", "web_automation", "日志页面", "/logs", "chromium", "1440x900", "已登录", "验证查询按钮",
            "goto /logs\nassertVisible button=查询", "assertVisible button=查询", "查询按钮可见", "进入日志页面", "查询按钮可见", "requirements.txt"
        ));

        when(runService.ruleVersion("run-knowledge")).thenReturn(1);
        when(runService.projectId("run-knowledge")).thenReturn(1L);
        when(runService.requestedPointIds("run-knowledge")).thenReturn(List.of("point-knowledge"));
        when(runService.ruleSnapshot("run-knowledge")).thenReturn(rules);
        when(runService.mode("run-knowledge")).thenReturn(WebCaseAgentMode.COMPILE);
        when(runService.planRunId("run-knowledge")).thenReturn("plan-run-knowledge");
        when(runService.confirmedPlanBatch("plan-run-knowledge")).thenReturn(plan);
        when(runService.artifactCount("run-knowledge")).thenReturn(0);
        when(runService.get("run-knowledge")).thenReturn(new WebCaseAgentRunResponse(
            "run-knowledge", 1L, "analysis-1", WebCaseAgentRunStatus.RUNNING, WebCaseAgentStage.PREFLIGHT,
            List.of("point-knowledge"), List.of(), List.of(), 1, null, "2026-08-12T00:00:00Z", ""
        ));
        when(memories.promptContext(1L)).thenReturn(List.of());
        when(generator.resolveEvidence(any(), any(), any(), any(), anyLong())).thenReturn(evidence);
        when(generator.compile(any(), any(), any(), any(), any(), any())).thenReturn(generated);

        dispatcher.dispatch("run-knowledge", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-knowledge"), "env-1", "https://test.example.com", "chromium", "1440x900", null, null, WebCaseAgentMode.COMPILE, "plan-run-knowledge"
        ));

        ArgumentCaptor<LocatorKnowledgeService.ExplorationKnowledge> knowledge = ArgumentCaptor.forClass(LocatorKnowledgeService.ExplorationKnowledge.class);
        verify(locatorKnowledge, timeout(1000).times(2)).recordAgentEvidence(
            eq(1L), eq("env-1"), anyString(), anyString(), anyString(), anyList(), knowledge.capture()
        );
        org.assertj.core.api.Assertions.assertThat(knowledge.getAllValues().get(0).navigationTrail())
            .containsExactly("https://test.example.com/#/logs", "click button=查询");
        org.assertj.core.api.Assertions.assertThat(knowledge.getAllValues().get(0).totalScore()).isEqualTo(88);
        org.assertj.core.api.Assertions.assertThat(knowledge.getAllValues().get(0).rounds()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(knowledge.getAllValues().get(0).status()).isEqualTo("matched");
        org.assertj.core.api.Assertions.assertThat(knowledge.getAllValues().get(1).navigationTrail())
            .containsExactly("https://test.example.com/#/alerts", "click text=告警");
        org.assertj.core.api.Assertions.assertThat(knowledge.getAllValues().get(1).totalScore()).isEqualTo(42);
        org.assertj.core.api.Assertions.assertThat(knowledge.getAllValues().get(1).rounds()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(knowledge.getAllValues().get(1).status()).isEqualTo("partial");

        ArgumentCaptor<JsonNode> details = ArgumentCaptor.forClass(JsonNode.class);
        // 同型竞态加固：先按内容等到 round=1 且携 historicalCandidateCount 的明细事件，再捕获断言。
        verify(events, timeout(5000)).appendDetailed(anyString(), any(), any(), anyString(), anyString(), any(), any(), anyLong(), any(), any(), any(),
            org.mockito.ArgumentMatchers.argThat(item -> item != null && item.path("round").asInt() == 1
                && item.path("metrics").path("historicalCandidateCount").asInt() == 2));
        verify(events, timeout(1000).atLeast(3)).appendDetailed(anyString(), any(), any(), anyString(), anyString(), any(), any(), anyLong(), any(), any(), any(), details.capture());
        JsonNode first = details.getAllValues().stream().filter(item -> item.path("round").asInt() == 1).findFirst().orElseThrow();
        JsonNode metrics = details.getAllValues().stream()
            .map(item -> item.path("metrics"))
            .filter(item -> item.path("historicalCandidateCount").asInt() == 2)
            .findFirst()
            .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(first.path("pageUrl").asText()).isEqualTo("https://test.example.com/#/logs");
        org.assertj.core.api.Assertions.assertThat(first.path("pageUrl").asText()).doesNotContain("?", "access_token", "secret");
        org.assertj.core.api.Assertions.assertThat(first.path("pageScore").asInt()).isEqualTo(35);
        org.assertj.core.api.Assertions.assertThat(first.path("totalScore").asInt()).isEqualTo(70);
        org.assertj.core.api.Assertions.assertThat(first.path("actionType").asText()).isEqualTo("click");
        org.assertj.core.api.Assertions.assertThat(metrics.path("historicalCandidateCount").asInt()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(metrics.path("historicalMatchedCount").asInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(metrics.path("historicalRejectedCount").asInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(first.toString()).doesNotContain("页面内部机密", "button=查询", "access_token", "report", "observation", "actionFailure");
        Set<String> fields = new HashSet<>();
        first.properties().forEach(entry -> fields.add(entry.getKey()));
        org.assertj.core.api.Assertions.assertThat(fields)
            .containsExactlyInAnyOrder("round", "maxRounds", "pageUrl", "fingerprint", "pageScore", "elementScore", "stateScore", "totalScore", "actionType", "actionTarget", "targetKind", "accessName", "accessibleName", "locatorFingerprint", "candidateCount", "failureReason", "status", "durationMs", "candidate", "action", "failure", "metrics");
        org.assertj.core.api.Assertions.assertThat(first.path("fingerprint").asText()).isEqualTo("fp-1");
        org.assertj.core.api.Assertions.assertThat(first.path("candidate").has("candidateCount")).isTrue();
        org.assertj.core.api.Assertions.assertThat(first.path("candidate").has("matchedLabelsCount")).isTrue();
        org.assertj.core.api.Assertions.assertThat(first.path("candidate").has("hasTarget")).isTrue();
        org.assertj.core.api.Assertions.assertThat(first.path("action").has("actionType")).isTrue();
        org.assertj.core.api.Assertions.assertThat(first.path("action").has("safe")).isTrue();
        org.assertj.core.api.Assertions.assertThat(first.path("action").has("targetKind")).isTrue();
        org.assertj.core.api.Assertions.assertThat(first.path("failure").has("failureReason")).isTrue();
        org.assertj.core.api.Assertions.assertThat(first.path("failure").has("retryable")).isTrue();
        org.assertj.core.api.Assertions.assertThat(first.path("failure").has("category")).isTrue();
        org.assertj.core.api.Assertions.assertThat(first.path("action").toString()).doesNotContain("button=查询", "secret");
        org.assertj.core.api.Assertions.assertThat(first.path("failure").toString()).doesNotContain("原始长错误不应落库");
    }

    @Test
    void retriesSemanticCompilationMismatchOnceWhenEvidenceHasTimeRemaining() {
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-retry", "用户列表", "用户", "用户管理", "/users", List.of("进入用户管理"), "用户列表可见", "验证用户列表")),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode())
        );
        AgentWebCaseGenerationFacade.BrowserEvidenceBundle evidence = new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
            objectMapper.createArrayNode(), List.of("user-list"), 1, 1, true, "matched", 2, 90, 10_000L, objectMapper.createObjectNode()
        );
        List<AgentWebCaseGenerationFacade.GeneratedWebCase> generated = List.of(new AgentWebCaseGenerationFacade.GeneratedWebCase(
            "point-retry", "用户列表", "用户", "web_automation", "用户管理", "/users", "chromium", "1440x900", "已登录", "验证用户列表",
            "goto /users\nassertVisible text=用户列表", "assertVisible text=用户列表", "用户列表可见", "进入用户管理", "用户列表可见", "requirements.txt"
        ));
        when(runService.ruleVersion("run-retry")).thenReturn(1);
        when(runService.projectId("run-retry")).thenReturn(1L);
        when(runService.requestedPointIds("run-retry")).thenReturn(List.of("point-retry"));
        when(runService.ruleSnapshot("run-retry")).thenReturn(rules);
        when(runService.mode("run-retry")).thenReturn(WebCaseAgentMode.COMPILE);
        when(runService.planRunId("run-retry")).thenReturn("plan-run-retry");
        when(runService.confirmedPlanBatch("plan-run-retry")).thenReturn(plan);
        when(runService.artifactCount("run-retry")).thenReturn(1);
        when(memories.promptContext(1L)).thenReturn(List.of());
        when(generator.resolveEvidence(any(), any(), any(), any(), anyLong())).thenReturn(evidence);
        when(generator.resolveEvidence(any(), any(), any(), any(), eq(90_000L))).thenReturn(evidence);
        when(generator.compile(any(), any(), any(), any(), any(), any()))
            .thenThrow(new IllegalArgumentException("元素断言未命中自然语言步骤"))
            .thenReturn(generated);

        dispatcher.dispatch("run-retry", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-retry"), "env-1", "https://test.example.com", "chromium", "1440x900", null, null, WebCaseAgentMode.COMPILE, "plan-run-retry"
        ));

        verify(generator, timeout(1000)).resolveEvidence(any(), any(), any(), eq(List.of("元素断言未命中自然语言步骤")), eq(90_000L));
        verify(generator, timeout(1000).times(2)).compile(any(), any(), any(), any(), any(), any());
        verify(runService, timeout(1000)).markPointCompleted("run-retry", "point-retry");
        verify(runService, timeout(1000)).succeed("run-retry", 1);
    }

    @Test
    void blocksPointWhenTheSingleSemanticRetryAlsoFails() {
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-retry-fail", "用户列表", "用户", "用户管理", "/users", List.of("进入用户管理"), "用户列表可见", "验证用户列表")),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode())
        );
        AgentWebCaseGenerationFacade.BrowserEvidenceBundle evidence = new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
            objectMapper.createArrayNode(), List.of("user-list"), 1, 1, true, "matched", 2, 90, 10_000L, objectMapper.createObjectNode()
        );
        when(runService.ruleVersion("run-retry-fail")).thenReturn(1);
        when(runService.projectId("run-retry-fail")).thenReturn(1L);
        when(runService.requestedPointIds("run-retry-fail")).thenReturn(List.of("point-retry-fail"));
        when(runService.ruleSnapshot("run-retry-fail")).thenReturn(rules);
        when(runService.mode("run-retry-fail")).thenReturn(WebCaseAgentMode.COMPILE);
        when(runService.planRunId("run-retry-fail")).thenReturn("plan-run-retry-fail");
        when(runService.confirmedPlanBatch("plan-run-retry-fail")).thenReturn(plan);
        when(runService.artifactCount("run-retry-fail")).thenReturn(0);
        when(memories.promptContext(1L)).thenReturn(List.of());
        var blockedNetwork = objectMapper.createObjectNode()
            .set("criticalBlocked", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("origin", "https://api.example.test/v1?access_token=secret")
                .put("path", "/users?locator=button[data-token=\"secret\"]")
                .put("resourceType", "fetch")))
            .set("unknownBlocked", objectMapper.createArrayNode())
            .set("optionalBlocked", objectMapper.createArrayNode().add(objectMapper.createObjectNode()
                .put("origin", "https://cdn.example.test/assets?query=token")
                .put("path", "/static/app.js?token=secret")
                .put("resourceType", "script")));
        var retryReport = new WebAutomationLocatorDiscoveryService.ExplorationReport(
            "mismatch", List.of(
                new WebAutomationLocatorDiscoveryService.ExplorationRound(1, "https://test.example.com/users", "fp-1", "click", "text=用户", new WebAutomationLocatorDiscoveryService.ExplorationScores(20, 4, 2, 26, false)),
                new WebAutomationLocatorDiscoveryService.ExplorationRound(2, "https://test.example.com/users", "fp-2", "click", "text=列表", new WebAutomationLocatorDiscoveryService.ExplorationScores(25, 6, 3, 34, false)),
                new WebAutomationLocatorDiscoveryService.ExplorationRound(3, "https://test.example.com/users", "fp-3", "wait", "", new WebAutomationLocatorDiscoveryService.ExplorationScores(30, 11, 4, 45, false))
            ),
            new WebAutomationLocatorDiscoveryService.ExplorationScores(30, 11, 4, 45, false), List.of("https://test.example.com/users"),
            "重探仍未命中 token=secret locator=button[data-token=\"secret\"]", 900L,
            objectMapper.createObjectNode(),
            8_000L, null, blockedNetwork, null, null,
            List.of("bogus-match"),
            List.of("bogus-reject")
        );
        when(generator.resolveEvidence(eq(List.of("point-retry-fail")), any(), any(), any(), eq(90_000L)))
            .thenReturn(evidence)
            .thenThrow(new WebExplorationBlockedException("point-retry-fail", "重探仍未命中 token=secret locator=button[data-token=\"secret\"]", retryReport));
        when(generator.compile(any(), any(), any(), any(), any(), any()))
            .thenThrow(new IllegalArgumentException("不能只使用 assertUrl"))
            .thenThrow(new IllegalArgumentException("元素断言未命中自然语言步骤"));

        dispatcher.dispatch("run-retry-fail", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-retry-fail"), "env-1", "https://test.example.com", "chromium", "1440x900", null, null, WebCaseAgentMode.COMPILE, "plan-run-retry-fail"
        ));

        verify(runService, timeout(1000)).markPointBlocked("run-retry-fail", "point-retry-fail");
        verify(generator, timeout(1000).times(1)).compile(any(), any(), any(), any(), any(), any());
        verify(runService, never()).succeed(any(), eq(1));
        ArgumentCaptor<String> summaries = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<JsonNode> details = ArgumentCaptor.forClass(JsonNode.class);
        // CI 慢机上 atLeastOnce 会在「重探未命中目标」事件仍未来到时先被更早的事件满足（真竞态）；
        // 先按内容等待目标事件到达，再做捕获断言——若该事件从不出现则在此处诚实失败。
        verify(events, timeout(5000)).appendDetailed(any(), any(), any(), any(),
            org.mockito.ArgumentMatchers.contains("重探未命中目标"), any(), any(), anyLong(), any(), any(), any(), any());
        verify(events, timeout(1000).atLeastOnce()).appendDetailed(any(), any(), any(), any(), summaries.capture(), any(), any(), anyLong(), any(), any(), any(), details.capture());
        String summary = summaries.getAllValues().stream().filter(value -> value.contains("重探未命中目标")).findFirst().orElse("");
        org.assertj.core.api.Assertions.assertThat(summary)
            .contains("blockedResources=2")
            .doesNotContain("重探仍未命中", "token=secret", "button[data-token", "api.example.test/v1", "locator=");
        JsonNode detail = details.getAllValues().stream().filter(item -> item.path("blockedResourceCount").asInt() == 2).findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThat(detail.path("status").asText()).isEqualTo("mismatch");
        org.assertj.core.api.Assertions.assertThat(detail.path("rounds").asInt()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(detail.path("failureReason").asText())
            .doesNotContain("token=secret", "button[data-token", "access_token");
        org.assertj.core.api.Assertions.assertThat(detail.path("blockedResources").isArray()).isTrue();
        org.assertj.core.api.Assertions.assertThat(detail.path("blockedResources").toString())
            .contains("https://api.example.test", "/users", "fetch", "https://cdn.example.test", "/static/app.js", "script")
            .doesNotContain("token=secret", "button[data-token", "query=token");

        JsonNode explorationMetrics = details.getAllValues().stream()
            .map(item -> item.path("metrics"))
            .filter(item -> item.path("historicalCandidateCount").asInt() == 2)
            .findFirst()
            .orElseThrow();
        org.assertj.core.api.Assertions.assertThat(explorationMetrics.path("historicalCandidateCount").asInt()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(explorationMetrics.path("historicalMatchedCount").asInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(explorationMetrics.path("historicalRejectedCount").asInt()).isEqualTo(1);
    }

    @Test
    void resumeSkipsAlreadyCompletedPoints() {
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-2", "告警日志 Tab 展示", "日志管理", "日志管理页面", "/project", List.of("进入告警日志"), "告警日志可见", "验证告警日志 Tab")),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode())
        );
        AgentWebCaseGenerationFacade.BrowserEvidenceBundle evidence = new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
            objectMapper.createArrayNode(), List.of("known-alert-tab"), 1, 1, false
        );
        List<AgentWebCaseGenerationFacade.GeneratedWebCase> generated = List.of(new AgentWebCaseGenerationFacade.GeneratedWebCase(
            "point-2", "告警日志 Tab 展示", "日志管理", "web_automation", "日志管理页面", "/project", "chromium", "1440x900", "已登录", "验证告警日志 Tab",
            "assertVisible text=告警日志", "assertVisible text=告警日志", "告警日志可见", "进入告警日志", "告警日志可见", "requirements.txt"
        ));

        when(runService.ruleVersion("run-1")).thenReturn(1);
        when(runService.projectId("run-1")).thenReturn(1L);
        when(runService.requestedPointIds("run-1")).thenReturn(List.of("point-1", "point-2"));
        when(runService.completedPointIds("run-1")).thenReturn(List.of("point-1"));
        when(runService.ruleSnapshot("run-1")).thenReturn(rules);
        when(runService.mode("run-1")).thenReturn(WebCaseAgentMode.COMPILE);
        when(runService.planRunId("run-1")).thenReturn("plan-run-1");
        when(runService.confirmedPlanBatch("plan-run-1")).thenReturn(plan);
        when(runService.artifactCount("run-1")).thenReturn(1);
        when(memories.promptContext(1L)).thenReturn(List.of());
        when(generator.resolveEvidence(any(), any(), any(), any(), anyLong())).thenReturn(evidence);
        when(generator.compile(any(), any(), any(), any(), any(), any())).thenReturn(generated);

        dispatcher.dispatch("run-1", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-1", "point-2"), "env-1", "https://app-dev.example.test/", "chromium", "1440x900", null, null, WebCaseAgentMode.COMPILE, "plan-run-1"
        ));

        verify(runService, timeout(1000)).succeed("run-1", 1);
        verify(generator, timeout(1000)).resolveEvidence(eq(List.of("point-2")), any(), any(), any(), anyLong());
        verify(generator, never()).resolveEvidence(eq(List.of("point-1")), any(), any(), any(), anyLong());
    }

    @Test
    void compileModeBlocksOnlyTheInvalidPointAndContinuesRemainingPoints() {
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(
                new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-invalid", "停用入口", "威胁情报", "业务列表页", "/threat", List.of("查看停用按钮"), "展示停用按钮", "验证停用入口"),
                new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-valid", "日志 Tab", "日志管理", "日志页面", "/logs", List.of("查看全部日志"), "展示全部日志", "验证日志 Tab")
            ),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode())
        );
        AgentWebCaseGenerationFacade.BrowserEvidenceBundle evidence = new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
            objectMapper.createArrayNode(), List.of("known-log-tab"), 1, 1, false, "matched", 1, 80, 10_000L
        );
        List<AgentWebCaseGenerationFacade.GeneratedWebCase> generated = List.of(new AgentWebCaseGenerationFacade.GeneratedWebCase(
            "point-valid", "日志 Tab", "日志管理", "web_automation", "日志页面", "/logs", "chromium", "1440x900", "已登录", "验证日志 Tab",
            "goto /logs\nassertVisible text=全部日志", "assertVisible text=全部日志", "展示全部日志", "查看全部日志", "展示全部日志", "requirements.txt"
        ));

        when(runService.ruleVersion("run-1")).thenReturn(1);
        when(runService.projectId("run-1")).thenReturn(1L);
        when(runService.requestedPointIds("run-1")).thenReturn(List.of("point-invalid", "point-valid"));
        when(runService.ruleSnapshot("run-1")).thenReturn(rules);
        when(runService.mode("run-1")).thenReturn(WebCaseAgentMode.COMPILE);
        when(runService.planRunId("run-1")).thenReturn("plan-run-1");
        when(runService.confirmedPlanBatch("plan-run-1")).thenReturn(plan);
        when(runService.artifactCount("run-1")).thenReturn(1);
        when(runService.get("run-1")).thenReturn(new WebCaseAgentRunResponse(
            "run-1", 1L, "analysis-1", WebCaseAgentRunStatus.RUNNING, WebCaseAgentStage.PREFLIGHT,
            List.of("point-invalid", "point-valid"), List.of("point-invalid", "point-valid"), List.of(), 2, null, "2026-08-12T00:00:00Z", ""
        ));
        when(memories.promptContext(1L)).thenReturn(List.of());
        when(generator.resolveEvidence(any(), any(), any(), any(), anyLong())).thenReturn(evidence);
        when(generator.compile(eq(List.of("point-invalid")), any(), any(), any(), any(), any()))
            .thenThrow(new IllegalArgumentException("未采集到停用按钮"));
        when(generator.compile(eq(List.of("point-valid")), any(), any(), any(), any(), any())).thenReturn(generated);

        dispatcher.dispatch("run-1", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-invalid", "point-valid"), "env-1", "https://app-dev.example.test/", "chromium", "1440x900", null, null, WebCaseAgentMode.COMPILE, "plan-run-1"
        ));

        verify(runService, timeout(1000)).markPointBlocked("run-1", "point-invalid");
        verify(generator, timeout(1000)).compile(eq(List.of("point-valid")), any(), any(), any(), any(), any());
        verify(generator).resolveEvidence(eq(List.of("point-invalid")), any(), any(), any(), anyLong());
        verify(generator).resolveEvidence(eq(List.of("point-valid")), any(), any(), any(), anyLong());
        verify(runService, timeout(1000)).partial(eq("run-1"), anyInt(), anyInt());
        verify(runService, never()).fail(eq("run-1"), any());
    }

    @Test
    void compileModeCarriesBatchBudgetForwardAndKeepsGoingAfterFirstPointBlocked() {
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        AtomicLong nanos = new AtomicLong(0L);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery, Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC), nanos::get);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(
                new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-1", "停用入口", "威胁情报", "业务列表页", "/threat", List.of("查看停用按钮"), "展示停用按钮", "验证停用入口"),
                new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-2", "告警入口", "告警", "告警页面", "/alerts", List.of("查看告警按钮"), "告警按钮可见", "验证告警按钮")
            ),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode())
        );
        AgentWebCaseGenerationFacade.BrowserEvidenceBundle secondEvidence = new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
            objectMapper.createArrayNode(), List.of("known-alert-tab"), 1, 1, false, "matched", 1, 80, 70_000L
        );
        List<AgentWebCaseGenerationFacade.GeneratedWebCase> generated = List.of(new AgentWebCaseGenerationFacade.GeneratedWebCase(
            "point-2", "告警入口", "告警", "web_automation", "告警页面", "/alerts", "chromium", "1440x900", "已登录", "验证告警按钮",
            "assertVisible text=告警", "assertVisible text=告警", "告警按钮可见", "查看告警按钮", "告警按钮可见", "requirements.txt"
        ));
        var blockedNetwork = objectMapper.createObjectNode()
            .set("criticalBlocked", objectMapper.createArrayNode())
            .set("unknownBlocked", objectMapper.createArrayNode())
            .set("optionalBlocked", objectMapper.createArrayNode());
        var blockedReport = new WebAutomationLocatorDiscoveryService.ExplorationReport(
            "mismatch",
            List.of(new WebAutomationLocatorDiscoveryService.ExplorationRound(1, "https://test.example.com/threat", "fp-1", "click", "text=停用", new WebAutomationLocatorDiscoveryService.ExplorationScores(20, 4, 2, 26, false))),
            new WebAutomationLocatorDiscoveryService.ExplorationScores(20, 4, 2, 26, false),
            List.of("https://test.example.com/threat"),
            "浏览器探索未命中目标",
            30_000L,
            objectMapper.createObjectNode(),
            70_000L,
            null,
            blockedNetwork,
            null,
            objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("step", "retry"))
        );

        when(runService.ruleVersion("run-budget")).thenReturn(1);
        when(runService.projectId("run-budget")).thenReturn(1L);
        when(runService.requestedPointIds("run-budget")).thenReturn(List.of("point-1", "point-2"));
        when(runService.completedPointIds("run-budget")).thenReturn(List.of());
        when(runService.ruleSnapshot("run-budget")).thenReturn(rules);
        when(runService.mode("run-budget")).thenReturn(WebCaseAgentMode.COMPILE);
        when(runService.planRunId("run-budget")).thenReturn("plan-run-budget");
        when(runService.confirmedPlanBatch("plan-run-budget")).thenReturn(plan);
        when(runService.artifactCount("run-budget")).thenReturn(1);
        when(memories.promptContext(1L)).thenReturn(List.of());
        when(generator.resolveEvidence(eq(List.of("point-1")), any(), any(), any(), eq(90_000L)))
            .thenAnswer(invocation -> {
                nanos.addAndGet(TimeUnit.SECONDS.toNanos(30));
                throw new WebExplorationBlockedException("point-1", "浏览器探索未命中目标", blockedReport);
            });
        when(generator.resolveEvidence(eq(List.of("point-2")), any(), any(), any(), eq(70_000L))).thenReturn(secondEvidence);
        when(generator.compile(eq(List.of("point-2")), any(), any(), any(), any(), any())).thenReturn(generated);

        dispatcher.dispatch("run-budget", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-1", "point-2"), "env-1", "https://test.example.com", "chromium", "1440x900", null, null, 100_000L, WebCaseAgentMode.COMPILE, "plan-run-budget"
        ));

        verify(runService, timeout(1000)).markPointBlocked("run-budget", "point-1");
        verify(generator, timeout(1000)).resolveEvidence(eq(List.of("point-2")), any(), any(), any(), eq(70_000L));
        verify(runService, timeout(1000)).markPointCompleted("run-budget", "point-2");
        verify(runService, timeout(1000)).partial(eq("run-budget"), anyInt(), anyInt());

        ArgumentCaptor<JsonNode> summary = ArgumentCaptor.forClass(JsonNode.class);
        verify(runService, timeout(1000)).writeOutputSummary(eq("run-budget"), summary.capture());
        org.assertj.core.api.Assertions.assertThat(summary.getValue().path("status").asText()).isEqualTo("partial_success");
        org.assertj.core.api.Assertions.assertThat(summary.getValue().path("successes").isArray()).isTrue();
        org.assertj.core.api.Assertions.assertThat(summary.getValue().path("successes").size()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(summary.getValue().path("blocked").isArray()).isTrue();
        org.assertj.core.api.Assertions.assertThat(summary.getValue().path("blocked").size()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(summary.getValue().path("blocked").get(0).path("category").asText()).isEqualTo("browser_evidence_unmatched");
        org.assertj.core.api.Assertions.assertThat(summary.getValue().path("blocked").get(0).path("sourcePointId").asText()).isEqualTo("point-1");
    }

    @Test
    void compileModeMarksLaterPointsDurationExhaustedWhenTheBatchBudgetRunsOut() {
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        AtomicLong nanos = new AtomicLong(0L);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery, Clock.fixed(Instant.parse("2026-09-07T00:00:00Z"), ZoneOffset.UTC), nanos::get);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(
                new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-1", "停用入口", "威胁情报", "业务列表页", "/threat", List.of("查看停用按钮"), "展示停用按钮", "验证停用入口"),
                new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-2", "告警入口", "告警", "告警页面", "/alerts", List.of("查看告警按钮"), "告警按钮可见", "验证告警按钮")
            ),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode())
        );
        AgentWebCaseGenerationFacade.BrowserEvidenceBundle firstEvidence = new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
            objectMapper.createArrayNode(), List.of("known-threat-tab"), 1, 1, false, "matched", 1, 80, 0L
        );
        List<AgentWebCaseGenerationFacade.GeneratedWebCase> generated = List.of(new AgentWebCaseGenerationFacade.GeneratedWebCase(
            "point-1", "停用入口", "威胁情报", "web_automation", "业务列表页", "/threat", "chromium", "1440x900", "已登录", "验证停用入口",
            "assertVisible text=停用", "assertVisible text=停用", "停用按钮可见", "查看停用按钮", "停用按钮可见", "requirements.txt"
        ));

        when(runService.ruleVersion("run-budget-exhausted")).thenReturn(1);
        when(runService.projectId("run-budget-exhausted")).thenReturn(1L);
        when(runService.requestedPointIds("run-budget-exhausted")).thenReturn(List.of("point-1", "point-2"));
        when(runService.completedPointIds("run-budget-exhausted")).thenReturn(List.of());
        when(runService.ruleSnapshot("run-budget-exhausted")).thenReturn(rules);
        when(runService.mode("run-budget-exhausted")).thenReturn(WebCaseAgentMode.COMPILE);
        when(runService.planRunId("run-budget-exhausted")).thenReturn("plan-run-budget-exhausted");
        when(runService.confirmedPlanBatch("plan-run-budget-exhausted")).thenReturn(plan);
        when(runService.artifactCount("run-budget-exhausted")).thenReturn(1);
        when(memories.promptContext(1L)).thenReturn(List.of());
        when(generator.resolveEvidence(eq(List.of("point-1")), any(), any(), any(), eq(90_000L))).thenReturn(firstEvidence);
        when(generator.compile(eq(List.of("point-1")), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            nanos.addAndGet(TimeUnit.SECONDS.toNanos(100));
            return generated;
        });

        dispatcher.dispatch("run-budget-exhausted", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-1", "point-2"), "env-1", "https://test.example.com", "chromium", "1440x900", null, null, 100_000L, WebCaseAgentMode.COMPILE, "plan-run-budget-exhausted"
        ));

        verify(runService, timeout(1000)).markPointCompleted("run-budget-exhausted", "point-1");
        verify(runService, timeout(1000)).markPointBlocked("run-budget-exhausted", "point-2");
        verify(generator, never()).resolveEvidence(eq(List.of("point-2")), any(), any(), any(), anyLong());
        verify(runService, timeout(1000)).partial(eq("run-budget-exhausted"), anyInt(), anyInt());

        ArgumentCaptor<JsonNode> summary = ArgumentCaptor.forClass(JsonNode.class);
        verify(runService, timeout(1000)).writeOutputSummary(eq("run-budget-exhausted"), summary.capture());
        org.assertj.core.api.Assertions.assertThat(summary.getValue().path("status").asText()).isEqualTo("partial_success");
        org.assertj.core.api.Assertions.assertThat(summary.getValue().path("blocked").isArray()).isTrue();
        org.assertj.core.api.Assertions.assertThat(summary.getValue().path("blocked").size()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(summary.getValue().path("blocked").get(0).path("category").asText()).isEqualTo("duration_exhausted");
        org.assertj.core.api.Assertions.assertThat(summary.getValue().path("blocked").get(0).path("sourcePointId").asText()).isEqualTo("point-2");
    }

    @Test
    void locatorKnowledgePersistenceFailureBlocksOnlyThatPointAndEmitsPersistenceError() {
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(
                new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-persistence", "查询入口", "日志管理", "日志页面", "/logs", List.of("查看查询按钮"), "查询按钮可见", "验证查询按钮"),
                new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-valid", "告警入口", "告警", "告警页面", "/alerts", List.of("查看告警按钮"), "告警按钮可见", "验证告警按钮")
            ),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode())
        );
        var persistenceEvidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
            .put("source", "browserDiscovery").put("sourcePointId", "point-persistence").put("moduleName", "日志管理").put("pageName", "日志页面").put("pageUrl", "https://test.example.com/logs")
            .set("elements", objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("locator", "role=button[name=查询]").put("label", "查询").put("role", "button").put("tagName", "button")))
            .set("exploration", objectMapper.createObjectNode().put("status", "matched").set("rounds", objectMapper.createArrayNode()).set("scores", objectMapper.createObjectNode().put("totalScore", 80))));
        var validEvidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
            .put("source", "browserDiscovery").put("sourcePointId", "point-valid").put("moduleName", "告警").put("pageName", "告警页面").put("pageUrl", "https://test.example.com/alerts")
            .set("elements", objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("locator", "testId=alert").put("label", "告警").put("role", "button").put("tagName", "button")))
            .set("exploration", objectMapper.createObjectNode().put("status", "matched").set("rounds", objectMapper.createArrayNode()).set("scores", objectMapper.createObjectNode().put("totalScore", 80))));
        AgentWebCaseGenerationFacade.BrowserEvidenceBundle firstEvidence = new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
            persistenceEvidence, List.of(), 1, 1, true, "matched", 1, 80, 10_000L, objectMapper.createObjectNode());
        AgentWebCaseGenerationFacade.BrowserEvidenceBundle secondEvidence = new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
            validEvidence, List.of(), 1, 1, true, "matched", 1, 80, 10_000L, objectMapper.createObjectNode());
        List<AgentWebCaseGenerationFacade.GeneratedWebCase> generated = List.of(new AgentWebCaseGenerationFacade.GeneratedWebCase(
            "point-valid", "告警入口", "告警", "web_automation", "告警页面", "/alerts", "chromium", "1440x900", "已登录", "验证告警按钮",
            "assertVisible testId=alert", "assertVisible testId=alert", "告警按钮可见", "查看告警按钮", "告警按钮可见", "requirements.txt"));

        when(runService.ruleVersion("run-persistence")).thenReturn(1);
        when(runService.projectId("run-persistence")).thenReturn(1L);
        when(runService.requestedPointIds("run-persistence")).thenReturn(List.of("point-persistence", "point-valid"));
        when(runService.completedPointIds("run-persistence")).thenReturn(List.of());
        when(runService.ruleSnapshot("run-persistence")).thenReturn(rules);
        when(runService.mode("run-persistence")).thenReturn(WebCaseAgentMode.COMPILE);
        when(runService.planRunId("run-persistence")).thenReturn("plan-run-persistence");
        when(runService.confirmedPlanBatch("plan-run-persistence")).thenReturn(plan);
        when(runService.artifactCount("run-persistence")).thenReturn(1);
        when(memories.promptContext(1L)).thenReturn(List.of());
        when(generator.resolveEvidence(any(), any(), any(), any(), anyLong())).thenReturn(firstEvidence, secondEvidence);
        when(generator.compile(eq(List.of("point-valid")), any(), any(), any(), any(), any())).thenReturn(generated);
        doThrow(new DataAccessResourceFailureException("locator knowledge database unavailable"))
            .doNothing()
            .when(locatorKnowledge).recordAgentEvidence(anyLong(), anyString(), anyString(), anyString(), anyString(), anyList(), any());
        doAnswer(invocation -> {
            if ("locator_knowledge_persistence_failed".equals(invocation.getArgument(10, String.class))) {
                throw new DataAccessResourceFailureException("event store unavailable");
            }
            return null;
        }).when(events).append(anyString(), any(), any(), anyString(), anyString(), any(), any(), anyLong(), any(), any(), any());

        dispatcher.dispatch("run-persistence", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-persistence", "point-valid"), "env-1", "https://test.example.com", "chromium", "1440x900", null, null, WebCaseAgentMode.COMPILE, "plan-run-persistence"));

        verify(runService, timeout(1000)).markPointBlocked("run-persistence", "point-persistence");
        verify(generator, never()).compile(eq(List.of("point-persistence")), any(), any(), any(), any(), any());
        verify(runService, timeout(1000)).markPointCompleted("run-persistence", "point-valid");
        verify(runService, timeout(1000)).partial(eq("run-persistence"), anyInt(), anyInt());
        ArgumentCaptor<String> summaries = ArgumentCaptor.forClass(String.class);
        verify(events, timeout(1000).atLeastOnce()).append(eq("run-persistence"), eq(WebCaseAgentStage.EVIDENCE), eq(WebCaseAgentEventType.ERROR), eq("error"), summaries.capture(), eq("point-persistence"), eq("locator-knowledge"), anyLong(), eq(1), any(), eq("locator_knowledge_persistence_failed"));
        verify(events, timeout(1000).times(1)).append(eq("run-persistence"), eq(WebCaseAgentStage.EVIDENCE), eq(WebCaseAgentEventType.ERROR), eq("error"), anyString(), eq("point-persistence"), eq("locator-knowledge"), anyLong(), eq(1), any(), eq("locator_knowledge_persistence_failed"));
        org.assertj.core.api.Assertions.assertThat(summaries.getAllValues()).anyMatch(value -> value.contains("素材库持久化失败"));
    }

    @Test
    void recordHitPersistenceFailureBlocksOnlyThatPointAndContinues() {
        assertLocatorWriteFailureIsPointScoped(false);
    }

    @Test
    void recordExecutionOutcomePersistenceFailureBlocksOnlyThatPointAndContinues() {
        assertLocatorWriteFailureIsPointScoped(true);
    }

    @Test
    void evidenceReadFailureIsNotReportedAsLocatorPersistenceFailure() {
        String runId = "run-evidence-read";
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(
                new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-read", "查询入口", "日志管理", "日志页面", "/logs", List.of("查看查询按钮"), "查询按钮可见", "验证查询按钮"),
                new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-valid", "告警入口", "告警", "告警页面", "/alerts", List.of("查看告警按钮"), "告警按钮可见", "验证告警按钮")
            ), objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode()));
        AgentWebCaseGenerationFacade.BrowserEvidenceBundle evidence = new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
            objectMapper.createArrayNode(), List.of("known-locator"), 1, 1, false, "matched", 1, 80, 10_000L);

        when(runService.ruleVersion(runId)).thenReturn(1);
        when(runService.projectId(runId)).thenReturn(1L);
        when(runService.requestedPointIds(runId)).thenReturn(List.of("point-read", "point-valid"));
        when(runService.completedPointIds(runId)).thenReturn(List.of());
        when(runService.ruleSnapshot(runId)).thenReturn(rules);
        when(runService.mode(runId)).thenReturn(WebCaseAgentMode.COMPILE);
        when(runService.planRunId(runId)).thenReturn("plan-run");
        when(runService.confirmedPlanBatch("plan-run")).thenReturn(plan);
        when(runService.artifactCount(runId)).thenReturn(1);
        when(memories.promptContext(1L)).thenReturn(List.of());
        when(generator.resolveEvidence(any(), any(), any(), any(), anyLong()))
            .thenThrow(new DataAccessResourceFailureException("locator knowledge read unavailable"))
            .thenReturn(evidence);
        when(generator.compile(eq(List.of("point-valid")), any(), any(), any(), any(), any())).thenReturn(List.of());

        dispatcher.dispatch(runId, new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-read", "point-valid"), "env-1", "https://test.example.com", "chromium", "1440x900", null, null, WebCaseAgentMode.COMPILE, "plan-run"));

        verify(runService, timeout(1000)).markPointBlocked(runId, "point-read");
        verify(runService, timeout(1000)).markPointCompleted(runId, "point-valid");
        verify(runService, timeout(1000)).partial(eq(runId), anyInt(), anyInt());
        verify(runService, never()).fail(eq(runId), any());
        verify(events, timeout(1000).times(1)).append(eq(runId), eq(WebCaseAgentStage.EVIDENCE), eq(WebCaseAgentEventType.ERROR), eq("error"), anyString(), eq("point-read"), eq("locator-knowledge"), anyLong(), eq(1), any(), eq("browser_evidence_read_failed"));
        verify(events, never()).append(eq(runId), any(), any(), anyString(), anyString(), eq("point-read"), any(), anyLong(), any(), any(), eq("locator_knowledge_persistence_failed"));
    }

    @Test
    void evidenceHandoffExceptionKeepsStructuredCategoryInDispatcherEvents() {
        String runId = "run-handoff";
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-handoff", "登录页", "登录", "登录页", "/login", List.of("打开登录页"), "登录页可见", "验证登录页")),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode())
        );
        JsonNode evidenceDetails = objectMapper.createObjectNode()
            .put("category", "manual_review_required")
            .put("pageUrl", "https://example.test/login")
            .put("retryable", false)
            .set("handoff", objectMapper.createObjectNode().put("required", true).put("mode", "manual").put("reason", "manual_review_required").put("resumeSupported", false));
        WebAutomationEvidenceException evidenceFailure = new WebAutomationEvidenceException(422, "runner returned structured handoff evidence", "manual_review_required", evidenceDetails);
        AgentWebCaseGenerationFacade.BrowserEvidenceBundle evidence = new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
            objectMapper.createArrayNode(), List.of(), 1, 1, false, "matched", 1, 80, 10_000L
        );

        when(runService.ruleVersion(runId)).thenReturn(1);
        when(runService.projectId(runId)).thenReturn(1L);
        when(runService.requestedPointIds(runId)).thenReturn(List.of("point-handoff"));
        when(runService.completedPointIds(runId)).thenReturn(List.of());
        when(runService.ruleSnapshot(runId)).thenReturn(rules);
        when(runService.mode(runId)).thenReturn(WebCaseAgentMode.COMPILE);
        when(runService.planRunId(runId)).thenReturn("plan-run-handoff");
        when(runService.confirmedPlanBatch("plan-run-handoff")).thenReturn(plan);
        when(runService.artifactCount(runId)).thenReturn(0);
        when(memories.promptContext(1L)).thenReturn(List.of());
        when(generator.resolveEvidence(any(), any(), any(), any(), anyLong())).thenThrow(evidenceFailure);
        when(generator.compile(any(), any(), any(), any(), any(), any())).thenReturn(List.of());

        dispatcher.dispatch(runId, new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-handoff"), "env-1", "https://example.test", "chromium", "1440x900", null, null, WebCaseAgentMode.COMPILE, "plan-run-handoff"));

        ArgumentCaptor<String> errorCodes = ArgumentCaptor.forClass(String.class);
        verify(events, timeout(1000)).appendDetailed(eq(runId), eq(WebCaseAgentStage.EVIDENCE), eq(WebCaseAgentEventType.ERROR), eq("error"), anyString(), eq("point-handoff"), eq("browser-discovery"), anyLong(), eq(1), any(), errorCodes.capture(), eq(evidenceDetails));
        org.assertj.core.api.Assertions.assertThat(errorCodes.getValue()).isEqualTo("manual_review_required");
        verify(runService, timeout(1000)).markPointBlocked(runId, "point-handoff");
        verify(runService, never()).fail(eq(runId), any());
        verify(runService, never()).succeed(eq(runId), anyInt());
    }

    @Test
    void safeFailureSummaryIncludesTypeAndRedactsSensitiveMessageParts() {
        String summary = WebCaseAgentDispatcher.safeFailureSummary(new DataAccessResourceFailureException(
            "access_token=secret query=secret search=token selector=button[name=查询] locator=text=secret password=secret token=secret"));
        org.assertj.core.api.Assertions.assertThat(summary)
            .startsWith("DataAccessResourceFailureException:")
            .contains("query=[redacted]", "search=[redacted]", "selector=[redacted]", "locator=[redacted]", "password=[redacted]", "token=[redacted]")
            .doesNotContain("secret", "role=button", "text=secret", "select *");
    }

    @Test
    void userVisibleFailureSummaryRedactsLocatorCredentialsAndQueries() {
        String summary = WebCaseAgentDispatcher.safeAgentFailureMessage(
            new IllegalArgumentException("waitForUrl failed: https://user:pass@example.test/#/login?query=secret search=token selector=button locator=css=#secret password=secret token=secret"),
            "浏览器证据采集失败"
        );

        org.assertj.core.api.Assertions.assertThat(summary)
            .doesNotContain("query=secret", "search=token", "selector=button", "locator=css", "password=secret", "token=secret", "user:pass", "secret")
            .contains("[url]", "search=[redacted]", "selector=[redacted]", "locator=[redacted]", "password=[redacted]")
            .contains("IllegalArgumentException");
    }

    private void assertLocatorWriteFailureIsPointScoped(boolean failAtExecutionOutcome) {
        String runId = failAtExecutionOutcome ? "run-outcome-persistence" : "run-hit-persistence";
        String failedPointId = "point-persistence";
        String validPointId = "point-valid";
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(
                new AgentWebCaseGenerationFacade.NaturalWebCasePlan(failedPointId, "查询入口", "日志管理", "日志页面", "/logs", List.of("查看查询按钮"), "查询按钮可见", "验证查询按钮"),
                new AgentWebCaseGenerationFacade.NaturalWebCasePlan(validPointId, "告警入口", "告警", "告警页面", "/alerts", List.of("查看告警按钮"), "告警按钮可见", "验证告警按钮")
            ), objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode()));
        AgentWebCaseGenerationFacade.BrowserEvidenceBundle evidence = new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
            objectMapper.createArrayNode(), List.of("known-locator"), 1, 1, false, "matched", 1, 80, 10_000L);
        List<AgentWebCaseGenerationFacade.GeneratedWebCase> generated = List.of(new AgentWebCaseGenerationFacade.GeneratedWebCase(
            validPointId, "告警入口", "告警", "web_automation", "告警页面", "/alerts", "chromium", "1440x900", "已登录", "验证告警按钮",
            "assertVisible testId=alert", "assertVisible testId=alert", "告警按钮可见", "查看告警按钮", "告警按钮可见", "requirements.txt"));

        when(runService.ruleVersion(runId)).thenReturn(1);
        when(runService.projectId(runId)).thenReturn(1L);
        when(runService.requestedPointIds(runId)).thenReturn(List.of(failedPointId, validPointId));
        when(runService.completedPointIds(runId)).thenReturn(List.of());
        when(runService.ruleSnapshot(runId)).thenReturn(rules);
        when(runService.mode(runId)).thenReturn(WebCaseAgentMode.COMPILE);
        when(runService.planRunId(runId)).thenReturn("plan-run");
        when(runService.confirmedPlanBatch("plan-run")).thenReturn(plan);
        when(runService.artifactCount(runId)).thenReturn(1);
        when(memories.promptContext(1L)).thenReturn(List.of());
        when(generator.resolveEvidence(any(), any(), any(), any(), anyLong())).thenReturn(evidence);
        when(generator.compile(eq(List.of(validPointId)), any(), any(), any(), any(), any())).thenReturn(generated);
        if (failAtExecutionOutcome) {
            when(generator.compile(eq(List.of(failedPointId)), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("provider unavailable"));
            doThrow(new DataAccessResourceFailureException("execution outcome database unavailable"))
                .when(locatorKnowledge).recordExecutionOutcome(runId, failedPointId, false);
        } else {
            doThrow(new DataAccessResourceFailureException("locator hit database unavailable"))
                .doNothing()
                .when(locatorKnowledge).recordHit(eq(runId), anyString(), anyList());
        }

        dispatcher.dispatch(runId, new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of(failedPointId, validPointId), "env-1", "https://test.example.com", "chromium", "1440x900", null, null, WebCaseAgentMode.COMPILE, "plan-run"));

        verify(runService, timeout(1000)).markPointBlocked(runId, failedPointId);
        verify(runService, timeout(1000)).markPointCompleted(runId, validPointId);
        // T10 bullet 4：一个点被持久化失败阻塞、其余点成功 ⇒ 运行汇总为 partial。
        verify(runService, timeout(1000)).partial(eq(runId), anyInt(), anyInt());
        verify(runService, never()).fail(eq(runId), any());
        verify(events, timeout(1000).times(1)).append(eq(runId), eq(WebCaseAgentStage.EVIDENCE), eq(WebCaseAgentEventType.ERROR), eq("error"), anyString(), eq(failedPointId), eq("locator-knowledge"), anyLong(), eq(1), any(), eq("locator_knowledge_persistence_failed"));
    }

    @Test
    void explorationDetailsExposeSafeLocatorIdentityAndAccessibilityMetadata() throws Exception {
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);
        AgentRuleSnapshot rules = new AgentRuleSnapshot("web-case-agent", "project", 1L, 1, "hash", java.util.Map.of());
        AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch plan = new AgentWebCaseGenerationFacade.NaturalWebCasePlanBatch(
            List.of(new AgentWebCaseGenerationFacade.NaturalWebCasePlan("point-detail", "停用入口", "威胁情报", "业务列表页", "/threat", List.of("查看停用按钮"), "停用按钮可见", "验证停用入口")),
            objectMapper.createObjectNode().set("cases", objectMapper.createArrayNode()));
        String sensitiveTarget = "role=button[name=\"停用\"]?access_token=secret";
        var rawEvidence = objectMapper.createArrayNode().add(objectMapper.createObjectNode()
            .put("source", "browserDiscovery").put("sourcePointId", "point-detail").put("moduleName", "威胁情报").put("pageName", "业务列表页").put("pageUrl", "https://test.example.com/threat?access_token=secret")
            .set("elements", objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("locator", "role=button[name=\"停用\"]").put("label", "停用").put("role", "button").put("accessibleName", "停用").put("tagName", "button")))
            .set("tabs", objectMapper.createArrayNode().add(objectMapper.createObjectNode().put("locator", "text=只读详情").put("label", "只读详情").put("accessibleName", "只读详情").put("role", "tab")))
            .set("exploration", objectMapper.createObjectNode().put("status", "matched").set("rounds", objectMapper.createArrayNode()
                .add(objectMapper.createObjectNode().put("round", 1).put("actionType", "switch-tab").put("actionTarget", sensitiveTarget).set("scores", objectMapper.createObjectNode().put("pageScore", 20).put("elementScore", 25).put("stateScore", 10).put("totalScore", 55)))
                .add(objectMapper.createObjectNode().put("round", 2).put("actionType", "switch-tab").put("actionTarget", "text=只读详情").set("scores", objectMapper.createObjectNode().put("pageScore", 20).put("elementScore", 25).put("stateScore", 10).put("totalScore", 55)))
                .add(objectMapper.createObjectNode().put("round", 3).put("actionType", "click").put("actionTarget", "css=[data-tab=\"?\"]").set("scores", objectMapper.createObjectNode().put("pageScore", 20).put("elementScore", 25).put("stateScore", 10).put("totalScore", 55))))
                .set("scores", objectMapper.createObjectNode().put("totalScore", 55))));
        AgentWebCaseGenerationFacade.BrowserEvidenceBundle evidence = new AgentWebCaseGenerationFacade.BrowserEvidenceBundle(
            rawEvidence, List.of(), 1, 1, true, "matched", 1, 55, 10_000L, objectMapper.createObjectNode());
        when(runService.ruleVersion("run-detail")).thenReturn(1);
        when(runService.projectId("run-detail")).thenReturn(1L);
        when(runService.requestedPointIds("run-detail")).thenReturn(List.of("point-detail"));
        when(runService.completedPointIds("run-detail")).thenReturn(List.of());
        when(runService.ruleSnapshot("run-detail")).thenReturn(rules);
        when(runService.mode("run-detail")).thenReturn(WebCaseAgentMode.COMPILE);
        when(runService.planRunId("run-detail")).thenReturn("plan-run-detail");
        when(runService.confirmedPlanBatch("plan-run-detail")).thenReturn(plan);
        when(runService.artifactCount("run-detail")).thenReturn(1);
        when(memories.promptContext(1L)).thenReturn(List.of());
        when(generator.resolveEvidence(any(), any(), any(), any(), anyLong())).thenReturn(evidence);
        when(generator.compile(any(), any(), any(), any(), any(), any())).thenReturn(List.of());

        dispatcher.dispatch("run-detail", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-detail"), "env-1", "https://test.example.com", "chromium", "1440x900", null, null, WebCaseAgentMode.COMPILE, "plan-run-detail"));

        ArgumentCaptor<JsonNode> details = ArgumentCaptor.forClass(JsonNode.class);
        // 同型竞态加固：先按内容等到 round=3 的明细事件（本测试要断言三轮明细），再捕获断言。
        verify(events, timeout(5000)).appendDetailed(anyString(), any(), any(), anyString(), anyString(), any(), any(), anyLong(), any(), any(), any(),
            org.mockito.ArgumentMatchers.argThat(item -> item != null && item.path("round").asInt() == 3));
        verify(events, timeout(1000).atLeast(2)).appendDetailed(anyString(), any(), any(), anyString(), anyString(), any(), any(), anyLong(), any(), any(), any(), details.capture());
        JsonNode diagnostic = details.getAllValues().stream().filter(item -> item.path("round").asInt() == 1).findFirst().orElseThrow();
        // Keep the existing nested action summary while adding Task4's flat diagnostic fields.
        org.assertj.core.api.Assertions.assertThat(diagnostic.path("action").has("targetKind")).isTrue();
        org.assertj.core.api.Assertions.assertThat(diagnostic.path("targetKind").asText()).isEqualTo("role");
        org.assertj.core.api.Assertions.assertThat(diagnostic.path("accessibleName").asText()).isEqualTo("停用");
        org.assertj.core.api.Assertions.assertThat(diagnostic.path("accessName").asText()).isEqualTo("停用");
        String expectedFingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(sensitiveTarget.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        org.assertj.core.api.Assertions.assertThat(diagnostic.path("locatorFingerprint").asText()).isEqualTo(expectedFingerprint);
        org.assertj.core.api.Assertions.assertThat(diagnostic.toString()).doesNotContain("access_token", "secret", "role=button");
        JsonNode textLocatorDiagnostic = details.getAllValues().stream().filter(item -> item.path("round").asInt() == 2).findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThat(textLocatorDiagnostic.path("accessName").asText()).isEqualTo("只读详情");
        org.assertj.core.api.Assertions.assertThat(textLocatorDiagnostic.path("accessibleName").asText()).isEqualTo("只读详情");
        JsonNode legalLocatorDiagnostic = details.getAllValues().stream().filter(item -> item.path("round").asInt() == 3).findFirst().orElseThrow();
        String legalLocator = "css=[data-tab=\"?\"]";
        String legalFingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(legalLocator.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        org.assertj.core.api.Assertions.assertThat(legalLocatorDiagnostic.path("locatorFingerprint").asText()).isEqualTo(legalFingerprint);
    }

    @Test
    void keepsMultipleAgentRunsFromBlockingEachOtherAtPreflight() {
        WebCaseAgentRunService runService = mock(WebCaseAgentRunService.class);
        WebCaseAgentEventService events = mock(WebCaseAgentEventService.class);
        AgentWebCaseGenerationFacade generator = mockGeneratorFacade();
        AgentMemoryService memories = mock(AgentMemoryService.class);
        LocatorKnowledgeService locatorKnowledge = mock(LocatorKnowledgeService.class);
        WebCaseQualityFeedbackService qualityFeedback = mock(WebCaseQualityFeedbackService.class);
        WebAutomationLocatorDiscoveryService browserDiscovery = mock(WebAutomationLocatorDiscoveryService.class);
        WebCaseAgentDispatcher dispatcher = new WebCaseAgentDispatcher(runService, events, generator, memories, locatorKnowledge, qualityFeedback, browserDiscovery);

        when(runService.ruleVersion(any())).thenReturn(1);
        when(runService.projectId(any())).thenReturn(1L);
        when(runService.requestedPointIds(any())).thenReturn(List.of("point-1"));
        when(runService.ruleSnapshot(any())).thenThrow(new AssertionError("should not reach generation"));
        when(runService.get(any())).thenReturn(new WebCaseAgentRunResponse(
            "run-1", 1L, "analysis-1", WebCaseAgentRunStatus.RUNNING, WebCaseAgentStage.PREFLIGHT,
            List.of("point-1"), List.of(), List.of(), 1, null, "2026-08-12T00:00:00Z", ""
        ));

        dispatcher.dispatch("run-1", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-1"), "env-1", "https://app-dev.example.test/",
            "chromium", "1440x900", null, null
        ));
        dispatcher.dispatch("run-2", new WebCaseAgentModels.WebCaseAgentStartRequest(
            1L, "analysis-1", List.of("point-1"), "env-1", "https://app-dev.example.test/",
            "chromium", "1440x900", null, null
        ));

        verify(runService, timeout(1000).atLeastOnce()).markRunning("run-1");
        verify(runService, timeout(1000).atLeastOnce()).markRunning("run-2");
    }
}
