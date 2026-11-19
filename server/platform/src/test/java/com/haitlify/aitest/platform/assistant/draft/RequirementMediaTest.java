package com.haitlify.aitest.platform.assistant.draft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 图文/表格计数必须可解释：表格只认「表头 + 分隔行同列数、每列 ≥3 短横」，
 * 内联 data: 一律拒绝（含带空白的写法，后者不会被普通图片正则捕获）。
 */
class RequirementMediaTest {

    @Test
    void countsOnlyRealGfmTables() {
        // 真表格：2 列表头 + 2 列分隔行。
        assertThat(RequirementMedia.countTables("| a | b |\n| --- | --- |\n| 1 | 2 |")).isEqualTo(1);
        // 伪表格：竖线文本 + 两个短横，不构成 GFM 表格（复核 D5）。
        assertThat(RequirementMedia.countTables("见上|下\n--")).isZero();
        assertThat(RequirementMedia.countTables("接口见 https://x/a|b\n----")).isZero();
        // 列数不匹配的分隔行不算表格。
        assertThat(RequirementMedia.countTables("| a | b |\n| --- |")).isZero();
        // 表格体里含竖线的行不能再配成第二张表。
        assertThat(RequirementMedia.countTables("| a | b |\n| --- | --- |\n| 1 | 2 |\n| 3 | 4 |\n")).isEqualTo(1);
    }

    @Test
    void rejectsInlineDataImagesEvenWhenTheUriContainsWhitespace() {
        assertThatThrownBy(() -> RequirementImages.validate("![x](data:image/png;base64,AAAA)"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("data:");
        // 含空白的 data URI 原先绕过了 URL 正则，必须同样拒绝（复核 D6）。
        assertThatThrownBy(() -> RequirementImages.validate("![x]( data:image/png;base64,AA AA)"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("data:");
    }

    @Test
    void keepsCountingHttpsImagesAndWarnsAboutTables() {
        RequirementMedia.Summary summary = RequirementMedia.summarize(
            "需求 ![a](https://example.test/a.png)\n\n| h | v |\n| --- | --- |\n| 1 | 2 |");

        assertThat(summary.imageCount()).isEqualTo(1);
        assertThat(summary.tableCount()).isEqualTo(1);
        assertThat(summary.warnings()).anyMatch(warning -> warning.contains("markdown table"));
    }
}
