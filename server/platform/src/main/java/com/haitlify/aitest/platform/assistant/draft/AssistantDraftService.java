package com.haitlify.aitest.platform.assistant.draft;

import java.nio.charset.StandardCharsets;
import tools.jackson.core.type.TypeReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.auth.ProjectAccessService;

import com.haitlify.aitest.platform.assistant.compile.AssistantInputBindingService;
import com.haitlify.aitest.platform.assistant.compile.AssistantScriptCompiler;

import tools.jackson.databind.ObjectMapper;

/**
 * 草稿与人工确认（计划 §4.2 / §9.2）：模型可以产出草稿，但**只有经人工确认的草稿**才能编译执行。
 *
 * <p>确认是一次显式动作：记录确认人/时间；重复确认不覆盖原确认人；被驳回的草稿不能执行。
 * 草稿 digest = sha256(requirement_ref | requirement_text | spec)，需求文本变了 digest 就变，
 * 防止「确认的是旧需求、执行的是新需求」。
 */
@Service
public class AssistantDraftService {

    public record CreateDraftCommand(long tenantId, Long projectId, long createdBy, String requirementRef,
                                     String requirementText, AssistantScriptCompiler.CaseSpec spec,
                                     String actionCode, String targetRef, String targetRevision,
                                     Map<String, String> inputs) {}

    public record DraftView(String draftId, String state, String requirementDigest, String specDigest,
                            AssistantScriptCompiler.CaseSpec spec, Long confirmedBy,
                            String actionCode, String targetRef, String targetRevision,
                            Map<String, String> inputs, String inputsDigest, Long rejectedBy,
                            int revision, String supersededBy, Map<String, Object> requirementSummary) {}

    public static class DraftNotConfirmedException extends RuntimeException {
        public DraftNotConfirmedException(String message) {
            super(message);
        }
    }

    private final AssistantDraftRepository repository;

    private final AssistantScriptCompiler compiler;

    private final com.haitlify.aitest.platform.assistant.ledger.AssistantLedgerRepository operations;
    private final ObjectMapper objectMapper;
    private final ProjectAccessService projectAccess;

