package com.haitlify.aitest.platform.ai.agentic.locator;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 素材库检索 SQL 的驱动级契约钉子。
 *
 * <p>回归背景：ESCAPE 子句曾在文本块里写成两个反斜杠（编译后在运行时只剩一个），
 * MySQL 驱动会把紧随的引号当作被转义字符，连锁吞掉后面的占位符，在真实 MySQL 连接上
 * 抛出 "Parameter index out of range"。该错误只由 MySQL 驱动客户端的解析触发，
 * H2 单元测试对此不敏感，因此这里直接钉运行时字符串形态。
 */
class LocatorKnowledgeSqlContractTest {

    @Test
    void escapeClauseUsesBangCharacterWithoutBackslashAmbiguity() {
        String sql = LocatorKnowledgeService.KNOWLEDGE_ROWS_SQL;
        assertThat(sql).contains("escape '!'");
        assertThat(sql).doesNotContain("escape '\\'");            // 单反斜杠：MySQL 驱动计数错误
        assertThat(sql).doesNotContain("escape '" + (char) 92 + (char) 92 + "'"); // 双反斜杠：H2 拒绝
    }

    @Test
    void placeholderCountIsSixteen() {
        long placeholders = LocatorKnowledgeService.KNOWLEDGE_ROWS_SQL.chars().filter(ch -> ch == '?').count();
        assertThat(placeholders).isEqualTo(16);
    }

    @Test
    void searchCandidatesReadsInIsolatedTransaction() throws Exception {
        Method method = LocatorKnowledgeService.class.getMethod(
            "searchCandidates", Long.class, String.class, String.class, String.class, String.class, String.class);
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(transactional.readOnly()).isTrue();
    }
}
