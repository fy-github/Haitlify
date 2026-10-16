package com.haitlify.aitest.platform.ai.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.haitlify.aitest.platform.webautomation.WebAutomationLocatorDiscoveryService;

import tools.jackson.databind.ObjectMapper;

class WebExplorationIntentBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void buildsBusinessFocusedIntentFromReviewedPlanAndSourcePoint() throws Exception {
        var plan = objectMapper.readTree("""
            {"title":"即将到期免费体验版显示停用按钮","module":"威胁情报业务","pageName":"威胁情报业务列表页",
             "naturalSteps":"进入列表页\\n找到即将到期记录\\n检查停用按钮","naturalExpectedResult":"操作列展示停用按钮"}
            """);

        var intent = new WebExplorationIntentBuilder().build(plan, Map.of(
            "TITLE", "即将到期免费体验版显示停用按钮",
            "TEST_OBJECTIVE", "验证即将到期记录允许停用"
        ));

        assertThat(intent.pageAliases()).contains("威胁情报业务列表页", "威胁情报业务");
        assertThat(intent.elementTerms()).contains("停用");
        assertThat(intent.elementTerms()).doesNotContain("项目", "页面");
        assertThat(intent.actionTerms()).contains("停用");
        assertThat(intent.stateTerms()).contains("即将到期");
        assertThat(intent.negativeTerms()).isEmpty();
    }

    @Test
    void extractsQuotedUiCopyAndStructuredContextWithoutPromotingGlobalWords() throws Exception {
        var plan = objectMapper.readTree("""
            {"title":"冻结资格入口展示恢复服务资格失效提示","module":"权益中心","pageName":"客户详情页",
             "route":"/entitlements/customer/detail","breadcrumbs":["客户管理","权益中心","客户详情"],
             "naturalSteps":"在操作列查看冻结资格入口，状态列为灰度中时检查详情列表",
             "naturalExpectedResult":"页面提示\\\"恢复服务资格\\\"、\\\"权益已失效\\\"，列表详情可见"}
            """);

        var intent = new WebExplorationIntentBuilder().build(plan, Map.of(
            "SOURCE_TEXT", "全局导航项目菜单不应成为业务元素",
            "EXPLICIT_NEGATIVE_TERMS", "全局导航",
            "ROUTE_HINT", "/entitlements/customer/detail"
        ));

        assertThat(intent.pageAliases()).contains("客户详情页", "权益中心", "客户详情", "客户管理");
        assertThat(intent.routeHints()).contains("/entitlements/customer/detail");
        assertThat(intent.elementTerms()).contains("恢复服务资格", "权益已失效", "入口", "列表", "详情", "操作列", "状态列");
        assertThat(intent.stateTerms()).contains("灰度中", "冻结", "失效");
        assertThat(intent.expectedTexts()).contains("页面提示\"恢复服务资格\"、\"权益已失效\"，列表详情可见");
        assertThat(intent.negativeTerms()).containsExactly("全局导航");
        assertThat(intent.elementTerms()).doesNotContain("项目", "页面", "管理");
    }

    @Test
    void separatesExplicitNegativeTermsFromGenericNavigationTerms() throws Exception {
        var plan = objectMapper.readTree("""
            {"pageName":"冻结资格列表页","naturalSteps":"不要点击全局导航中的项目管理，检查灰度状态"}
            """);

        var intent = new WebExplorationIntentBuilder().build(plan, Map.of(
            "EXPLICIT_NEGATIVE_TERMS", "全局导航\n项目管理"
        ));

        assertThat(intent.negativeTerms()).containsExactly("全局导航", "项目管理");
        assertThat(intent.elementTerms()).doesNotContain("全局导航", "项目管理", "项目", "管理");
    }

    @Test
    void boundsAndDeduplicatesSemanticTermsAcrossSources() throws Exception {
        var plan = objectMapper.readTree("""
            {"title":"冻结资格入口展示恢复服务资格失效提示","module":"权益中心","pageName":"客户详情页",
             "breadcrumbs":["客户管理","权益中心","客户详情","客户详情"],
             "route":"/entitlements/customer/detail",
             "naturalSteps":"点击\\\"恢复服务资格\\\"按钮\\n查看\\\"恢复服务资格\\\"提示\\n检查\\\"灰度状态\\\"",
             "naturalExpectedResult":"页面显示\\\"权益失效提示\\\"并保留\\\"恢复服务资格\\\"入口"}
            """);

        var source = Map.<String, Object>of(
            "SOURCE_TEXT", "冻结资格入口\\n恢复服务资格\\n权益失效提示\\n灰度状态\\n项目\\n页面\\n项目\\n页面",
            "EXPLICIT_NEGATIVE_TERMS", "项目管理\\n全局导航\\n项目管理",
            "ROUTE_HINTS", List.of("/entitlements/customer/detail", "/entitlements/customer/detail?from=nav")
        );

        var intent = new WebExplorationIntentBuilder().build(plan, source);

        assertThat(intent.pageAliases()).contains("客户详情页", "权益中心", "客户管理");
        assertThat(intent.pageAliases()).doesNotHaveDuplicates();
        assertThat(intent.pageAliases().size()).isLessThanOrEqualTo(8);
        assertThat(intent.routeHints()).contains("/entitlements/customer/detail", "/entitlements/customer/detail?from=nav");
        assertThat(intent.routeHints().size()).isLessThanOrEqualTo(8);
        assertThat(intent.elementTerms()).contains("恢复服务资格", "灰度状态", "权益失效提示", "按钮", "详情", "入口", "提示", "状态");
        assertThat(intent.elementTerms()).doesNotContain("项目", "页面");
        assertThat(intent.elementTerms().size()).isLessThanOrEqualTo(8);
        assertThat(intent.actionTerms()).contains("点击", "查看");
        assertThat(intent.actionTerms().size()).isLessThanOrEqualTo(4);
        assertThat(intent.stateTerms()).contains("灰度状态", "失效");
        assertThat(intent.stateTerms().size()).isLessThanOrEqualTo(6);
        assertThat(intent.negativeTerms()).containsExactly("项目管理", "全局导航");
    }

    @Test
    void serializesTargetIntentAndLegacyRunnerIntentNames() throws Exception {
        var intent = new WebAutomationLocatorDiscoveryService.TargetIntent(
            List.of("日志页面"), List.of("/logs"), List.of("告警日志"), List.of("查看"),
            List.of("在线"), List.of("告警日志可见"), List.of("项目")
        );
        var request = new WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest(
            "https://example.test/#/logs", "https://example.test/", "chromium", "1440x900", true,
            10_000, "domcontentloaded", null, Map.of(), List.of(), null, List.of("日志页面"), intent,
            new WebAutomationLocatorDiscoveryService.ExplorationBudget(5, 90_000, 2, 1, 1, 3, 70, 25), List.of("previous-failure")
        );

        var serialized = objectMapper.writeValueAsString(request);
        assertThat(serialized).contains("\"targetIntent\"");
        assertThat(serialized).contains("\"intent\"");
        assertThat(serialized).contains("\"previousAttempts\"");
        assertThat(serialized).contains("\"history\"");
        var roundTripped = objectMapper.readValue(serialized,
            WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryRequest.class);
        assertThat(roundTripped.targetIntent().elementTerms()).containsExactly("告警日志");
    }

    @Test
    void readsRunnerExplorationRoundAndReportFields() throws Exception {
        var response = objectMapper.readValue("""
            {"pageUrl":"https://example.test/#/logs","elements":[],"exploration":{
              "status":"exhausted","rounds":[{
                "round":1,"observation":{"url":"https://example.test/#/logs","fingerprint":"fp-1"},
                "scores":{"pageScore":20,"elementScore":0,"stateScore":0,"totalScore":20,"relevantElements":[],"matched":false},
                "action":{"type":"scroll-page"},
                "actionFailure":{"failureType":"navigation_failed","message":"blocked"}
              }],
              "scores":{"pageScore":20,"elementScore":0,"stateScore":0,"totalScore":20,"relevantElements":[],"matched":false},
              "navigationTrail":["scroll-page"],"failureReason":"round_budget_exhausted","durationMs":123,
              "finalObservation":{"url":"https://example.test/#/logs","fingerprint":"fp-1"},
              "remainingDurationMs":89877,"trailError":"trail unavailable"
            }}
            """, WebAutomationLocatorDiscoveryService.PageLocatorDiscoveryResponse.class);

        assertThat(response.exploration().rounds()).hasSize(1);
        assertThat(response.exploration().rounds().get(0).observation()).isNotNull();
        assertThat(response.exploration().rounds().get(0).action()).isNotNull();
        assertThat(response.exploration().rounds().get(0).actionFailure()).isNotNull();
        assertThat(response.exploration().finalObservation()).isNotNull();
        assertThat(response.exploration().remainingDurationMs()).isEqualTo(89_877L);
        assertThat(response.exploration().trailError()).isEqualTo("trail unavailable");
    }
}
