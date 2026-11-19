package com.haitlify.aitest.platform.assistant.dispatch;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.haitlify.aitest.platform.assistant.compile.AssistantScriptCompiler;
import com.haitlify.aitest.platform.assistant.draft.AssistantDraftService;

/** 开了 Hermes 但执行单元不可用：必须拒绝受理，不能静默退化成「跳过助手直接跑脚本」。 */
@SpringBootTest(properties = {
    "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=",
    "app.assistant.hermes.enabled=true",
    "app.assistant.hermes.command="
})
@ActiveProfiles("test")
class AssistantTaskHermesUnavailableTest {

    @Autowired
    private AssistantTaskService tasks;

    @Autowired
    private AssistantDraftService drafts;

    @Test
    void rejectsWhenHermesEnabledButUnavailable() {
        long tenant = Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L) + 1_000_000L;
        AssistantScriptCompiler.CaseSpec spec = new AssistantScriptCompiler.CaseSpec("case-a1", "夹具",
            "http://127.0.0.1:19399/fixture.html",
            List.of(new AssistantScriptCompiler.Step("s1", "goto", "/fixture.html", null),
                new AssistantScriptCompiler.Step("s2", "assertVisible", "#summary", null)));
        String draftId = drafts.confirm(drafts.create(new AssistantDraftService.CreateDraftCommand(
            tenant, 1L, 1L, "unavailable 测试需求", "夹具", spec, "web.case.execute", "project:1/case-a1", "rev-1", null)).draftId(), tenant, 1L).draftId();
        assertThatThrownBy(() -> tasks.run(new AssistantTaskService.TaskRequest(tenant, 1L, 1L,
            UUID.randomUUID().toString(), draftId, "web.case.execute", "project:1/case-a1", "rev-1", spec, null)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("拒绝受理");
    }
}
