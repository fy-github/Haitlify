package com.haitlify.aitest.platform.ai.analysis.chunk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class AiChunkPlannerTest {

    private final AiChunkPlanner planner = new AiChunkPlanner();

    @Test
    void markdownHeadingsProduceSeparateChunks() {
        List<AiPlannedAnalysisChunk> chunks = planner.plan(new AiChunkPlanningRequest(
            List.of(new AiChunkSource("source-1", "doc-1", "", "# 登录\n用户输入账号密码。\n## 权限\n管理员查看用户列表。")),
            200,
            10,
            "all"
        ));

        assertEquals(2, chunks.size());
        assertEquals("登录", chunks.get(0).section());
        assertEquals("权限", chunks.get(1).section());
        assertEquals(List.of("source-1"), chunks.get(0).sourceChunkIds());
        assertEquals("planned", chunks.get(0).status());
    }

    @Test
    void tableRowsStayTogether() {
        List<AiPlannedAnalysisChunk> chunks = planner.plan(new AiChunkPlanningRequest(
            List.of(new AiChunkSource("source-1", "doc-1", "", "# 字段规则\n| 字段 | 规则 |\n| 名称 | 必填 |\n| 编号 | 唯一 |\n补充说明。")),
            8,
            10,
            "all"
        ));

        assertTrue(chunks.stream().anyMatch(chunk ->
            chunk.contentText().contains("| 名称 | 必填 |") && chunk.contentText().contains("| 编号 | 唯一 |")
        ));
    }

    @Test
    void veryLongSectionsSplitBySize() {
        String longContent = "# 长章节\n" + "项目名称必须校验唯一。\n".repeat(30);

        List<AiPlannedAnalysisChunk> chunks = planner.plan(new AiChunkPlanningRequest(
            List.of(new AiChunkSource("source-1", "doc-1", "", longContent)),
            24,
            20,
            "all"
        ));

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> "长章节".equals(chunk.section())));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.tokenEstimate() <= 24));
    }

    @Test
    void oversizedSingleLinesAreSplitBySize() {
        String longLine = "退款状态需要回补并通知用户。".repeat(400);

        List<AiPlannedAnalysisChunk> chunks = planner.plan(new AiChunkPlanningRequest(
            List.of(new AiChunkSource("source-1", "doc-1", "", "# 超长行\n" + longLine)),
            80,
            100,
            "all"
        ));

        assertTrue(chunks.size() > 1);
        assertTrue(chunks.stream().allMatch(chunk -> "超长行".equals(chunk.section())));
        assertTrue(chunks.stream().allMatch(chunk -> chunk.tokenEstimate() <= 80));
    }

    @Test
    void skippedChunksAreMarkedWhenMaxAnalysisChunksIsExceeded() {
        List<AiPlannedAnalysisChunk> chunks = planner.plan(new AiChunkPlanningRequest(
            List.of(new AiChunkSource("source-1", "doc-1", "", "# A\n内容 A\n# B\n内容 B\n# C\n内容 C")),
            200,
            2,
            "all"
        ));

        assertEquals(3, chunks.size());
        assertEquals("planned", chunks.get(0).status());
        assertEquals("planned", chunks.get(1).status());
        assertEquals("skipped", chunks.get(2).status());
        assertEquals("max_chunks_exceeded", chunks.get(2).skipReason());
    }
}
