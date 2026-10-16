package com.haitlify.aitest.platform.ai.runtime;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * T15 发布开关（共享解析）。facade 与 job Runtime 两条真实出口共用同一语义——
 * 被禁范围在发出任何请求前阻断，绝无旁路或双份付费请求；空配置 = 全部启用。
 */
public final class AiRuntimeSwitches {

    public record Switches(Set<String> disabledTaskTypes, Set<Long> disabledProjects) {

        public static Switches empty() {
            return new Switches(Set.of(), Set.of());
        }

        public boolean isDisabled(String taskType, Long projectId) {
            return reasonFor(taskType, projectId) != null;
        }

        /** 命中时的原因（null = 未命中）。 */
        public String reasonFor(String taskType, Long projectId) {
            // T15-R5：大小写/空白（含 Unicode）不敏感——变体不得绕过灰度。
            String normalized = normalizeIdentifier(taskType);
            if (!normalized.isEmpty() && disabledTaskTypes.contains(normalized)) {
                return "runtime_disabled_task_type: " + taskType;
            }
            if (projectId != null && disabledProjects.contains(projectId)) {
                return "runtime_disabled_project: " + projectId;
            }
            return null;
        }
    }

    private AiRuntimeSwitches() {
    }

    /**
     * T-R5 轮 5：标识符归一——迭代到不动点（≤4 轮）：NFKC → 剥离（Z*、Cc、Cf、Mn、Me）→ lowercase。
     * 单轮变换存在两类互补的顺序副作用，必须迭代：
     *  - 数学粗体 '𝐀'（U+1D400）无 Java 简单小写映射：先 NFKC 折叠为 'A'，下一轮 lowercase 成 'a'；
     *  - 'İ' 的 lowercase 会引入组合点（Mn）：下一轮被剥离。
     * 结果同时用于灰度判定、配置解析、submit 归一与目录查找——四者口径必须一致。
     */
    public static String normalizeIdentifier(String raw) {
        if (raw == null) {
            return "";
        }
        String current = raw;
        for (int round = 0; round < 4; round++) {
            String decomposed = java.text.Normalizer.normalize(current,
                java.text.Normalizer.Form.NFKC);
            StringBuilder builder = new StringBuilder(decomposed.length());
            // T-R5 轮 6：按码点（而非 UTF-16 char）迭代——补充平面 Mn/Mc/Cf（如 U+101FD、U+E0020）
            // 以代理对形式出现，charAt+getType(char) 只会看到 SURROGATE 而漏剥离。
            for (int index = 0; index < decomposed.length(); ) {
                int codePoint = decomposed.codePointAt(index);
                int category = Character.getType(codePoint);
                if (category == Character.SPACE_SEPARATOR || category == Character.LINE_SEPARATOR
                        || category == Character.PARAGRAPH_SEPARATOR || category == Character.CONTROL
                        || category == Character.FORMAT || category == Character.NON_SPACING_MARK
                        || category == Character.COMBINING_SPACING_MARK
                        || category == Character.ENCLOSING_MARK) {
                    index += Character.charCount(codePoint);
                    continue;
                }
                builder.appendCodePoint(codePoint);
                index += Character.charCount(codePoint);
            }
            String next = builder.toString().toLowerCase(java.util.Locale.ROOT);
            if (next.equals(current)) {
                return next;
            }
            current = next;
        }
        return current;
    }

    public static Switches parse(String rawTaskTypes, String rawProjects) {
        Set<String> taskTypes = new LinkedHashSet<>();
        if (rawTaskTypes != null) {
            for (String part : rawTaskTypes.split(",")) {
                String normalized = normalizeIdentifier(part);
                if (!normalized.isEmpty()) {
                    taskTypes.add(normalized);
                }
            }
        }
        Set<Long> projects = new LinkedHashSet<>();
        if (rawProjects != null) {
            for (String part : rawProjects.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                try {
                    projects.add(Long.parseLong(normalizeIdentifier(part)));
                } catch (NumberFormatException ignored) {
                    // 非法项目 id 忽略（任务型禁用不受影响）。
                }
            }
        }
        return new Switches(Set.copyOf(taskTypes), Set.copyOf(projects));
    }
}
