package com.haitlify.aitest.platform.ai.analysis.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class AiChunkPlanner {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final String STATUS_PLANNED = "planned";
    private static final String STATUS_SKIPPED = "skipped";

    List<AiPlannedAnalysisChunk> plan(AiChunkPlanningRequest request) {
        int maxInputTokens = request.maxInputTokens() <= 0 ? 2000 : request.maxInputTokens();
        int maxChunks = request.maxChunks() <= 0 ? Integer.MAX_VALUE : request.maxChunks();
        List<AiPlannedAnalysisChunk> chunks = new ArrayList<>();
        for (AiChunkSource source : request.sourceChunks()) {
            for (SectionBlock section : splitSections(source)) {
                for (String content : splitSectionContent(section.contentText(), maxInputTokens)) {
                    addChunk(chunks, maxChunks, source, section.section(), content);
                }
            }
        }
        return chunks;
    }

    private List<SectionBlock> splitSections(AiChunkSource source) {
        List<SectionBlock> sections = new ArrayList<>();
        String section = blankToDefault(source.sectionHeading(), "未命名章节");
        List<String> lines = new ArrayList<>();
        for (String line : source.contentText().split("\\R", -1)) {
            Matcher matcher = HEADING_PATTERN.matcher(line);
            if (matcher.matches()) {
                flushSection(sections, section, lines);
                section = matcher.group(2).trim();
                lines = new ArrayList<>();
            } else {
                lines.add(line);
            }
        }
        flushSection(sections, section, lines);
        return sections;
    }

    private void flushSection(List<SectionBlock> sections, String section, List<String> lines) {
        String content = String.join("\n", lines).trim();
        if (!content.isBlank()) {
            sections.add(new SectionBlock(section, content));
        }
    }

    private List<String> splitSectionContent(String contentText, int maxInputTokens) {
        List<String> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentTokens = 0;
        for (String atom : boundedAtomicBlocks(contentText, maxInputTokens)) {
            int atomTokens = estimateTokens(atom);
            if (!current.isEmpty() && currentTokens + atomTokens > maxInputTokens) {
                chunks.add(String.join("\n", current).trim());
                current = new ArrayList<>();
                currentTokens = 0;
            }
            current.add(atom);
            currentTokens += atomTokens;
            if (atomTokens >= maxInputTokens && isTable(atom)) {
                chunks.add(String.join("\n", current).trim());
                current = new ArrayList<>();
                currentTokens = 0;
            }
        }
        if (!current.isEmpty()) {
            chunks.add(String.join("\n", current).trim());
        }
        return chunks;
    }

    private List<String> boundedAtomicBlocks(String contentText, int maxInputTokens) {
        List<String> bounded = new ArrayList<>();
        for (String atom : atomicBlocks(contentText)) {
            int atomTokens = estimateTokens(atom);
            if (atomTokens <= maxInputTokens || (isTable(atom) && atomTokens <= oversizedTableSplitThreshold(maxInputTokens))) {
                bounded.add(atom);
                continue;
            }
            bounded.addAll(splitOversizedAtom(atom, maxInputTokens));
        }
        return bounded;
    }

    private int oversizedTableSplitThreshold(int maxInputTokens) {
        return Math.max(64, maxInputTokens * 4);
    }

    private List<String> splitOversizedAtom(String atom, int maxInputTokens) {
        if (isTable(atom)) {
            return splitOversizedTable(atom, maxInputTokens);
        }
        return splitTextByChars(atom, maxInputTokens);
    }

    private List<String> splitTextByChars(String text, int maxInputTokens) {
        List<String> parts = new ArrayList<>();
        int maxChars = Math.max(200, maxInputTokens * 4);
        String normalized = text == null ? "" : text.trim();
        for (int start = 0; start < normalized.length(); start += maxChars) {
            int end = Math.min(normalized.length(), start + maxChars);
            parts.add(normalized.substring(start, end).trim());
        }
        return parts.stream().filter(part -> !part.isBlank()).toList();
    }

    private List<String> splitOversizedTable(String table, int maxInputTokens) {
        List<String> parts = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentTokens = 0;
        for (String row : table.split("\\R", -1)) {
            if (row.isBlank()) continue;
            int rowTokens = estimateTokens(row);
            if (!current.isEmpty() && currentTokens + rowTokens > maxInputTokens) {
                parts.add(String.join("\n", current).trim());
                current = new ArrayList<>();
                currentTokens = 0;
            }
            if (rowTokens > maxInputTokens) {
                parts.addAll(splitTextByChars(row, maxInputTokens));
                continue;
            }
            current.add(row);
            currentTokens += rowTokens;
        }
        if (!current.isEmpty()) {
            parts.add(String.join("\n", current).trim());
        }
        return parts;
    }

    private List<String> atomicBlocks(String contentText) {
        List<String> atoms = new ArrayList<>();
        List<String> table = new ArrayList<>();
        for (String line : contentText.split("\\R", -1)) {
            if (line.trim().startsWith("|")) {
                table.add(line);
                continue;
            }
            flushTable(atoms, table);
            if (!line.isBlank()) {
                atoms.add(line);
            }
        }
        flushTable(atoms, table);
        return atoms;
    }

    private void flushTable(List<String> atoms, List<String> table) {
        if (!table.isEmpty()) {
            atoms.add(String.join("\n", table));
            table.clear();
        }
    }

    private void addChunk(List<AiPlannedAnalysisChunk> chunks, int maxChunks, AiChunkSource source, String section, String contentText) {
        boolean planned = chunks.stream().filter(chunk -> STATUS_PLANNED.equals(chunk.status())).count() < maxChunks;
        chunks.add(new AiPlannedAnalysisChunk(
            "planned-" + (chunks.size() + 1),
            List.of(source.sourceChunkId()),
            section,
            estimateTokens(contentText),
            planned ? STATUS_PLANNED : STATUS_SKIPPED,
            planned ? "" : "max_chunks_exceeded",
            contentText
        ));
    }

    private boolean isTable(String value) {
        return value.lines().allMatch(line -> line.trim().startsWith("|"));
    }

    private int estimateTokens(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(normalized.length() / 4.0));
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record SectionBlock(String section, String contentText) {
    }
}

record AiChunkPlanningRequest(
    List<AiChunkSource> sourceChunks,
    int maxInputTokens,
    int maxChunks,
    String analysisScope
) {
}

record AiChunkSource(
    String sourceChunkId,
    String documentId,
    String sectionHeading,
    String contentText
) {
}

record AiPlannedAnalysisChunk(
    String plannedChunkId,
    List<String> sourceChunkIds,
    String section,
    int tokenEstimate,
    String status,
    String skipReason,
    String contentText
) {
}
