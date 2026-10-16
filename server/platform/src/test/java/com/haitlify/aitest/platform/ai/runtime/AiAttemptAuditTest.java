package com.haitlify.aitest.platform.ai.runtime;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T06 attempt 审计测试：请求前落库、返回后独立 finalize、业务事务回滚不得抹掉
 * 错误记录（REQUIRES_NEW）、重复 finalize 被拒、允许摘要脱敏、usage unknown
 * 语义与按 job 的顺序读取。
 */
@SpringBootTest
@ActiveProfiles("test")
class AiAttemptAuditTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AiAttemptRepository attemptsBean;

    private AiAttemptRepository attempts;

    @BeforeEach
    void setUp() {
        attempts = attemptsBean;
        jdbcTemplate.update("delete from ai_runtime_attempts");
    }

    @Test
    void beanWiringIsPresentInTheContext() {
        assertThat(attemptsBean).isNotNull();
    }

    @Test
    void attemptIsSavedBeforeTheRequestAndFinalizedAfter() {
        String attemptId = attempts.begin("job-1", "node-1", "run-1", "生成需求分析（1 分片）", "route:model-x", "rev-9");
        AiAttemptRepository.Attempt pending = attempts.find(attemptId).orElseThrow();
        assertThat(pending.status()).isEqualTo(AiAttemptRepository.STATUS_PENDING);
        assertThat(pending.requestHash()).isEqualTo(AiAttemptRepository.sha256("生成需求分析（1 分片）"));
        assertThat(pending.startedAt()).isNotNull();
        assertThat(pending.finishedAt()).isNull();

        assertThat(attempts.finalize(attemptId, new AiAttemptRepository.AttemptOutcome(
            AiAttemptRepository.STATUS_SUCCEEDED, 200, "pr-1", "", "", true, 10, 20, 120L, 500L))).isTrue();
        AiAttemptRepository.Attempt done = attempts.find(attemptId).orElseThrow();
        assertThat(done.status()).isEqualTo(AiAttemptRepository.STATUS_SUCCEEDED);
        assertThat(done.httpStatus()).isEqualTo(200);
        assertThat(done.providerRequestId()).isEqualTo("pr-1");
        assertThat(done.inputTokens()).isEqualTo(10);
        assertThat(done.outputTokens()).isEqualTo(20);
        assertThat(done.ttfbMs()).isEqualTo(120L);
        assertThat(done.durationMs()).isEqualTo(500L);
        assertThat(done.finishedAt()).isNotNull();
    }

    @Test
    void businessRollbackDoesNotEraseTheAttempt() {
        // 业务事务失败回滚；attempt 在 REQUIRES_NEW 里已独立提交，错误记录不得被抹掉。
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            attempts.begin("job-2", "node-2", "run-2", "请求摘要", "route", "rev-1");
            throw new IllegalStateException("business failure after begin");
        })).isInstanceOf(IllegalStateException.class);

        List<AiAttemptRepository.Attempt> survivors = attempts.listByJob("job-2");
        assertThat(survivors).hasSize(1);
        assertThat(survivors.get(0).status()).isEqualTo(AiAttemptRepository.STATUS_PENDING);
    }

    @Test
    void doubleFinalizeIsRejectedAndFirstOutcomeStands() {
        String attemptId = attempts.begin("job-3", "node-1", "run-3", "req", null, "rev-1");
        assertThat(attempts.finalize(attemptId, new AiAttemptRepository.AttemptOutcome(
            AiAttemptRepository.STATUS_FAILED, 502, "pr-3", "UPSTREAM_ERROR", "UPSTREAM_ERROR", false, null, null, null, 900L)))
            .isTrue();
        assertThat(attempts.finalize(attemptId, new AiAttemptRepository.AttemptOutcome(
            AiAttemptRepository.STATUS_SUCCEEDED, 200, "pr-overwrite", "", "", true, 1, 1, null, 1L))).isFalse();
        AiAttemptRepository.Attempt attempt = attempts.find(attemptId).orElseThrow();
        assertThat(attempt.status()).isEqualTo(AiAttemptRepository.STATUS_FAILED);
        assertThat(attempt.providerRequestId()).isEqualTo("pr-3");
        assertThat(attempt.usageKnown()).isFalse();
    }

    @Test
    void secretsAndRawTextAreNeverStoredInSummaries() {
        String fakeKey = "sk-" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0";
        String attemptId = attempts.begin("job-4", "node-1", "run-4",
            "调用 chatanalysis 使用 key=" + fakeKey + " 生成", null, "rev-1");
        assertThat(attempts.find(attemptId).orElseThrow().requestHash())
            .isEqualTo(AiAttemptRepository.sha256("调用 chatanalysis 使用 key=" + fakeKey + " 生成"));

        String storedSummary = jdbcTemplate.queryForObject(
            "select request_summary_json from ai_runtime_attempts where attempt_id = ?", String.class, attemptId);
        assertThat(storedSummary).doesNotContain(fakeKey);
        assertThat(storedSummary).contains("***");
        assertThat(AiAttemptRepository.maskAllowedSummary(null)).isNull();
        assertThat(AiAttemptRepository.maskAllowedSummary("short ref")).isEqualTo("short ref");
        assertThat(AiAttemptRepository.maskAllowedSummary("a".repeat(40))).isEqualTo("***");
        // C-10 边界外扩：含 ./+/= 分隔的 27 字符密钥形态样本必须打码；24 字符起生效；
        // 20 字符以下仍是已知残余边界（文档化，非首防——首防是摘要本身不含密钥）。
        assertThat(AiAttemptRepository.maskAllowedSummary("AbC.123/xyz+QWE=abcDEF.789"))
            .isEqualTo("***");
        assertThat(AiAttemptRepository.maskAllowedSummary("Zx9Yw8Vu7Ts6Rq5Po4Nm3Lk2"))
            .isEqualTo("***");
        assertThat(AiAttemptRepository.maskAllowedSummary("abcdefghij1234567890")).isEqualTo("abcdefghij1234567890");
    }

    @Test
    void usageUnknownIsCarriedHonestly() {
        String attemptId = attempts.begin("job-5", "node-1", "run-5", "req", null, "rev-1");
        assertThat(attempts.finalize(attemptId, new AiAttemptRepository.AttemptOutcome(
            AiAttemptRepository.STATUS_FAILED, 503, "", "UPSTREAM_ERROR", "UPSTREAM_ERROR", false, null, null, null, 30L)))
            .isTrue();
        AiAttemptRepository.Attempt attempt = attempts.find(attemptId).orElseThrow();
        assertThat(attempt.usageKnown()).isFalse();
        assertThat(attempt.inputTokens()).isNull();
        assertThat(attempt.outputTokens()).isNull();
    }

    @Test
    void attemptsAreListedInInsertionOrderPerJob() {
        String jobId = "job-" + UUID.randomUUID();
        String first = attempts.begin(jobId, "node-1", "run-6", "first", null, "rev-1");
        String second = attempts.begin(jobId, "node-1", "run-6", "second", null, "rev-1");
        assertThat(attempts.listByJob(jobId)).extracting(AiAttemptRepository.Attempt::attemptId)
            .containsExactly(first, second);
        assertThat(attempts.listByJob("job-none")).isEmpty();
    }
}
