package com.haitlify.aitest.platform.assistant.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.haitlify.aitest.platform.assistant.compile.AssistantScriptCompiler;

import tools.jackson.databind.ObjectMapper;

/** 人工确认门：未确认不能执行；确认留痕；驳回/改写都被拒绝；跨租户不可见。 */
@SpringBootTest(properties = "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
@ActiveProfiles("test")
class AssistantDraftServiceTest {

    @Autowired
    private AssistantDraftService drafts;

    @Autowired
    private ObjectMapper objectMapper;

    private AssistantScriptCompiler.CaseSpec spec(String summaryText) {
        return new AssistantScriptCompiler.CaseSpec("case-draft", "草稿夹具", "http://127.0.0.1:19399/fixture.html",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "/fixture.html", null),
                new AssistantScriptCompiler.Step("s2", "assertText", "#summary", summaryText)));
    }

    private long tenant() {
        return Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
    }

    private AssistantDraftService.DraftView create(long tenant) {
        return drafts.create(new AssistantDraftService.CreateDraftCommand(tenant, 1L, 42L,
            "docs/superpowers/a0/2026-10-16-a0-isolated-environment-and-legacy-inventory.md §5.5",
            "需求原文：只读夹具断言 3 条", spec("共 3 条"), "web.case.execute", "project:1/case-a1", "rev-1", null));
    }

    @Test
    void unconfirmedDraftCannotBeExecuted() {
        long tenant = tenant();
        AssistantDraftService.DraftView draft = create(tenant);
        assertThat(draft.state()).isEqualTo("DRAFT");
        assertThatThrownBy(() -> drafts.requireConfirmed(draft.draftId(), tenant, spec("共 3 条"), "web.case.execute", "project:1/case-a1", "rev-1", null, objectMapper))
            .isInstanceOf(AssistantDraftService.DraftNotConfirmedException.class)
            .hasMessageContaining("未确认");
    }

    @Test
    void confirmRecordsActorAndIsIdempotent() {
        long tenant = tenant();
        AssistantDraftService.DraftView draft = create(tenant);
        AssistantDraftService.DraftView confirmed = drafts.confirm(draft.draftId(), tenant, 7L);
        assertThat(confirmed.state()).isEqualTo("CONFIRMED");
        assertThat(confirmed.confirmedBy()).isEqualTo(7L);

        AssistantDraftService.DraftView second = drafts.confirm(draft.draftId(), tenant, 8L);
        assertThat(second.confirmedBy()).isEqualTo(7L); // 不覆盖第一个确认人
    }

    @Test
    void confirmedDraftBindsStructureAndRejectsRewrite() {
        long tenant = tenant();
        AssistantDraftService.DraftView draft = create(tenant);
        drafts.confirm(draft.draftId(), tenant, 7L);

        assertThat(drafts.requireConfirmed(draft.draftId(), tenant, spec("共 3 条"), "web.case.execute", "project:1/case-a1", "rev-1", null, objectMapper).draftId())
            .isEqualTo(draft.draftId());
        assertThatThrownBy(() -> drafts.requireConfirmed(draft.draftId(), tenant, spec("共 4 条"), "web.case.execute", "project:1/case-a1", "rev-1", null, objectMapper))
            .isInstanceOf(AssistantDraftService.DraftNotConfirmedException.class)
            .hasMessageContaining("不一致");
    }

    @Test
    void rejectedDraftCannotBeConfirmedAndConfirmedDraftCannotBeSilentlyRejected() {
        long tenant = tenant();
        AssistantDraftService.DraftView draft = create(tenant);
        assertThat(drafts.reject(draft.draftId(), tenant, 7L).state()).isEqualTo("REJECTED");
        assertThatThrownBy(() -> drafts.confirm(draft.draftId(), tenant, 8L))
            .isInstanceOf(AssistantDraftService.DraftNotConfirmedException.class)
            .hasMessageContaining("驳回");

        AssistantDraftService.DraftView confirmed = create(tenant);
        drafts.confirm(confirmed.draftId(), tenant, 7L);
        // 已确认但未执行：允许作废。
        assertThat(drafts.reject(confirmed.draftId(), tenant, 8L).state()).isEqualTo("REJECTED");
    }

    @Test
    void draftsAreNotVisibleAcrossTenants() {
        long tenant = tenant();
        long otherTenant = tenant + 1;
        AssistantDraftService.DraftView draft = create(tenant);

        assertThatThrownBy(() -> drafts.get(draft.draftId(), otherTenant))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> drafts.confirm(draft.draftId(), otherTenant, 7L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> drafts.requireConfirmed(draft.draftId(), otherTenant, spec("共 3 条"), "web.case.execute", "project:1/case-a1", "rev-1", null, objectMapper))
            .isInstanceOf(AssistantDraftService.DraftNotConfirmedException.class);
    }

    @Test
    void requirementDigestChangesWithRequirementText() {
        long tenant = tenant();
        AssistantDraftService.DraftView first = create(tenant);
        AssistantDraftService.DraftView second = drafts.create(new AssistantDraftService.CreateDraftCommand(
            tenant, 1L, 42L, "same-ref", "需求原文：只读夹具断言 3 条", spec("共 3 条"),
            "web.case.execute", "project:1/case-a1", "rev-1", null));
        assertThat(first.requirementDigest()).isNotEqualTo(second.requirementDigest());
    }

    @Test
    void executionTargetMustMatchConfirmedDraft() {
        long tenant = tenant();
        AssistantDraftService.DraftView draft = create(tenant);
        drafts.confirm(draft.draftId(), tenant, 7L);

        assertThatThrownBy(() -> drafts.requireConfirmed(draft.draftId(), tenant, spec("共 3 条"),
            "web.case.execute", "project:other", "rev-1", null, objectMapper))
            .isInstanceOf(AssistantDraftService.DraftNotConfirmedException.class)
            .hasMessageContaining("执行目标");
        assertThatThrownBy(() -> drafts.requireConfirmed(draft.draftId(), tenant, spec("共 3 条"),
            "web.case.delete", "project:1/case-a1", "rev-1", null, objectMapper))
            .isInstanceOf(AssistantDraftService.DraftNotConfirmedException.class)
            .hasMessageContaining("执行目标");
    }

    @Test
    void confirmedDraftBindsInputValuesAndRejectsChangedInputs() {
        long tenant = tenant();
        Map<String, String> inputs = Map.of("keyword", "威胁情报");
        AssistantDraftService.DraftView draft = drafts.create(new AssistantDraftService.CreateDraftCommand(
            tenant, 1L, 42L, "req-inputs", "需求：带输入", spec("共 3 条"), "web.case.execute",
            "project:1/case-a1", "rev-1", inputs));
        assertThat(draft.inputsDigest()).isNotBlank();
        assertThat(draft.inputs()).containsEntry("keyword", "威胁情报");
        drafts.confirm(draft.draftId(), tenant, 7L);

        assertThat(drafts.requireConfirmed(draft.draftId(), tenant, spec("共 3 条"), "web.case.execute",
            "project:1/case-a1", "rev-1", inputs, objectMapper).draftId()).isEqualTo(draft.draftId());
        assertThatThrownBy(() -> drafts.requireConfirmed(draft.draftId(), tenant, spec("共 3 条"),
            "web.case.execute", "project:1/case-a1", "rev-1", Map.of("keyword", "改过的值"), objectMapper))
            .isInstanceOf(AssistantDraftService.DraftNotConfirmedException.class)
            .hasMessageContaining("输入");
    }

    @Test
    void invalidStructureCannotEvenBeDrafted() {
        long tenant = tenant();
        AssistantScriptCompiler.CaseSpec bad = new AssistantScriptCompiler.CaseSpec("case-bad", "非法动作",
            "http://127.0.0.1:19399/fixture.html",
            List.of(new AssistantScriptCompiler.Step("s1", "evaluate", "alert(1)", null)));
        assertThatThrownBy(() -> drafts.create(new AssistantDraftService.CreateDraftCommand(
            tenant, 1L, 42L, "req-bad", "需求", bad, "web.case.execute", "project:1/case-a1", "rev-1", null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("动作");
    }

    @Test
    void secretLookingKeysAreMaskedEvenInCamelCase() {
        long tenant = tenant();
        Map<String, String> inputs = new java.util.LinkedHashMap<>();
        inputs.put("accessKey", "AKIA-plain");
        inputs.put("aws_access_key_id", "AKIA-plain-id");
        inputs.put("keyId", "kid-plain");
        inputs.put("privateKey", "pk-plain");
        inputs.put("PWD", "pw-plain");
        inputs.put("keyword", "业务词");
        AssistantDraftService.DraftView draft = drafts.create(new AssistantDraftService.CreateDraftCommand(
            tenant, 1L, 42L, "req-mask", "需求", spec("共 3 条"), "web.case.execute",
            "project:1/case-a1", "rev-1", inputs));
        assertThat(draft.inputs()).containsEntry("accessKey", "***").containsEntry("privateKey", "***")
            .containsEntry("PWD", "***").containsEntry("aws_access_key_id", "***")
            .containsEntry("keyId", "***").containsEntry("keyword", "业务词");
    }

    @Test
    void reviseSupersedesOldDraftAndRequiresFreshConfirmation() {
        long tenant = tenant();
        AssistantDraftService.DraftView v1 = create(tenant);
        drafts.confirm(v1.draftId(), tenant, 7L);

        AssistantDraftService.DraftView v2 = drafts.revise(v1.draftId(), tenant, 8L, spec("共 4 条"), null);
        assertThat(v2.draftId()).isNotEqualTo(v1.draftId());
        assertThat(v2.revision()).isEqualTo(2);
        assertThat(v2.state()).isEqualTo("DRAFT");
        assertThat(drafts.get(v1.draftId(), tenant).state()).isEqualTo("SUPERSEDED");

        // 旧修订既不能确认也不能执行；新修订必须先确认。
        assertThatThrownBy(() -> drafts.confirm(v1.draftId(), tenant, 9L))
            .isInstanceOf(AssistantDraftService.DraftNotConfirmedException.class)
            .hasMessageContaining("取代");
        assertThatThrownBy(() -> drafts.requireConfirmed(v1.draftId(), tenant, spec("共 3 条"),
            "web.case.execute", "project:1/case-a1", "rev-1", null, objectMapper))
            .isInstanceOf(AssistantDraftService.DraftNotConfirmedException.class)
            .hasMessageContaining("取代");
        assertThatThrownBy(() -> drafts.requireConfirmed(v2.draftId(), tenant, spec("共 4 条"),
            "web.case.execute", "project:1/case-a1", "rev-1", null, objectMapper))
            .isInstanceOf(AssistantDraftService.DraftNotConfirmedException.class)
            .hasMessageContaining("未确认");

        drafts.confirm(v2.draftId(), tenant, 8L);
        assertThat(drafts.requireConfirmed(v2.draftId(), tenant, spec("共 4 条"), "web.case.execute",
            "project:1/case-a1", "rev-1", null, objectMapper).revision()).isEqualTo(2);
    }

    @Test
    void listShowsLatestRevisionOnlyAndHistoryKeepsBoth() {
        long tenant = tenant();
        AssistantDraftService.DraftView v1 = create(tenant);
        AssistantDraftService.DraftView other = create(tenant);
        AssistantDraftService.DraftView v2 = drafts.revise(v1.draftId(), tenant, 8L, spec("共 5 条"), null);

        var latest = drafts.list(tenant, 1L, null, 50, 0);
        assertThat(latest).extracting(AssistantDraftService.DraftView::draftId)
            .contains(v2.draftId(), other.draftId())
            .doesNotContain(v1.draftId());
        assertThat(latest).extracting(AssistantDraftService.DraftView::revision).containsOnly(1, 2);

        var history = drafts.revisions(v1.draftId(), tenant);
        assertThat(history).hasSize(2);
        assertThat(history.get(0).revision()).isEqualTo(1);
        assertThat(history.get(0).state()).isEqualTo("SUPERSEDED");
        assertThat(history.get(1).revision()).isEqualTo(2);
        assertThat(history.get(1).state()).isEqualTo("DRAFT");

        assertThat(drafts.list(tenant, 1L, "SUPERSEDED", 50, 0)).isEmpty();
    }

    @Test
    void requirementImagesAreValidatedAndBoundIntoTheDigest() {
        String base = "联系信息列表：\n![列表](/api/attachments/1)\n";
        var first = drafts.create(new AssistantDraftService.CreateDraftCommand(tenant(), 1L, 5L,
            "IMG-1", base, spec("共 3 条"), "web.case.execute", "project:1/case", "rev-1", null));
        var second = drafts.create(new AssistantDraftService.CreateDraftCommand(tenant(), 1L, 5L,
            "IMG-1", base.replace("/api/attachments/1", "/api/attachments/2"), spec("共 3 条"),
            "web.case.execute", "project:1/case", "rev-1", null));
        assertThat(second.requirementDigest()).isNotEqualTo(first.requirementDigest());

        assertThatThrownBy(() -> drafts.create(new AssistantDraftService.CreateDraftCommand(tenant(), 1L, 5L,
            "IMG-2", "![x](data:image/png;base64,AAAA)", spec("共 3 条"), "web.case.execute",
            "project:1/case", "rev-1", null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("data:");

        assertThatThrownBy(() -> drafts.create(new AssistantDraftService.CreateDraftCommand(tenant(), 1L, 5L,
            "IMG-3", "![x](http://188.1.2.3/a.png)", spec("共 3 条"), "web.case.execute",
            "project:1/case", "rev-1", null)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("https");
    }
}
