package com.haitlify.aitest.platform.assistant.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.filter.GenericFilterBean;

/**
 * Second machine surface: {@code POST /mcp-lease/{leaseId}}.
 *
 * <p>The caller presents <b>only</b> the unguessable lease id in the path; the real grant token is resolved
 * from {@link McpLeaseRegistry} inside this JVM and then handed to {@link McpEndpoint#serveLease}, so
 * authorization, session binding, TTL, protocol validation, tool authorization and every error status are
 * literally the same code as {@code /mcp}. A token travelling in the header here is rejected: the lease is
 * the only credential this path accepts, and it can never widen the grant it was minted for.
 *
 * <p>Disabled means absent: {@code app.assistant.mcp.lease.enabled=false} answers 404 for every method and
 * no registry bean exists at all.
 */
final class McpLeaseEndpoint extends GenericFilterBean {
    static final String PATH = McpLeaseRegistry.PATH;
    private static final String PREFIX = PATH + "/";
    private static final Pattern LEASE_PATH = Pattern.compile(Pattern.quote(PATH) + "/([A-Za-z0-9_-]{43})");
    private static final Pattern LEASE_ID = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final boolean enabled;
    private final McpLeaseRegistry registry;
    private final McpEndpoint delegate;

    McpLeaseEndpoint(boolean enabled, McpLeaseRegistry registry, McpEndpoint delegate) {
        this.enabled = enabled;
        this.registry = registry;
        this.delegate = delegate;
    }

    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException {
        var req = (HttpServletRequest) request;
        var res = (HttpServletResponse) response;
        res.setHeader("Cache-Control", "no-store");
        res.setHeader("X-Content-Type-Options", "nosniff");
        try {
            if (!enabled || registry == null) throw new McpEndpoint.HttpFailure(404);
            if (req.getHeader("Origin") != null) throw new McpEndpoint.HttpFailure(403);
            // The grant must never travel next to a lease: this path's only credential is the path itself.
            if (!Collections.list(req.getHeaders("Authorization")).isEmpty()) throw new McpEndpoint.HttpFailure(401);
            if (req.getHeader("Cookie") != null || req.getQueryString() != null) throw new McpEndpoint.HttpFailure(401);
            String leaseId = leaseId(req);
            var lease = registry.resolve(leaseId).orElseThrow(() -> new McpEndpoint.HttpFailure(401));
            delegate.serveLease(req, res, lease.token());
        } catch (McpEndpoint.HttpFailure ex) { McpEndpoint.fail(res, ex.status); }
          catch (RuntimeException ex) { McpEndpoint.fail(res, 401); }
    }

    private static String leaseId(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String context = req.getContextPath();
        String path = context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
        if (path.indexOf(';') >= 0 || path.contains("//")) throw new McpEndpoint.HttpFailure(404);
        Matcher matcher = LEASE_PATH.matcher(path);
        if (!matcher.matches()) throw new McpEndpoint.HttpFailure(404);
        String id = matcher.group(1);
        if (!LEASE_ID.matcher(id).matches()) throw new McpEndpoint.HttpFailure(404);
        return id;
    }

    static boolean matches(String pathWithinApplication) {
        return pathWithinApplication.equals(PATH) || pathWithinApplication.startsWith(PREFIX);
    }
}
