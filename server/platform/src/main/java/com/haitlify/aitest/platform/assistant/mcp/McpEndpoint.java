package com.haitlify.aitest.platform.assistant.mcp;

import com.haitlify.aitest.platform.assistant.execution.ExecutionBindingService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.web.filter.GenericFilterBean;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Terminal filter: exact machine endpoint only; no MVC error forwarding or ambient principal. */
final class McpEndpoint extends GenericFilterBean {
    static final String VERSION = "2025-11-25";
    static final int MAX_BODY = 65536;
    private final boolean enabled;
    private final ExecutionBindingService execution;
    private final Map<String, Session> sessions = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final JsonMapper json = JsonMapper.builder(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(MAX_BODY).maxNumberLength(128).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    private final com.haitlify.aitest.platform.assistant.generation.tool.GenerationDraftTools tools;
    private final Clock clock;
    private final int capacity;
    McpEndpoint(boolean enabled, ExecutionBindingService execution,
            com.haitlify.aitest.platform.assistant.generation.tool.GenerationDraftTools tools) {
        this(enabled, execution, tools, Clock.systemUTC(), 1024);
    }
    McpEndpoint(boolean enabled, ExecutionBindingService execution,
            com.haitlify.aitest.platform.assistant.generation.tool.GenerationDraftTools tools, Clock clock, int capacity) {
        this.enabled = enabled; this.execution = execution; this.tools = tools;
        this.clock = clock; this.capacity = capacity;
    }
    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException {
        var req = (HttpServletRequest) request;
        var res = (HttpServletResponse) response;
        begin(res);
        try {
            if (req.getHeader("Origin") != null) throw new HttpFailure(403);
            String raw = bearer(req);
            var authorized = execution.inspectAuthorizedGrant(raw, "generation_context");
            if (!enabled) throw new HttpFailure(404);
            serve(req, res, raw, authorized);
        } catch (ExecutionBindingService.AuthorizationException ex) { fail(res, 401); }
          catch (HttpFailure ex) { fail(res, ex.status); }
          catch (RuntimeException ex) { fail(res, 503); }
    }

    /**
     * Second entry: the caller proved possession of a lease instead of presenting the grant.
     * Everything after the raw token is known (authorization snapshot, session binding, TTL, protocol,
     * tool authorization, error semantics) is the <b>same</b> code path as {@link #doFilter}.
     */
    void serveLease(HttpServletRequest req, HttpServletResponse res, String raw) throws IOException {
        begin(res);
        try {
            var authorized = execution.inspectAuthorizedGrant(raw, "generation_context");
            serve(req, res, raw, authorized);
        } catch (ExecutionBindingService.AuthorizationException ex) { fail(res, 401); }
          catch (HttpFailure ex) { fail(res, ex.status); }
          catch (RuntimeException ex) { fail(res, 503); }
    }

    private static void begin(HttpServletResponse res) {
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("X-Content-Type-Options", "nosniff");
    }

    private void serve(HttpServletRequest req, HttpServletResponse res, String raw,
                       ExecutionBindingService.AuthorizedGrant authorized) throws IOException {
        var scope = authorized.scope();
        String version = single(req, "MCP-Protocol-Version");
        if (version != null && !VERSION.equals(version)) throw new HttpFailure(400);
        String sessionId = single(req, "Mcp-Session-Id");
        if (sessionId != null) session(sessionId, raw, scope);
        if (!"POST".equals(req.getMethod())) { res.setHeader("Allow", "POST"); throw new HttpFailure(405); }
        if (!accepts(req)) throw new HttpFailure(406);
        String type = single(req, "Content-Type");
        if (type == null || !type.matches("(?i)application/json(?:\\s*;\\s*charset=\"?utf-8\"?)?")) throw new HttpFailure(415);
        byte[] bytes;
        try { bytes = req.getInputStream().readNBytes(MAX_BODY + 1); }
        catch (IOException ex) { throw new HttpFailure(400); }
        if (bytes.length > MAX_BODY) throw new HttpFailure(413);
        JsonNode message;
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            message = json.readTree(text);
        } catch (Exception ex) { throw new HttpFailure(400); }
        if (message == null || !message.isObject() || !"2.0".equals(message.path("jsonrpc").asString())
            || !message.path("method").isString() || !Set.of("jsonrpc", "id", "method", "params").containsAll(message.propertyNames())
            || (message.has("id") && !(message.path("id").isString() || message.path("id").isIntegralNumber()))
            || (message.has("params") && !message.path("params").isObject())) throw new HttpFailure(400);
        String method = message.path("method").asString();
        if ("initialize".equals(method)) {
            JsonNode params = message.path("params");
            if (!message.has("id") || single(req, "Mcp-Session-Id") != null
                || !params.path("protocolVersion").isString() || !params.path("capabilities").isObject()
                || !params.path("clientInfo").path("name").isString() || !params.path("clientInfo").path("version").isString())
                throw new HttpFailure(400);
            String session = newSession(raw, authorized);
            res.setHeader("Mcp-Session-Id", session);
            reply(res, message.get("id"), Map.of("protocolVersion", VERSION, "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", "haitlify-private-drafts", "version", "1")));
            return;
        }
        Session session = session(single(req, "Mcp-Session-Id"), raw, scope);
        if (!message.has("id")) {
            if ("notifications/initialized".equals(method)) { session.ready = true; res.setStatus(202); return; }
            throw new HttpFailure(400);
        }
        if ("ping".equals(method)) { reply(res, message.get("id"), Map.of()); return; }
        if (!session.ready) throw new HttpFailure(400);
        JsonNode params = message.path("params");
        if ("tools/list".equals(method)) {
            if (!params.isMissingNode() && (!params.isObject()
                    || !Set.of("_meta").containsAll(params.propertyNames()) || !validMetadata(params))) {
                rpcError(res, message.get("id"), -32602); return;
            }
            reply(res, message.get("id"), Map.of("tools", McpToolCatalog.tools())); return;
        }
        if ("tools/call".equals(method)) {
            if (!params.isObject() || !Set.of("name", "arguments", "_meta").containsAll(params.propertyNames())
                || !params.path("name").isString() || !validMetadata(params)
                || (params.has("arguments") && !params.path("arguments").isObject())) {
                rpcError(res, message.get("id"), -32602); return;
            }
            String name = params.path("name").asString();
            // Omission is legal in CallToolRequestParams; explicit null is not an object.
            // Protocol metadata never becomes business arguments or authorization scope.
            JsonNode args = params.has("arguments") ? params.get("arguments") : json.createObjectNode();
            if (!(Set.of("generation_context", "draft_get").contains(name) && args.isEmpty())
                && !("draft_create".equals(name) && args.propertyNames().equals(Set.of("spec")) && args.path("spec").isObject())) {
                rpcError(res, message.get("id"), -32602); return;
            }
            session.admitTool();
            // Each tool owns its authorization transaction. Never nest callbacks or wrap this filter in one.
            Object value;
            try {
                value = switch (name) {
                    case "generation_context" -> tools.context(raw);
                    case "draft_create" -> tools.create(raw, json.writeValueAsString(args.get("spec")));
                    case "draft_get" -> tools.get(raw);
                    default -> throw new IllegalStateException();
                };
            } catch (org.springframework.web.server.ResponseStatusException ex) {
                int status = ex.getStatusCode().value();
                if (status == 401 || status == 403) throw new HttpFailure(status);
                String error = switch (status) {
                    case 400 -> "Invalid draft specification";
                    case 404 -> "Generation draft not found";
                    case 409 -> "Generation draft conflict";
                    default -> "Generation tool unavailable";
                };
                reply(res, message.get("id"), Map.of("isError", true,
                    "content", List.of(Map.of("type", "text", "text", error))));
                return;
            }
            reply(res, message.get("id"), Map.of("isError", false, "structuredContent", value,
                "content", List.of(Map.of("type", "text", "text", json.writeValueAsString(value)))));
            return;
        }
        rpcError(res, message.get("id"), -32601);
    }

    private static boolean validMetadata(JsonNode params) {
        if (!params.has("_meta")) return true;
        JsonNode meta = params.get("_meta");
        if (!meta.isObject()) return false;
        if (!meta.has("progressToken")) return true;
        JsonNode progress = meta.get("progressToken");
        return progress.isString() || progress.isNumber();
    }

    private String bearer(HttpServletRequest req) {
        if (req.getHeader("Cookie") != null || req.getQueryString() != null) throw new HttpFailure(401);
        if (Collections.list(req.getHeaders("Authorization")).size() != 1) throw new HttpFailure(401);
        String value = single(req, "Authorization");
        if (value == null || !value.matches("(?i:Bearer) [A-Za-z0-9._~+/-]+=*") || value.length() > 512) throw new HttpFailure(401);
        return value.substring(7);
    }
    private static String single(HttpServletRequest request, String header) {
        var values = Collections.list(request.getHeaders(header));
        if (values.size() > 1) throw new HttpFailure(400);
        return values.isEmpty() ? null : values.getFirst();
    }
    private static boolean accepts(HttpServletRequest request) {
        try {
            var types = org.springframework.http.MediaType.parseMediaTypes(Collections.list(request.getHeaders("Accept")));
            return types.stream().anyMatch(t -> t.getQualityValue() > 0 && t.getType().equals("application") && t.getSubtype().equals("json"))
                && types.stream().anyMatch(t -> t.getQualityValue() > 0 && t.getType().equals("text") && t.getSubtype().equals("event-stream"));
        } catch (IllegalArgumentException ex) { return false; }
    }
    private synchronized String newSession(String raw, ExecutionBindingService.AuthorizedGrant authorized) {
        Instant now = clock.instant();
        sessions.values().removeIf(s -> !s.expires.isAfter(now));
        if (sessions.size() >= capacity) throw new HttpFailure(503);
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // Absolute deadline from the same locked authorization snapshot; requests never renew it.
        Instant cap = now.plus(Duration.ofMinutes(5));
        Instant expiry = authorized.expiresAt().isBefore(cap) ? authorized.expiresAt() : cap;
        if (!expiry.isAfter(now)) throw new HttpFailure(401);
        sessions.put(id, new Session(hash(raw), authorized.scope(), expiry));
        return id;
    }
    private synchronized Session session(String id, String raw, ExecutionBindingService.Scope scope) {
        if (id == null) throw new HttpFailure(400);
        Session session = sessions.get(id);
        if (session == null || !session.expires.isAfter(clock.instant())) { sessions.remove(id); throw new HttpFailure(404); }
        if (!session.tokenHash.equals(hash(raw)) || !session.scope.equals(scope)) throw new HttpFailure(404);
        return session;
    }
    private void rpcError(HttpServletResponse res, JsonNode id, int code) throws IOException {
        res.setStatus(200); res.setContentType("application/json"); res.setCharacterEncoding("UTF-8");
        json.writeValue(res.getOutputStream(), Map.of("jsonrpc", "2.0", "id", id,
            "error", Map.of("code", code, "message", code == -32601 ? "Method not found" : "Invalid params")));
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(); }
    }
    private void reply(HttpServletResponse res, JsonNode id, Object value) throws IOException {
        res.setStatus(200); res.setContentType("application/json"); res.setCharacterEncoding("UTF-8");
        json.writeValue(res.getOutputStream(), Map.of("jsonrpc", "2.0", "id", id, "result", value));
    }
    static void fail(HttpServletResponse response, int status) throws IOException {
        response.setStatus(status);
        if (status == 401) response.setHeader("WWW-Authenticate", "Bearer");
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"MCP request rejected\"}");
    }
    private static final class Session {
        final String tokenHash; final ExecutionBindingService.Scope scope; final Instant expires;
        volatile boolean ready;
        int toolCalls;
        synchronized void admitTool() { if (++toolCalls > 120) throw new HttpFailure(429); }
        Session(String hash, ExecutionBindingService.Scope scope, Instant expires) {
            this.tokenHash = hash; this.scope = scope; this.expires = expires;
        }
    }
    static final class HttpFailure extends RuntimeException {
        final int status;
        HttpFailure(int status) { super(null, null, false, false); this.status = status; }
    }
}
