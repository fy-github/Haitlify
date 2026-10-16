package com.haitlify.aitest.platform.ai.runtime;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.haitlify.aitest.platform.ai.event.AiRunEvent;
import com.haitlify.aitest.platform.ai.event.AiRunEventService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T06 job API 测试：幂等提交返回同一 jobId、参数缺失 400、未知 job 404、取消
 * 幂等（已终态 ALREADY_FINISHED）、审计读取（节点/事件）与 afterSeq 补拉。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiJobControllerTest {

    private static final long PROJECT_ID = 987004L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AiRunEventService eventService;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from ai_runtime_attempts");
        jdbcTemplate.update("delete from ai_runtime_job_nodes");
        jdbcTemplate.update("delete from ai_runtime_jobs");
        jdbcTemplate.update("delete from projects where id = ?", PROJECT_ID);
        jdbcTemplate.update(
            "insert into projects(id, code, name, description, active, created_at, updated_at)"
                + " values (?, ?, ?, '', true, ?, ?)",
            PROJECT_ID, "CTIT-" + PROJECT_ID, "controller-test", new java.sql.Timestamp(System.currentTimeMillis()),
            new java.sql.Timestamp(System.currentTimeMillis()));
    }

    @Test
    void submitIsIdempotentAndInvalidRequestsAreRejected() throws Exception {
        String key = "idem-" + UUID.randomUUID();
        String body = "{"
            + "\"jobType\":\"requirement_analysis\",\"projectId\":" + PROJECT_ID + ",\"actorId\":\"actor-1\","
            + "\"idempotencyKey\":\"" + key + "\",\"inputHash\":\"hash-1\","
            + "\"nodePlan\":[\"step-1\",\"step-2\"]}";

        String first = mockMvc.perform(post("/api/ai/jobs").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").isNotEmpty())
            .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/ai/jobs").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(second).isEqualTo(first);

        mockMvc.perform(post("/api/ai/jobs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"jobType\":\"requirement_analysis\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void unknownJobsAre404AndCancelIsIdempotent() throws Exception {
        mockMvc.perform(get("/api/ai/jobs/job-missing")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/ai/jobs/job-missing/cancel")).andExpect(status().isNotFound());

        String jobId = submitOne();
        mockMvc.perform(post("/api/ai/jobs/" + jobId + "/cancel"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("CANCELLED"));
        // 晚到/重复取消：已终态 ⇒ ALREADY_FINISHED，不损坏终态。
        mockMvc.perform(post("/api/ai/jobs/" + jobId + "/cancel"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.outcome").value("ALREADY_FINISHED"));
    }

    @Test
    void auditReturnsNodesAndEventsWithAfterSeqCatchUp() throws Exception {
        String jobId = submitOne();
        String runId = jobId;
        eventService.emit(new AiRunEvent(runId, Instant.now(), "step_a", "info", "第一事件", null, 0, null,
            "", "", "", 0L, "", "", 0L));
        eventService.emit(new AiRunEvent(runId, Instant.now(), "step_b", "info", "第二事件", null, 0, null,
            "", "", "", 0L, "", "", 0L));

        mockMvc.perform(get("/api/ai/jobs/" + jobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.job.status").value("QUEUED"))
            .andExpect(jsonPath("$.nodes.length()").value(2))
            .andExpect(jsonPath("$.events.length()").value(2));

        String eventsJson = mockMvc.perform(get("/api/ai/jobs/" + jobId + "/events").param("afterSeq", "1"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(eventsJson).contains("第二事件").doesNotContain("第一事件");
    }

    private String submitOne() throws Exception {
        String key = "idem-" + UUID.randomUUID();
        String body = "{"
            + "\"jobType\":\"requirement_analysis\",\"projectId\":" + PROJECT_ID + ",\"actorId\":\"actor-1\","
            + "\"idempotencyKey\":\"" + key + "\",\"inputHash\":\"hash-1\","
            + "\"nodePlan\":[\"step-1\",\"step-2\"]}";
        String response = mockMvc.perform(post("/api/ai/jobs").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        return response.replaceAll(".*\"jobId\":\"([^\"]+)\".*", "$1");
    }
}
