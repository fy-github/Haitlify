package com.haitlify.aitest.platform.ai.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T07 架构coverage：扫描全项目源码，AiGateway.structuredGenerate / visionAnalyze 的直接调用
 * 只允许出现在「登记在册的待迁移调用点」与「白名单层（gateway/facade/runtime）」里。
 *
 * <p>迁移推进时：每迁完一个消费者，把它从 {@link #REGISTERED_PENDING_MIGRATION} 移入
 * {@link #MIGRATED}；任何新出现的未登记直接调用都会让本测试失败（防止新入口绕过统一 Runtime）。
 * 当 REGISTERED_PENDING_MIGRATION 清空后，业务层直连即彻底被禁止。
 */
class AiRuntimeConsumerCoverageTest {

    /** 唯一允许长期持有直连调用的层：gateway 自身定义与统一 Runtime facade。
     * 2026-10 收敛轮：路由执行器与结构修复已迁入 MIGRATED（经 facade.generateForRoute）；
     * 业务层/编排层直连一律须经登记或迁移，新增直连即失败。 */
    private static final Set<String> SANCTIONED_LAYERS = Set.of(
        "ai/gateway/AiGateway.java",
        "ai/gateway/DefaultAiGateway.java",
        "ai/runtime/AiTaskExecutionFacade.java"
    );

    /**
     * T11 预运行管理探针：对「尚未保存的草稿配置」做连通性验证（连接测试），天然没有
     * run/task 上下文，不能也不应经 AiTaskExecutionFacade——那会伪造 run 上下文。
     * 仅限单目的探针（无业务 prompt、无交付物），新增任何此类入口都必须显式登记。
     */
    private static final Set<String> SANCTIONED_PRE_RUN_PROBES = Set.of(
        "ai/config/AiConfigService.java"
    );

    /**
     * T07 迁移清单（主计划 §5.1）：仍直连调用、待逐个迁到统一 Runtime/facade 的入口。
     * 全部完成——业务层直连调用已清零（登记表为空即终态，任何新直连都会失败）。
     */
    private static final Set<String> REGISTERED_PENDING_MIGRATION = Set.of();

    /** 已迁移（不再出现直连调用的入口文件）——随迁移推进由 REGISTERED 移入。 */
    private static final Set<String> MIGRATED = Set.of(
        // 同步 executeRun 死代码零引用验证后整体删除（含其专属私有链）。
        "ai/analysis/AiAnalysisRunService.java",
        // 经 AiTaskExecutionFacade 统一入口（台账预算 + attempt 审计），机会式降级语义不变。
        "ai/agent/AiAgentConsensusMergeService.java",
        // 经 facade；settingsService 仅保留用于调用记录元数据。
        "ai/agent/AiMultiAgentRunService.java",
        // 经 facade 视觉通道 generateVision；旧 VisualGatewaySettings 私有查询已删除。
        "ai/visual/AiVisualAnalysisService.java",
        // 经 facade（用例生成/Web 方案两处调用点）；settingsService 仅保留调用记录用途。
        "ai/draft/AiDraftTestPointService.java",
        // 经 facade（5s 子预算作显式 override 保留）；settingsService 依赖整体移除。
        "ai/draft/WebExplorationSemanticExpansionService.java",
        // T07 收敛轮：路由执行器经 facade.generateForRoute 发起每次调用（台账/attempt/预算/失败分类同权），
        // 本类只保留路由/重试/取消编排；不再直接调用网关。
        "ai/gateway/AiRouteExecutor.java",
        // T07 收敛轮：结构修复经 facade.generateForRoute（纳入父 run 台账与 token 预算），不再旁路直发。
        "ai/gateway/AiStructuredOutputRepairService.java"
    );

    /**
     * 注：ai/figma/AiFigmaAnalysisService.java（主计划 §5.1 清单）实测不含任何直连
     * gateway 调用（纯规则提取，模型语义分析未接线），从登记表移除；若日后接线直连
     * 调用，扫描会以「未登记调用点」直接失败，防回归仍成立。
     */

    private static final Pattern DIRECT_CALL = Pattern.compile("\\.(structuredGenerate|visionAnalyze)\\s*\\(");

    @Test
    void everyDirectGatewayCallSiteIsRegisteredOrSanctioned() throws IOException {
        Map<String, List<Integer>> offenders = new LinkedHashMap<>();
        for (Path source : mainJavaSources()) {
            String relative = relative(source);
            if (SANCTIONED_LAYERS.contains(relative)
                    || SANCTIONED_PRE_RUN_PROBES.contains(relative)
                    || REGISTERED_PENDING_MIGRATION.contains(relative)
                    || MIGRATED.contains(relative)) {
                continue;
            }
            List<Integer> lines = new ArrayList<>();
            List<String> content = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int line = 0; line < content.size(); line++) {
                if (DIRECT_CALL.matcher(content.get(line)).find()) {
                    lines.add(line + 1);
                }
            }
            if (!lines.isEmpty()) {
                offenders.put(relative, lines);
            }
        }
        assertThat(offenders)
            .describedAs("未登记的 AiGateway 直接调用点（业务层禁止绕过统一 Runtime；"
                + "迁移完成的文件移入 MIGRATED，新增入口必须登记）")
            .isEmpty();
    }

    @Test
    void registeredEntryPointsFromThePlanAllStillExistOrAreMigrated() throws IOException {
        Set<String> all = new LinkedHashSet<>();
        for (Path source : mainJavaSources()) {
            all.add(relative(source));
        }
        // 登记表必须与真实文件一一对应（防呆：文件改名/删除后登记表必须同步）。
        for (String registered : REGISTERED_PENDING_MIGRATION) {
            assertThat(all).describedAs("登记表文件必须存在：" + registered).contains(registered);
        }
        for (String migrated : MIGRATED) {
            assertThat(all).describedAs("已迁移文件必须存在：" + migrated).contains(migrated);
        }
        // 迁移与登记不得同时登记同一文件。
        if (!MIGRATED.isEmpty()) {
            assertThat(REGISTERED_PENDING_MIGRATION).doesNotContainAnyElementsOf(MIGRATED);
        }
    }

    @Test
    void migratedFilesContainNoDirectCalls() throws IOException {
        for (Path source : mainJavaSources()) {
            String relative = relative(source);
            if (!MIGRATED.contains(relative)) {
                continue;
            }
            Matcher matcher = DIRECT_CALL.matcher(Files.readString(source, StandardCharsets.UTF_8));
            assertThat(matcher.find())
                .describedAs("已迁移文件 %s 不得再有直连调用（第 %d 行附近）", relative,
                    matcher.find() ? 1 : 0)
                .isFalse();
        }
    }

    private static List<Path> mainJavaSources() throws IOException {
        Path root = Path.of("src/main/java");
        assertThat(Files.isDirectory(root)).describedAs("必须从 server/platform 运行").isTrue();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String relative(Path source) {
        return source.toString().replace('\\', '/')
            .replaceFirst("^src/main/java/com/haitlify/aitest/platform/", "");
    }
}