    public AssistantDraftService(AssistantDraftRepository repository, ObjectMapper objectMapper,
                                 ProjectAccessService projectAccess, AssistantScriptCompiler compiler,
                                 com.haitlify.aitest.platform.assistant.ledger.AssistantLedgerRepository operations) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.projectAccess = projectAccess;
        this.compiler = compiler;
        this.operations = operations;
    }

    public DraftView create(CreateDraftCommand command) {
        DraftStorage.validateIdentityAndSource(command);
        projectAccess.requireAccess(command.projectId());
        return view(new DraftStorage(repository, objectMapper, compiler).create(command));
    }

    /** Shared validation/persistence, NOT authorization. Call only after authorizing the complete
     * command scope. No transaction or SecurityContext: joins the caller's DB transaction. */
    public static final class DraftStorage {
        private final AssistantDraftRepository repository;
        private final ObjectMapper objectMapper;
        private final AssistantScriptCompiler compiler;
        public DraftStorage(AssistantDraftRepository repository, ObjectMapper objectMapper, AssistantScriptCompiler compiler) {
            this.repository = repository; this.objectMapper = objectMapper; this.compiler = compiler;
        }
        private static void validateIdentityAndSource(CreateDraftCommand command) {
            if (command.requirementRef() == null || command.requirementRef().isBlank()) {
                throw new IllegalArgumentException("requirementRef 不能为空（需求来源要有可追溯引用）");
            }
            if (command.requirementText() == null || command.requirementText().isBlank()) {
                throw new IllegalArgumentException("requirementText 不能为空");
            }
            if (command.spec() == null) {
                throw new IllegalArgumentException("草稿必须包含用例结构");
            }
            if (command.tenantId() <= 0) {
                throw new IllegalArgumentException("tenantId 必须为正数：" + command.tenantId());
            }
            if (command.projectId() == null) {
                throw new IllegalArgumentException("草稿必须属于某个项目（projectId 必填）");
            }
        }
        public AssistantDraftRepository.DraftRecord create(CreateDraftCommand command) {
            validateIdentityAndSource(command);
            // 结构必须能通过确定性编译：否则「确认」批准的是一份执行必被拒的草稿。
            compiler.compile(command.spec());
            RequirementImages.validate(command.requirementText());
            String draftJson = objectMapper.writeValueAsString(command.spec());
            String digest = sha256(command.requirementRef() + "|" + command.requirementText() + "|" + draftJson);
            if (command.actionCode() == null || command.actionCode().isBlank()
                || command.targetRef() == null || command.targetRef().isBlank()
                || command.targetRevision() == null || command.targetRevision().isBlank()) {
                throw new IllegalArgumentException("草稿必须绑定 actionCode/targetRef/targetRevision（授权上下文）");
            }
            com.haitlify.aitest.platform.assistant.security.AssistantTargetRef
                .validate(command.targetRef(), command.projectId());
            Map<String, String> inputs = command.inputs() == null ? Map.of() : command.inputs();
            // 确认前就校验：不让「已确认但执行必被拒」的草稿存在。
            AssistantInputBindingService.validateValues(inputs);
            String inputsDigest = AssistantInputBindingService.digest(inputs);
            String summaryJson = objectMapper.writeValueAsString(
                requirementSummary(RequirementMedia.summarize(command.requirementText())));
            String draftId = repository.insert(command.tenantId(), command.projectId(), command.requirementRef(),
                digest, draftJson, command.createdBy(), command.actionCode(), command.targetRef(),
                command.targetRevision(), objectMapper.writeValueAsString(inputs), inputsDigest,
                1, null, summaryJson);
            // root_draft_id 指向自己（V67 的 UPDATE 只处理历史行）。
            repository.setRootDraft(draftId, command.tenantId(), draftId);
            return repository.find(draftId, command.tenantId()).orElseThrow();
        }
    }

    /**
     * 资产修改：基于已确认草稿派生新修订（旧修订标记 SUPERSEDED，不能再执行）。
     * 修改后的草稿必须重新确认；确认的是新的结构/输入。
     */
    @org.springframework.transaction.annotation.Transactional
    public DraftView revise(String draftId, long tenantId, long actorId,
                            AssistantScriptCompiler.CaseSpec spec, Map<String, String> inputs) {
        AssistantDraftRepository.DraftRecord original = repository.find(draftId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("草稿不存在：" + draftId));
        requireDraftAccess(original);
        if ("SUPERSEDED".equals(original.state())) {
            throw new DraftNotConfirmedException("草稿已被新修订取代，请修改最新修订：" + draftId);
        }
        AssistantScriptCompiler.CaseSpec next = spec == null ? objectMapper.readValue(original.draftJson(),
            AssistantScriptCompiler.CaseSpec.class) : spec;
        Map<String, String> nextInputs = inputs == null ? Map.of() : inputs;
        compiler.compile(next);
        AssistantInputBindingService.validateValues(nextInputs);
        String json = objectMapper.writeValueAsString(next);
        String digest = sha256(original.requirementRef() + "|" + sha256(json) + "|" + json);
        String newId = repository.insert(tenantId, requireProject(original), original.requirementRef(), digest,
            json, actorId, original.actionCode(), original.targetRef(), original.targetRevision(),
            objectMapper.writeValueAsString(nextInputs), AssistantInputBindingService.digest(nextInputs),
            original.revision() + 1, rootOf(original), original.requirementSummaryJson());
        if (!repository.supersede(draftId, tenantId, newId)) {
            throw new DraftNotConfirmedException("草稿状态已变化，无法派生新修订：" + draftId);
        }
        return view(repository.find(newId, tenantId).orElseThrow());
    }

    private static Long requireProject(AssistantDraftRepository.DraftRecord draft) {
        if (draft.projectId() == null) {
            throw new IllegalArgumentException("草稿未绑定项目，拒绝派生修订：" + draft.draftId());
        }
        return draft.projectId();
    }

    private static String rootOf(AssistantDraftRepository.DraftRecord draft) {
        return draft.rootDraftId() == null ? draft.draftId() : draft.rootDraftId();
    }

    public DraftView confirm(String draftId, long tenantId, long actorId) {
        AssistantDraftRepository.DraftRecord draft = repository.find(draftId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("草稿不存在：" + draftId));
        requireDraftAccess(draft);
        if ("REJECTED".equals(draft.state())) {
            throw new DraftNotConfirmedException("草稿已被驳回，不能确认：" + draftId);
        }
        if ("SUPERSEDED".equals(draft.state())) {
            throw new DraftNotConfirmedException("草稿已被新修订取代，不能确认：" + draftId);
        }
        if ("DRAFT".equals(draft.state()) && !repository.confirm(draftId, tenantId, actorId)) {
            // 并发确认：别人先确认了，读回真实状态而不是覆盖确认人。
            draft = repository.find(draftId, tenantId).orElseThrow();
        } else if ("DRAFT".equals(draft.state())) {
            draft = repository.find(draftId, tenantId).orElseThrow();
        }
        return view(draft);
    }

    public DraftView reject(String draftId, long tenantId, long actorId) {
        // 已确认但还没执行过的草稿可以作废；已经执行过的必须保留（审计对象）。
        AssistantDraftRepository.DraftRecord current = repository.find(draftId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("草稿不存在：" + draftId));
        if ("CONFIRMED".equals(current.state()) && operations.hasOperationForDraft(draftId)) {
            throw new DraftNotConfirmedException("草稿已被执行，不能驳回：请派生新修订：" + draftId);
        }
        AssistantDraftRepository.DraftRecord draft = repository.find(draftId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("草稿不存在：" + draftId));
        requireDraftAccess(draft);
        if (!"DRAFT".equals(draft.state()) && !"CONFIRMED".equals(draft.state())) {
            throw new DraftNotConfirmedException("只有 DRAFT 状态可以驳回，当前 state=" + draft.state()
                + "（已确认的草稿不能悄悄失效）");
        }
        if (!repository.reject(draftId, tenantId, actorId)) {
            throw new DraftNotConfirmedException("草稿状态已变化，驳回未生效：" + draftId);
        }
        return view(repository.find(draftId, tenantId).orElseThrow());
    }

    /** 执行前必须校验：草稿存在、已确认、且与本次要跑的结构一致。 */
    public AssistantDraftRepository.DraftRecord requireConfirmed(String draftId, long tenantId,
                                                                 AssistantScriptCompiler.CaseSpec spec,
                                                                 String actionCode, String targetRef,
                                                                 String targetRevision, Map<String, String> inputs,
                                                                 ObjectMapper mapper) {
        AssistantDraftRepository.DraftRecord draft = repository.find(draftId, tenantId)
            .orElseThrow(() -> new DraftNotConfirmedException("草稿不存在，拒绝执行：" + draftId));
        if ("SUPERSEDED".equals(draft.state()) || draft.supersededBy() != null) {
            throw new DraftNotConfirmedException("草稿已被新修订取代，拒绝执行：" + draftId);
        }
        if (!"CONFIRMED".equals(draft.state())) {
            throw new DraftNotConfirmedException("草稿未确认（state=" + draft.state() + "），拒绝执行：" + draftId);
        }
        String expected = mapper.writeValueAsString(spec);
        if (!expected.equals(draft.draftJson())) {
            throw new DraftNotConfirmedException("执行结构与已确认草稿不一致，拒绝执行（疑似被改写）：" + draftId);
        }
        String inputsDigest = AssistantInputBindingService.digest(inputs == null ? Map.of() : inputs);
        if (!draft.inputsDigest().equals(inputsDigest)) {
            throw new DraftNotConfirmedException(
                "输入与确认时绑定的输入不一致：改输入必须重新确认草稿（" + draftId + "）");
        }
        if (draft.supersededBy() != null) {
            throw new DraftNotConfirmedException("草稿已被新修订取代，不能执行：" + draftId);
        }
        if (!draft.actionCode().equals(actionCode) || !draft.targetRef().equals(targetRef)
            || !draft.targetRevision().equals(targetRevision)) {
            throw new DraftNotConfirmedException(
                "执行目标与已确认草稿不一致（action/target/revision），拒绝执行：" + draftId);
        }
        return draft;
    }

    /** 列表：必须先授权项目；只返回修订链的最新一版（需要历史就查 revisions）。 */
    public List<DraftView> list(long tenantId, long projectId, String state, int limit, int offset) {
        projectAccess.requireAccess(projectId);
        return repository.listByProject(tenantId, projectId, state, limit, offset).stream()
            .map(this::view)
            .toList();
    }

    /** 修订历史：同一 root 的全部版本（含已取代的），给确认页展示「改了什么」。 */
    public List<DraftView> revisions(String draftId, long tenantId) {
        AssistantDraftRepository.DraftRecord draft = repository.find(draftId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("草稿不存在：" + draftId));
        requireDraftAccess(draft);
        return repository.listRevisions(draftId, tenantId).stream().map(this::view).toList();
    }

    public DraftView get(String draftId, long tenantId) {
        AssistantDraftRepository.DraftRecord draft = repository.find(draftId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("草稿不存在：" + draftId));
        requireDraftAccess(draft);
        return view(draft);
    }

    /** 草稿上的读写也要按项目成员关系授权：拿到 draftId 不等于拿到权限。 */
    private void requireDraftAccess(AssistantDraftRepository.DraftRecord draft) {
        if (draft.projectId() == null) {
            throw new IllegalArgumentException("草稿未绑定项目，拒绝操作：" + draft.draftId());
        }
        projectAccess.requireAccess(draft.projectId());
    }

    private DraftView view(AssistantDraftRepository.DraftRecord draft) {
        AssistantScriptCompiler.CaseSpec spec = objectMapper.readValue(draft.draftJson(), AssistantScriptCompiler.CaseSpec.class);
        return new DraftView(draft.draftId(), draft.state(), draft.requirementDigest(),
            sha256(draft.draftJson()), spec, draft.confirmedBy(), draft.actionCode(), draft.targetRef(),
            draft.targetRevision(), maskInputs(draft.inputsJson()), draft.inputsDigest(), draft.rejectedBy(),
            draft.revision(), draft.supersededBy(), readSummary(draft.requirementSummaryJson()));
    }

    /** 确认视图要能看到自己批准了哪些输入，但密钥形态的值不回显。 */
    private Map<String, String> maskInputs(String inputsJson) {
        if (inputsJson == null || inputsJson.isBlank()) {
            return Map.of();
        }
        Map<String, String> raw = objectMapper.readValue(inputsJson, new TypeReference<Map<String, String>>() {});
        Map<String, String> masked = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            // 归一化再判：accessKey/privateKey/PWD 这类 camelCase 与缩写也必须遮住；
            // keyword 这类业务字段要回显，所以只认「整词/后缀」语义。
            // 先按分隔符与 camelCase 切词：access_key_id / accessKey / keyId 都能命中 key。
            String spaced = key.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
            java.util.Set<String> tokens = new java.util.HashSet<>(java.util.Arrays.asList(spaced.split("_")));
            boolean secretish = !java.util.Collections.disjoint(tokens,
                java.util.Set.of("key", "keys", "secret", "secrets", "token", "tokens", "password", "passwd",
                    "pwd", "pass", "pin", "credential", "credentials", "authorization", "cookie", "session"))
                || tokens.contains("apikey") || spaced.contains("access_key") || spaced.contains("private_key")
                // 全小写无分隔的写法（secretkey/accesskeyid/accesstoken）既不在词元也不含分隔符。
                || spaced.replace("_", "").matches(".*(secretkey|accesskey|accesstoken|authtoken|privatekey).*")
                // 值本身是云厂商凭据形态时也不回显（长度不足 32 的 AKIA 键捕获不到）。
                || (value != null && value.matches("^(AKIA|ASIA)[0-9A-Z]{12,20}$"));
            masked.put(key, secretish ? "***" : value);
        });
        return masked;
    }

    private static Map<String, Object> requirementSummary(RequirementMedia.Summary summary) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lineCount", summary.lineCount());
        out.put("charCount", summary.charCount());
        out.put("tableCount", summary.tableCount());
        out.put("imageCount", summary.imageCount());
        out.put("imageRefs", summary.imageRefs());
        out.put("warnings", summary.warnings());
        return out;
    }

    private Map<String, Object> readSummary(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 不可用", ex);
        }
    }
}
