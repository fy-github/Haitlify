package com.haitlify.aitest.platform.ai.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T04 端点：POST 执行预检（只读默认）、GET 读取已存报告与新鲜度、未注册 profile 返回 400。
 * 探针指向死端口（127.0.0.1:1）——全部如实记 unknown/unsupported，不冒充通过。
 */
@SpringBootTest(properties = "APP_SECRET_KEY=MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiCapabilityCheckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.haitlify.aitest.platform.security.SecretCipher secretCipher;

    private String insertProfile() {
        String connectionId = "conn-" + UUID.randomUUID().toString().substring(0, 8);
        String profileId = "profile-" + UUID.randomUUID().toString().substring(0, 8);
        Timestamp now = Timestamp.from(Instant.now());
        // 存密文引用（生产语义：credential_ref 是引用；材料化由 SecretCipher 完成）。
        String credentialRef = secretCipher.encrypt("test-key");
        jdbcTemplate.update(
            "insert into ai_connections(connection_id, version, base_url, credential_ref, protocol, enabled, created_at, updated_at) "
                + "values (?, 1, ?, ?, 'chat_completions', true, ?, ?)",
            connectionId, "http://127.0.0.1:1/v1", credentialRef, now, now);
        jdbcTemplate.update(
            "insert into ai_model_profiles(profile_id, version, connection_id, model_id, capabilities_json, parameter_policy_json, verification_json, created_at, updated_at) "
                + "values (?, 1, ?, 'model-x', '{\"capabilities\":[\"TEXT\",\"JSON_OBJECT\"]}', '{}', '{}', ?, ?)",
            profileId, connectionId, now, now);
        return profileId;
    }

    @Test
    void postRunsProbesAndGetReturnsStoredReportState() throws Exception {
        String profileId = insertProfile();

        mockMvc.perform(post("/api/ai/capability-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"" + profileId + "\",\"profileVersion\":1,\"persist\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.profileId").value(profileId))
            .andExpect(jsonPath("$.overall").value("unknown"))
            .andExpect(jsonPath("$.probes.length()").value(6))
            .andExpect(jsonPath("$.probes[0].status").value("unknown"))
            .andExpect(jsonPath("$.probes[3].status").value("unsupported"))
            .andExpect(jsonPath("$.probes[5].probe").value("streaming"))
            .andExpect(jsonPath("$.probes[5].status").value("unsupported"))
            .andExpect(jsonPath("$.configHash").isNotEmpty());

        mockMvc.perform(get("/api/ai/capability-check")
                .param("profileId", profileId)
                .param("profileVersion", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fresh").value(false))
            .andExpect(jsonPath("$.report").doesNotExist());
    }

    @Test
    void unregisteredProfileIsRejectedWithBadRequest() throws Exception {
        mockMvc.perform(post("/api/ai/capability-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"profileId\":\"missing-profile\",\"profileVersion\":1,\"persist\":false}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("profile not registered")));
    }
}
