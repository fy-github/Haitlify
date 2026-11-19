package com.haitlify.aitest.platform.assistant.draft;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 图文/表格需求的确定性规模摘要。
 *
 * <p>计划 §6.2 要求「表格/图片有锚点、缺图表标 PARTIAL，不能静默丢弃」。原文不落库（只存 digest），
 * 所以创建草稿时把「这份需求长什么样」固化成可核验的事实：行数、字符数、markdown 表格数与图片数，
 * 以及不支持的内联图片。报告只用这份事实统计覆盖，不事后重算、不推断。
 *
 * <p>只数结构，不解析内容：真正理解图片/表格需要视觉模型，属于 Hermes 侧能力。
 */
final class RequirementMedia {

    /** 分隔行：每个单元格至少 3 个短横（GFM 要求），可带对齐冒号。 */
    private static final Pattern TABLE_DELIMITER = Pattern.compile(
        "^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$");
    private static final Pattern INLINE_DATA_IMAGE = Pattern.compile("!\\[[^\\]]*]\\(\\s*data:");

    record Summary(int lineCount, int charCount, int tableCount, int imageCount,
                   List<String> imageRefs, List<String> warnings) {}

    private RequirementMedia() {}

    static Summary summarize(String text) {
        String body = text == null ? "" : text;
        int lineCount = body.isBlank() ? 0 : body.split("\\R", -1).length;
        int charCount = body.length();
        List<String> warnings = new ArrayList<>();
        int tableCount = countTables(body);
        List<String> refs = new ArrayList<>();
        for (RequirementImages.ImageRef ref : RequirementImages.parse(body)) {
            refs.add(ref.url());
        }
        Matcher inline = INLINE_DATA_IMAGE.matcher(body);
        int inlineCount = 0;
        while (inline.find()) {
            inlineCount++;
        }
        if (inlineCount > 0) {
            warnings.add(inlineCount + " inline data: image(s) cannot be audited; attach via https or /api/ instead");
        }
        if (tableCount > 0) {
            warnings.add(tableCount + " markdown table(s) counted, cell content not verified by the deterministic parser");
        }
        return new Summary(lineCount, charCount, tableCount, refs.size(), List.copyOf(refs), List.copyOf(warnings));
    }

    /**
     * 表格 = 表头行 + 分隔行，两行列数一致，且分隔行每列至少 3 个短横。
     * 只要求「含竖线的行 + 下一行有短横」会把 `a|b` 后面一行 `--` 也算成表格（复核 D5）。
     */
    static int countTables(String body) {
        if (body == null || body.isBlank()) {
            return 0;
        }
        String[] lines = body.split("\\R", -1);
        int tables = 0;
        for (int index = 0; index + 1 < lines.length; index += 1) {
            String header = lines[index];
            String delimiter = lines[index + 1];
            if (!header.contains("|") || !TABLE_DELIMITER.matcher(delimiter).matches()) {
                continue;
            }
            if (cellCount(header) < 2 || cellCount(header) != cellCount(delimiter)) {
                continue;
            }
            tables++;
            // 跳过表体，避免把表体里恰好含竖线的行再配成新表。
            int cursor = index + 2;
            while (cursor < lines.length && lines[cursor].contains("|") && !lines[cursor].isBlank()) {
                cursor++;
            }
            index = cursor - 1;
        }
        return tables;
    }

    private static int cellCount(String line) {
        String trimmed = line.strip();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.split("\\|", -1).length;
    }
}
