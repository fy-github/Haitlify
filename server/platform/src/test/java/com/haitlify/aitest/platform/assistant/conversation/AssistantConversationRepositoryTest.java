package com.haitlify.aitest.platform.assistant.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import com.zaxxer.hikari.HikariDataSource;

/**
 * 账本仓储的 SQL 语义：受理落账的「0 行即失败」、陈旧扫描的「本 JVM 占有的行不扫」、
 * 以及毫秒并列时定序与被判据分开。
 *
 * <p>这里跑的是<b>真实的迁移链（V1..最新）+ 真实的 JDBC</b>，不是内存假仓储：上面三条结论全都
 * 落在 SQL 的影响行数/where 条件上，假仓储只能证明「我以为的语义」，证明不了数据库确实这么执行。
 */
class AssistantConversationRepositoryTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");

    /** 一条对话 + 一个仓储，每个用例独立的 H2 内存库（互不污染）。 */
    private record Fixture(JdbcTemplate jdbc, AssistantConversationRepository repository, String conversationId) {}

    private static Fixture fixture() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:h2:mem:conversation-repository-" + UUID.randomUUID()
            + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUsername("sa");
        dataSource.setPassword("");
        dataSource.setMaximumPoolSize(4);
        org.flywaydb.core.Flyway.configure()
            .dataSource(dataSource)
            .locations("filesystem:" + MIGRATIONS.toAbsolutePath())
            .load()
            .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String conversationId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update("insert into assistant_conversations(id,tenant_id,project_id,actor_id,request_id,title,"
                + "stored_session_id,created_at,updated_at) values (?,?,?,?,?,?,?,?,?)",
            conversationId, 1L, 1L, 7L, "create-1", null, null, now, now);
        AssistantConversationRepository repository =
            new AssistantConversationRepository(jdbc, new DataSourceTransactionManager(dataSource));
        return new Fixture(jdbc, repository, conversationId);
    }

    private static String seed(Fixture fixture, String requestId, String state, Instant when) {
        return seed(fixture, requestId, state, when, UUID.randomUUID().toString());
    }

    private static String seed(Fixture fixture, String requestId, String state, Instant when, String id) {
        fixture.jdbc().update("insert into assistant_conversation_messages(id,conversation_id,actor_id,request_id,"
                + "role,body_text,state,turn_id,created_at,updated_at) values (?,?,?,?,?,?,?,?,?,?)",
            id, fixture.conversationId(), 7L, requestId, "USER", "body-" + requestId, state, id, when, when);
        return id;
    }

    private static String state(Fixture fixture, String id) {
        return fixture.jdbc().queryForObject("select state from assistant_conversation_messages where id=?",
            String.class, id);
    }

    private static Instant acceptedAt(Fixture fixture, String id) {
        return fixture.jdbc().queryForObject("select accepted_at from assistant_conversation_messages where id=?",
            Instant.class, id);
    }

    private static String storedSession(Fixture fixture) {
        return fixture.jdbc().queryForObject("select stored_session_id from assistant_conversations where id=?",
            String.class, fixture.conversationId());
    }

    // ------------------------------------------------------------ N-b 受理落账

    /** 对照：正常受理时两者同一条事务落账。 */
    @Test
    void markAcceptedRecordsTheAcceptanceOnASubmittedRow() {
        Fixture fixture = fixture();
        String id = seed(fixture, "req-1", "SUBMITTED", Instant.now());

        fixture.repository().markAccepted(id, fixture.conversationId(), "turn-ref-1", "stored-1");

        assertThat(acceptedAt(fixture, id)).isNotNull();
        assertThat(storedSession(fixture)).isEqualTo("stored-1");
        assertThat(fixture.repository().accepted(id)).isTrue();
    }

    /**
     * 行已经不在 SUBMITTED（被陈旧归一化或收尾推走）时，受理证据根本写不进去。
     * 静默放过会得到「句柄提交了、受理证据没落账」的半截状态，所以必须是失败 + 整体回滚。
     */
    @Test
    void markAcceptedFailsLoudlyWhenTheRowIsNoLongerSubmitted() {
        Fixture fixture = fixture();
        String id = seed(fixture, "req-1", "SUBMITTED", Instant.now());
        fixture.repository().updateMessageStateIfSubmitted(id, "UNKNOWN", "stale");

        assertThatThrownBy(() -> fixture.repository()
            .markAccepted(id, fixture.conversationId(), "turn-ref-1", "stored-late"))
            .as("0 行不得静默成功").isInstanceOf(RuntimeException.class);

        assertThat(fixture.repository().accepted(id)).isFalse();
        assertThat(acceptedAt(fixture, id)).isNull();
        assertThat(storedSession(fixture)).as("受理没落账时句柄也不得单独提交").isNull();
        assertThat(state(fixture, id)).isEqualTo("UNKNOWN");
    }

    /** 同一行第二次受理落账 = 两次受理证据：同样必须响，不能悄悄当成幂等。 */
    @Test
    void markAcceptedFailsWhenTheAcceptanceWasAlreadyRecorded() {
        Fixture fixture = fixture();
        String id = seed(fixture, "req-1", "SUBMITTED", Instant.now());
        fixture.repository().markAccepted(id, fixture.conversationId(), "turn-ref-1", "stored-1");
        Instant first = acceptedAt(fixture, id);

        assertThatThrownBy(() -> fixture.repository()
            .markAccepted(id, fixture.conversationId(), "turn-ref-2", "stored-2"))
            .isInstanceOf(RuntimeException.class);

        assertThat(acceptedAt(fixture, id)).as("第一次的证据不被覆盖").isEqualTo(first);
        assertThat(storedSession(fixture)).as("失败的事务不得改句柄").isEqualTo("stored-1");
        assertThat(fixture.jdbc().queryForObject("select upstream_turn_ref from assistant_conversation_messages"
            + " where id=?", String.class, id)).isEqualTo("turn-ref-1");
    }

    // ------------------------------------------------------------ N-a 陈旧扫描

    /** 陈旧扫描只看时间是不够的：本 JVM 仍占有的行哪怕很老也不得被判死。 */
    @Test
    void theStaleSweepNeverTouchesRowsTheJvmStillOwns() {
        Fixture fixture = fixture();
        Instant ancient = Instant.now().minusSeconds(6 * 3600);
        String owned = seed(fixture, "req-owned", "SUBMITTED", ancient);
        String orphan = seed(fixture, "req-orphan", "SUBMITTED", ancient.minusSeconds(1));
        String completed = seed(fixture, "req-completed", "COMPLETED", ancient);

        fixture.repository().normalizeStaleSubmitted(fixture.conversationId(), Instant.now().minusSeconds(60),
            "stale", Set.of(owned));

        assertThat(state(fixture, owned)).as("本 JVM 仍占有的行不得被扫掉").isEqualTo("SUBMITTED");
        assertThat(state(fixture, orphan)).as("没有主的孤儿才是扫描目标").isEqualTo("UNKNOWN");
        assertThat(state(fixture, completed)).as("终态永不被改写").isEqualTo("COMPLETED");
    }

    /** 空占有集等价于旧行为（只看时间）：调用方给空集就是不排除任何行。 */
    @Test
    void anEmptyOwnedSetSweepsPurelyByTime() {
        Fixture fixture = fixture();
        String stale = seed(fixture, "req-stale", "SUBMITTED", Instant.now().minusSeconds(3600));
        String fresh = seed(fixture, "req-fresh", "SUBMITTED", Instant.now());

        fixture.repository().normalizeStaleSubmitted(fixture.conversationId(), Instant.now().minusSeconds(60),
            "stale", Set.of());

        assertThat(state(fixture, stale)).isEqualTo("UNKNOWN");
        assertThat(state(fixture, fresh)).isEqualTo("SUBMITTED");
    }

    // ------------------------------------------------------------ N-d 毫秒并列

    /**
     * {@code created_at} 完全并列时：{@code (created_at desc, id desc)} 是确定的全序（这里挑到 id 更大的
     * 那一行），但它<b>不代表写入先后</b>，所以对账判定不能只看这一行——它必须看「还有没有开着的行」。
     */
    @Test
    void millisecondTiesAreOrderedDeterministicallyAndNeverDecideTheReconcileGate() {
        Fixture fixture = fixture();
        Instant tie = Instant.now();
        seed(fixture, "req-open", "SUBMITTED", tie, "aaa-open");
        seed(fixture, "req-closed", "UNKNOWN", tie, "zzz-closed");

        String last = fixture.repository().findLastUserMessage(fixture.conversationId(), "no-such-row")
            .orElseThrow().id();

        assertThat(last).as("并列时按 id 文本定序：结果确定、可重复").isEqualTo("zzz-closed");
        assertThat(fixture.repository().countOtherOpenTurns(fixture.conversationId(), "no-such-row"))
            .as("另一行仍开着，账本就不能证实上一轮结束——与挑到哪一行无关").isEqualTo(1);
    }

    /** 全都结束（UNKNOWN/FAILED/COMPLETED）时才允许放闸。 */
    @Test
    void otherOpenTurnsIsZeroOnlyWhenEveryOtherRowIsTerminal() {
        Fixture fixture = fixture();
        seed(fixture, "req-1", "UNKNOWN", Instant.now().minusSeconds(2));
        seed(fixture, "req-2", "FAILED", Instant.now().minusSeconds(1));
        seed(fixture, "req-3", "COMPLETED", Instant.now());

        assertThat(fixture.repository().countOtherOpenTurns(fixture.conversationId(), "req-self")).isZero();

        seed(fixture, "req-4", "SUBMITTED", Instant.now());

        assertThat(fixture.repository().countOtherOpenTurns(fixture.conversationId(), "req-self")).isEqualTo(1);
    }
}
