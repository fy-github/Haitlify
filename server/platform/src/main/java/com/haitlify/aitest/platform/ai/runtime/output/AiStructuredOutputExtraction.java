package com.haitlify.aitest.platform.ai.runtime.output;

/**
 * T03 有限 fence 处理：仅接受「整体被单一 ``` 或 ```json 代码块包裹」的输出并剥壳。
 * 不做正则补完、不修复截断、不从文本任意位置抓取片段——任何不确定情形一律原样返回，
 * 交由严格 JSON 解析失败（空输出/拒绝文本/截断的 JSON 绝不变成「成功的空结果」）。
 * Unicode 与字符串内嵌花括号由 JSON 解析器自身处理，本工具不额外改写任何字节。
 */
public final class AiStructuredOutputExtraction {

    private AiStructuredOutputExtraction() {
    }

    public static String extract(String content) {
        if (content == null) {
            return "";
        }
        String value = content.strip();
        if (!value.startsWith("```")) {
            return value;
        }
        int firstLineEnd = value.indexOf('\n');
        if (firstLineEnd < 0) {
            return value;
        }
        String firstLine = value.substring(0, firstLineEnd).strip();
        if (!firstLine.equals("```") && !firstLine.equals("```json")) {
            // 只认 json/裸 fence；其它语言 fence 属于不可信形态，保持原样由解析失败。
            return value;
        }
        if (!value.endsWith("```") || value.length() < firstLineEnd + 1 + 3) {
            // 无闭合（截断）不补、不动。
            return value;
        }
        String inner = value.substring(firstLineEnd + 1, value.length() - 3).strip();
        if (inner.contains("```")) {
            // 内部再出现 fence 说明不是「整体单块」形态，保持原样。
            return value;
        }
        return inner;
    }
}
