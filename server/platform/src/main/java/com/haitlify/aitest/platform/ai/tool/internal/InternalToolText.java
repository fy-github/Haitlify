package com.haitlify.aitest.platform.ai.tool.internal;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * T09 bullet 4：内部工具的 RULE 文本规则（确定性、可复现，算法版本随输出标注）。
 */
final class InternalToolText {

    static final String MATCH_ALGORITHM = "keyword-overlap-v1";
    static final String COVERAGE_ALGORITHM = "coverage-dimensions-v1";

    private InternalToolText() {
    }

    /** 关键词集合：ASCII 词 + CJK 二元组（无空格中文的确定性回退，不引入模型）。 */
    static Set<String> terms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        for (String token : text.toLowerCase(Locale.ROOT).split("[\\s,，。;；:：/|()\\[\\]{}<>\"'!?！？、]+")) {
            if (token.isBlank()) {
                continue;
            }
            if (token.length() >= 2) {
                terms.add(token);
            }
            addCjkBigrams(token, terms);
        }
        return terms;
    }

    private static void addCjkBigrams(String token, Set<String> terms) {
        if (token.length() < 4 || !isCjkHeavy(token)) {
            return;
        }
        for (int index = 0; index + 2 <= token.length(); index++) {
            terms.add(token.substring(index, index + 2));
        }
    }

    private static boolean isCjkHeavy(String token) {
        int cjk = 0;
        for (int index = 0; index < token.length(); index++) {
            if (token.charAt(index) >= 0x4E00 && token.charAt(index) <= 0x9FFF) {
                cjk++;
            }
        }
        return cjk * 2 >= token.length();
    }

    /** 重叠命中次数（标题命中计 3 分，正文计 1 分）。 */
    static int overlapScore(String haystack, Set<String> terms) {
        if (haystack == null || haystack.isBlank() || terms.isEmpty()) {
            return 0;
        }
        String lowered = haystack.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            int index = 0;
            while ((index = lowered.indexOf(term, index)) >= 0) {
                score++;
                index += term.length();
            }
        }
        return score;
    }

    /** 覆盖维度关键词（中英同义；命中任一即视为该维度被覆盖）。 */
    static boolean mentionsAny(String haystack, String... keywords) {
        if (haystack == null || haystack.isBlank()) {
            return false;
        }
        String lowered = haystack.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lowered.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
