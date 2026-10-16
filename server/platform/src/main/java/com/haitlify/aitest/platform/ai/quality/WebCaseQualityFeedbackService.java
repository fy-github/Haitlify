package com.haitlify.aitest.platform.ai.quality;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;

/**
 * 质量反馈反哺生成:把 {@link WebCaseQualityInsightService} 聚合出的弱维与丢失用例信号,
 * 转成可直接拼入 agentMemoryContext 的提示字符串,让下一轮生成在规划阶段就规避历史弱项。
 *
 * <p>设计刻意保持「增量、可空」:没有评分 / 没有弱维 / 没有丢失用例时返回空列表,
 * 此时生成行为与加入本服务之前完全一致,不改变既有流程。调用方({@code WebCaseAgentDispatcher})
 * 需自行兜底异常,使本路径任何失败都退化为空列表,绝不阻断主生成流程。
 *
 * <p>提示一律以 {@code [quality_signal]} 前缀并明示「系统基于历史人工评分/回归对比自动统计」,
 * 与人工确认的经验记忆在语义上区分开,降低模型对机器聚合信号过度信任的风险。
 */
@Service
public class WebCaseQualityFeedbackService {

    private static final int MAX_WEAK_HINTS = 3;
    private static final int MAX_LOST_HINTS = 5;
    private static final int MAX_TITLE_LENGTH = 100;

    /** 六维与中文展示名,顺序与 {@link WebCaseQualityInsightService} 契约一致。 */
    private static final Map<String, String> DIMENSION_LABELS = Map.of(
        "coverage", "覆盖度",
        "source_traceability", "来源可追溯",
        "testability", "可测试性",
        "duplicate_risk", "去重质量",
        "automation_readiness", "自动化就绪",
        "review_risk", "评审把握"
    );

    /** 弱维 → 历史观察(参考口吻,非命令),契合 agentMemoryContext「仅作可复用参考」的语义。 */
    private static final Map<String, String> DIMENSION_HINTS = Map.of(
        "coverage", "此前生成常遗漏边界条件、异常分支与反向用例，本轮规划时可参考补齐这些场景",
        "source_traceability", "此前部分用例未能回溯到明确的测试点/需求原文，本轮可强化步骤与需求来源的对应",
        "testability", "此前部分步骤存在“正常显示”“合理”等含糊表述，本轮可改为可执行、可验证的断言",
        "duplicate_risk", "此前存在同义用例重复生成稀释评审的情况，本轮可先合并重复场景",
        "automation_readiness", "此前部分断言依赖人工判断，本轮可优先选择可自动执行的路径",
        "review_risk", "此前部分预期结果多义、评审需反复确认，本轮可让预期结果单一明确"
    );

    private final WebCaseQualityInsightService insightService;

    WebCaseQualityFeedbackService(WebCaseQualityInsightService insightService) {
        this.insightService = insightService;
    }

    /**
     * 返回可直接拼入 agentMemoryContext 的提示字符串;无评分/无弱维/无丢失用例时返回空列表。
     * {@code runId} 为当前正在生成的 run,聚合回归时排除它,避免把「本轮待生成的用例」误判为丢失。
     */
    public List<String> feedbackContext(Long projectId, String runId) {
        if (projectId == null) return List.of();
        WebCaseQualityInsightResponse insight = insightService.insight(projectId, runId);
        int threshold = WebCaseQualityRatingService.BASELINE_THRESHOLD;

        List<String> hints = new ArrayList<>();
        for (WeakDimension weak : insight.weakestDimensions().stream().limit(MAX_WEAK_HINTS).toList()) {
            String guidance = DIMENSION_HINTS.get(weak.key());
            if (guidance == null) continue;
            String label = DIMENSION_LABELS.getOrDefault(weak.key(), weak.key());
            hints.add("[quality_signal] 系统基于历史人工评分自动统计：该项目「" + label + "」平均 "
                + formatScore(weak.avg()) + " 分(低于 " + threshold + " 分基线)，" + guidance);
        }
        for (LostCase lost : insight.lostCases().stream().limit(MAX_LOST_HINTS).toList()) {
            hints.add("[quality_signal] 系统基于历史回归对比自动统计：固化基线用例「" + sanitizeTitle(lost.title())
                + "」在既往重生成中未复现 " + lost.count() + " 次(可能因改名或措辞变化)，本轮可留意覆盖等价场景");
        }
        return List.copyOf(hints);
    }

    /** 标题来自已生成的 artifact,回填进提示前必须收敛为单行、受控长度,防 prompt 注入与 token 膨胀。 */
    private String sanitizeTitle(String title) {
        if (title == null) return "";
        String cleaned = title.replaceAll("[\\p{Cc}\\r\\n\\t]", " ").replaceAll("\\s+", " ").strip();
        return cleaned.length() > MAX_TITLE_LENGTH ? cleaned.substring(0, MAX_TITLE_LENGTH) + "…" : cleaned;
    }

    /** 弱维均值向下取整到一位小数,保证「低于基线」的展示值永远严格小于阈值,避免自相矛盾。 */
    private String formatScore(double value) {
        return String.format(Locale.ROOT, "%.1f", Math.floor(value * 10.0) / 10.0);
    }
}
