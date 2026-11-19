package com.haitlify.aitest.platform.assistant.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.haitlify.aitest.platform.assistant.compile.AssistantScriptCompiler;
import com.haitlify.aitest.platform.assistant.draft.AssistantDraftService;

import tools.jackson.databind.ObjectMapper;

/**
 * A2 覆盖/修订量统计：报告要能回答「这次确认的需求含多少图表」「人改了几轮、改了什么」，
 * 依据是创建草稿时固化的需求摘要与修订链结构差，不是模型自述。
 */
@SpringBootTest(properties = "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
@ActiveProfiles("test")
class AssistantReportCoverageTest {

    private static final String REQUIREMENT = """
        需求：联系人可新增并按名称搜索。
        | 字段 | 必填 |
        | --- | --- |
        | 名称 | 是 |
        | 电话 | 否 |
        ![列表页](https://example.test/contacts.png)
        ![详情页](/api/requirements/assets/detail.png)
        """;

    @Autowired
    private AssistantDraftService drafts;

    @Autowired
    private AssistantReportService reports;

    @Autowired
    private ObjectMapper objectMapper;

    private AssistantScriptCompiler.CaseSpec spec(String assertionTarget) {
        return new AssistantScriptCompiler.CaseSpec("case-coverage", "覆盖夹具",
            "http://127.0.0.1:19399/fixture.html",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "/fixture.html", null),
                new AssistantScriptCompiler.Step("s2", "assertVisible", assertionTarget, null)));
    }

    private long tenant() {
        return Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
    }

    @Test
    void recordsRequirementMediaAndManualRevisionDelta() {
        long tenant = tenant();
        AssistantDraftService.DraftView created = drafts.create(new AssistantDraftService.CreateDraftCommand(
            tenant, 1L, 5L, "覆盖测试需求", REQUIREMENT, spec("#summary"),
            "web.case.execute", "project:1/case-coverage", "rev-1", null));

        // 需求规模在创建时就固化：2 张图、1 张表，行数/字符数为原文事实。
        assertThat(created.requirementSummary()).containsEntry("tableCount", 1)
            .containsEntry("imageCount", 2);
        assertThat((List<Object>) created.requirementSummary().get("imageRefs"))
            .containsExactly((Object) "https://example.test/contacts.png", "/api/requirements/assets/detail.png");

        AssistantDraftService.DraftView revised = drafts.revise(created.draftId(), tenant, 5L,
            spec("#summary-v2"), null);
        assertThat(revised.revision()).isEqualTo(2);
        // 修订不重算需求摘要：需求原文没变，摘要必须与首版一致。
        assertThat(revised.requirementSummary()).isEqualTo(created.requirementSummary());

        Map<String, Object> coverage = reports.coverageForDraft(created.draftId(), tenant);
        assertThat(coverage).containsEntry("revisionCount", 2).containsEntry("manualRevisionCount", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> requirement = (Map<String, Object>) coverage.get("requirement");
        assertThat(requirement).containsEntry("tableCount", 1).containsEntry("imageCount", 2);
        @SuppressWarnings("unchecked")
        Map<String, Object> delta = (Map<String, Object>) coverage.get("revisionDelta");
        assertThat(delta).containsEntry("stepsAdded", 1).containsEntry("stepsRemoved", 1);
        assertThat(((List<Object>) delta.get("addedSteps")).toString()).contains("assertVisible|#summary-v2");
        assertThat(((List<Object>) delta.get("removedSteps")).toString()).contains("assertVisible|#summary");
    }

    @Test
    void countsValueOnlyRevisionsSoManualEditsAreNotReportedAsNoChange() {
        long tenant = tenant();
        AssistantScriptCompiler.CaseSpec before = new AssistantScriptCompiler.CaseSpec("case-value", "改值夹具",
            "http://127.0.0.1:19399/fixture.html",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "/fixture.html", null),
                new AssistantScriptCompiler.Step("s2", "assertText", "#summary", "旧文案")));
        AssistantScriptCompiler.CaseSpec after = new AssistantScriptCompiler.CaseSpec("case-value", "改值夹具",
            "http://127.0.0.1:19399/fixture.html",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "/fixture.html", null),
                new AssistantScriptCompiler.Step("s2", "assertText", "#summary", "新文案")));
        AssistantDraftService.DraftView created = drafts.create(new AssistantDraftService.CreateDraftCommand(
            tenant, 1L, 5L, "改值需求", "需求正文", before,
            "web.case.execute", "project:1/case-value", "rev-1", null));
        drafts.revise(created.draftId(), tenant, 5L, after, null);

        Map<String, Object> coverage = reports.coverageForDraft(created.draftId(), tenant);
        @SuppressWarnings("unchecked")
        Map<String, Object> delta = (Map<String, Object>) coverage.get("revisionDelta");

        // 签名没变、只有 value 变了：不能显示成 0 改动（复核 D7）。
        assertThat(delta).containsEntry("stepsAdded", 0).containsEntry("stepsRemoved", 0)
            .containsEntry("stepsChanged", 1);
    }

    @Test
    void disclosesTableCoverageAsUnverifiedInsteadOfSilentlyClaimingFullCoverage() {
        long tenant = tenant();
        AssistantDraftService.DraftView created = drafts.create(new AssistantDraftService.CreateDraftCommand(
            tenant, 1L, 5L, "表格需求", "| a | b |\n| --- | --- |\n| 1 | 2 |", spec("#summary"),
            "web.case.execute", "project:1/case-coverage", "rev-1", null));

        assertThat(created.requirementSummary()).containsEntry("tableCount", 1);
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) created.requirementSummary().get("warnings");
        // 表格被计入但内容未由确定性解析器逐格核验：必须显式写在摘要里。
        assertThat(warnings).anyMatch(warning -> warning.contains("markdown table"));
    }
}
