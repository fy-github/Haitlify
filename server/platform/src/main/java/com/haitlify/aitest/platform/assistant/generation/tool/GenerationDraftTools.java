package com.haitlify.aitest.platform.assistant.generation.tool;

import com.haitlify.aitest.platform.assistant.draft.AssistantDraftRepository;
import com.haitlify.aitest.platform.assistant.draft.AssistantDraftService;
import com.haitlify.aitest.platform.assistant.compile.AssistantScriptCompiler;
import java.util.Map;
import com.haitlify.aitest.platform.assistant.execution.ExecutionBindingService;
import com.haitlify.aitest.platform.security.SecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Internal DB-only tools. No HTTP/MCP adapter, worker, model call or execution/confirmation entry. */
@Service
public final class GenerationDraftTools {
    private final ExecutionBindingService execution;
    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;
    private final ObjectMapper json;
    private final AssistantDraftRepository drafts;
    private final AssistantDraftService.DraftStorage storage;

    public GenerationDraftTools(ExecutionBindingService execution, JdbcTemplate jdbc, SecretCipher cipher, ObjectMapper json,
                                AssistantDraftRepository drafts, AssistantScriptCompiler compiler) {
        this.execution = execution; this.jdbc = jdbc; this.cipher = cipher; this.json = json;
        this.drafts = drafts; this.storage = new AssistantDraftService.DraftStorage(drafts, json, compiler);
    }

    public record Context(String requirementRef, String requirementText, String targetUrl, String targetRevision) {}

    public Context context(String raw) {
        return authorized(raw, "generation_context", scope -> snapshot(scope).context());
    }

    public AssistantDraftService.DraftView create(String raw, String specJson) {
        return authorized(raw, "draft_create", scope -> {
            Snapshot source = snapshot(scope);
            AssistantScriptCompiler.CaseSpec spec;
            try { spec = GenerationSpecPolicy.parse(specJson, source.context().targetUrl()); }
            catch (RuntimeException ex) { throw invalidSpec(); }
            String specDigest = digest(json.writeValueAsString(spec));
            var prior = provenance(scope);
            if (prior != null) {
                if (!prior.specDigest().equals(specDigest)) throw conflict();
                return stored(scope, source, prior);
            }
            AssistantDraftRepository.DraftRecord draft;
            try {
                draft = storage.create(new AssistantDraftService.CreateDraftCommand(scope.tenantId(), scope.projectId(), scope.actorId(),
                    source.context().requirementRef(), source.context().requirementText(), spec, "web.run",
                    "project:" + scope.projectId(), source.context().targetRevision(), Map.of()));
            } catch (IllegalArgumentException ex) { throw invalidSpec(); }
            jdbc.update("insert into assistant_generation_drafts(generation_id,draft_id,binding_id,lease_generation,snapshot_digest,spec_digest,provenance_digest,created_at) values (?,?,?,?,?,?,?,?)",
                scope.generationId(), draft.draftId(), scope.bindingId(), scope.leaseGeneration(), source.digest(), specDigest,
                provenanceDigest(scope.generationId(), draft.draftId(), scope.bindingId(), scope.leaseGeneration(), source.digest(), specDigest), java.time.Instant.now());
            return stored(scope, source, provenance(scope));
        });
    }

