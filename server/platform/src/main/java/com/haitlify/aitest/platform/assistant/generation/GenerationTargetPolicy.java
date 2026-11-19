package com.haitlify.aitest.platform.assistant.generation;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Admission syntax policy, aligned with AssistantScriptCompiler's private host guard.
 * That guard is private and includes compile-only requirements, so it is not called
 * with a fabricated CaseSpec. No DNS, HTTP, browser or other network I/O here.
 * A future worker MUST resolve and pin public addresses and recheck redirects at
 * connection time: accepting a hostname is NOT an SSRF-safe network grant.
 * Unlike the legacy compiler, admission never honors allow-private-hosts.
 */
final class GenerationTargetPolicy {
    private GenerationTargetPolicy() {}

    static void validate(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost() == null || uri.getRawUserInfo() != null
                || value.matches(".*[\\s\\\\\"'<>].*") || value.chars().anyMatch(c -> c < 32 || c == 127)
                || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawAuthority().endsWith(":")) throw new IllegalArgumentException();
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            // Conservative: IPv6 literals (including mapped/zone forms) are not admitted.
            if (host.contains(":") || !host.contains(".") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.endsWith(".internal")
                || host.endsWith(".nip.io") || host.endsWith(".sslip.io")) throw new IllegalArgumentException();
            String[] parts = host.split("\\.");
            // WHATWG accepts decimal/octal/hex and shortened IPv4. Require canonical
            // dotted decimal for every hostname whose final label is numeric.
            String last = parts[parts.length - 1];
            if (last.matches("[0-9]+|0x[0-9a-f]+")) {
                if (parts.length != 4) throw new IllegalArgumentException();
                int[] octets = new int[4];
                for (int i = 0; i < 4; i++) {
                    if (!parts[i].matches("0|[1-9][0-9]{0,2}")) throw new IllegalArgumentException();
                    octets[i] = Integer.parseInt(parts[i]);
                    if (octets[i] > 255) throw new IllegalArgumentException();
                }
                int a = octets[0], b = octets[1];
                if (a == 0 || a == 10 || a == 127 || a >= 224
                    || a == 169 && b == 254 || a == 172 && b >= 16 && b <= 31
                    || a == 192 && b == 168 || a == 100 && b >= 64 && b <= 127
                    || a == 198 && (b == 18 || b == 19)) throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid generation target");
        }
    }
}
