package com.haitlify.aitest.platform.assistant.draft;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 输入测试数据不能明文落库：库里应是 enc:v1: 密文，读出来仍是明文（调用方无感）。
 * 历史明文行必须继续可读（自描述前缀）。
 */
@SpringBootTest(properties = "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
@ActiveProfiles("test")
class AssistantDraftInputsEncryptionTest {

    @Autowired
    private AssistantDraftRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void inputsAreEncryptedAtRestAndDecryptedOnRead() {
        String draftId = repository.insert(9_100_001L, 1L, "req://enc", "a".repeat(64), "{}", 7L,
            "web.contact.create", "project:1/contacts", "rev-1",
            "{\"phone\":\"13800000000\"}", "b".repeat(64), 1, null, "{}");
        try {
            String stored = jdbc.queryForObject(
                "select inputs_json from assistant_draft where draft_id = ?", String.class, draftId);
            assertThat(stored).startsWith("enc:v1:").doesNotContain("13800000000");

            AssistantDraftRepository.DraftRecord record = repository.find(draftId, 9_100_001L).orElseThrow();
            assertThat(record.inputsJson()).contains("13800000000");
        } finally {
            jdbc.update("delete from assistant_draft where draft_id = ?", draftId);
        }
    }

    @Test
    void legacyPlaintextRowsRemainReadable() {
        Instant now = Instant.now();
        String draftId = UUID.randomUUID().toString();
        jdbc.update("insert into assistant_draft(draft_id, tenant_id, project_id, requirement_ref,"
            + " requirement_digest, draft_json, state, created_by, created_at, action_code, target_ref,"
            + " target_revision, inputs_json, inputs_digest, revision)"
            + " values (?, 9100002, 1, 'req://legacy-plain', ?, '{}', 'DRAFT', 7, ?,"
            + " 'web.contact.create', 'project:1/contacts', 'rev-1', ?, ?, 1)",
            draftId, "a".repeat(64), now, "{\"phone\":\"13900000000\"}", "b".repeat(64));
        try {
            assertThat(repository.find(draftId, 9_100_002L).orElseThrow().inputsJson())
                .contains("13900000000");
        } finally {
            jdbc.update("delete from assistant_draft where draft_id = ?", draftId);
        }
    }
}