    public AssistantDraftService.DraftView get(String raw) {
        return authorized(raw, "draft_get", scope -> {
            Snapshot source = snapshot(scope);
            Provenance prior = provenance(scope);
            if (prior == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Generation draft not found");
            return stored(scope, source, prior);
        });
    }

    private <T> T authorized(String raw, String action, java.util.function.Function<ExecutionBindingService.Scope,T> callback) {
        try { return execution.withAuthorizedGrant(raw, action, callback); }
        catch (ExecutionBindingService.AuthorizationException | ResponseStatusException ex) { throw ex; }
        catch (RuntimeException ex) {
            // Outside TransactionTemplate: DB rollback has happened before the opaque error escapes.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Generation tool unavailable");
        }
    }

    private record Provenance(String draftId, String snapshotDigest, String specDigest, String bindingId, long lease, String digest) {}
    private String provenanceDigest(String generation, String draft, String binding, long lease, String snapshot, String spec) {
        // Integrity checksum, not a MAC or authorization proof; authority always comes from the grant.
        return digest(json.writeValueAsString(java.util.List.of(generation, draft, binding, lease, snapshot, spec)));
    }
    private Provenance provenance(ExecutionBindingService.Scope scope) {
        return jdbc.query("select * from assistant_generation_drafts where generation_id=?", (rs,n) ->
            new Provenance(rs.getString("draft_id"), rs.getString("snapshot_digest"), rs.getString("spec_digest"),
                rs.getString("binding_id"), rs.getLong("lease_generation"), rs.getString("provenance_digest")),
            scope.generationId()).stream().findFirst().orElse(null);
    }
    private AssistantDraftService.DraftView stored(ExecutionBindingService.Scope scope, Snapshot source, Provenance prior) {
        var draft = drafts.find(prior.draftId(), scope.tenantId()).orElseThrow(GenerationDraftTools::conflict);
        if (!prior.digest().equals(provenanceDigest(scope.generationId(), prior.draftId(), prior.bindingId(), prior.lease(), prior.snapshotDigest(), prior.specDigest()))
            || !source.digest().equals(prior.snapshotDigest()) || !prior.specDigest().equals(digest(draft.draftJson()))
            || !draft.requirementDigest().equals(digest(source.context().requirementRef() + "|" + source.context().requirementText() + "|" + draft.draftJson()))
            || !"{}".equals(draft.inputsJson()) || !com.haitlify.aitest.platform.assistant.compile.AssistantInputBindingService.digest(Map.of()).equals(draft.inputsDigest())
            || !Long.valueOf(scope.projectId()).equals(draft.projectId()) || draft.createdBy() != scope.actorId()
            || !draft.requirementRef().equals(source.context().requirementRef())
            || !draft.targetRevision().equals(source.context().targetRevision())
            || !draft.targetRef().equals("project:" + scope.projectId()) || !draft.actionCode().equals("web.run")) throw conflict();
        return new AssistantDraftService.DraftView(draft.draftId(), draft.state(), draft.requirementDigest(), prior.specDigest(),
            json.readValue(draft.draftJson(), AssistantScriptCompiler.CaseSpec.class), draft.confirmedBy(), draft.actionCode(),
            draft.targetRef(), draft.targetRevision(), Map.of(), draft.inputsDigest(), draft.rejectedBy(), draft.revision(),
            draft.supersededBy(), json.readValue(draft.requirementSummaryJson(), new tools.jackson.core.type.TypeReference<Map<String,Object>>() {}));
    }
    private static ResponseStatusException invalidSpec() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid generation draft specification");
    }
    private static ResponseStatusException conflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Generation draft conflict");
    }

    private record Snapshot(Context context, String digest) {}
    private Snapshot snapshot(ExecutionBindingService.Scope scope) {
        // Generation is already locked by authorization. Never reacquire earlier-order locks here.
        try {
            return jdbc.query("select * from assistant_generations where id=?", (rs, n) -> {
                if (rs.getLong("actor_id") != scope.actorId() || rs.getLong("tenant_id") != scope.tenantId()
                    || rs.getLong("project_id") != scope.projectId()) throw invalidSnapshot();
                String encrypted = rs.getString("snapshot_ciphertext");
                if (encrypted == null || !encrypted.startsWith("enc:v1:")) throw invalidSnapshot();
                String plain = cipher.decrypt(encrypted);
                if (plain.length() > 400000 || !digest(plain).equals(rs.getString("payload_digest"))) throw invalidSnapshot();
                JsonNode body = json.readTree(plain);
                if (!body.isObject() || !body.propertyNames().equals(Set.of("requestId", "projectId", "requirementRef", "requirementText", "targetUrl", "targetRevision"))
                    || !body.path("projectId").isIntegralNumber() || body.path("projectId").asLong() != scope.projectId()
                    || !text(body, "requestId", 64).equals(rs.getString("request_id"))) throw invalidSnapshot();
                return new Snapshot(new Context(text(body, "requirementRef", 512), text(body, "requirementText", 65536),
                    text(body, "targetUrl", 2048), text(body, "targetRevision", 128)), rs.getString("payload_digest"));
            }, scope.generationId()).stream().findFirst().orElseThrow(GenerationDraftTools::invalidSnapshot);
        } catch (RuntimeException ex) {
            // Do not chain parser/cipher/SQL exceptions containing source text or credentials.
            throw invalidSnapshot();
        }
    }

    private static String text(JsonNode node, String key, int max) {
        JsonNode value = node.path(key);
        if (!value.isString() || value.asString().isBlank() || value.asString().length() > max) throw invalidSnapshot();
        return value.asString();
    }
    private static ResponseStatusException invalidSnapshot() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Generation snapshot unavailable");
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
