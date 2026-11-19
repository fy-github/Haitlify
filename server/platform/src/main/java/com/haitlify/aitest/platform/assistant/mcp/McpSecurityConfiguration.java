package com.haitlify.aitest.platform.assistant.mcp;

import java.time.Clock;
import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

/** Separate machine surface. Never reuse the application's browser authentication or CSRF policy. */
@Configuration(proxyBeanMethods = false)
public class McpSecurityConfiguration {

    /**
     * {@link McpEndpoint} is a {@link jakarta.servlet.Filter}, so exposing it as a bean would make Spring Boot
     * map it to {@code /*} as an ordinary servlet filter — outside its security chain and before
     * {@code /api/**}. Disable that registration: the endpoint only ever runs inside the machine chains.
     */
    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<McpEndpoint> mcpEndpointServletRegistration(McpEndpoint endpoint) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(endpoint);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    McpEndpoint mcpEndpoint(
            com.haitlify.aitest.platform.assistant.execution.ExecutionBindingService execution,
            com.haitlify.aitest.platform.assistant.generation.tool.GenerationDraftTools tools,
            @org.springframework.beans.factory.annotation.Value("${app.assistant.mcp.enabled:false}") boolean enabled) {
        return new McpEndpoint(enabled, execution, tools);
    }

    /**
     * The lease registry only exists when the lease path is enabled. Nothing is created, and therefore
     * nothing can hold a grant token in memory, while {@code app.assistant.mcp.lease.enabled} is false.
     */
    @Bean
    @ConditionalOnProperty(name = "app.assistant.mcp.lease.enabled", havingValue = "true")
    McpLeaseRegistry mcpLeaseRegistry(
            org.springframework.beans.factory.ObjectProvider<Clock> clocks,
            @org.springframework.beans.factory.annotation.Value("${app.assistant.mcp.lease.ttl-seconds:300}") long ttlSeconds,
            @org.springframework.beans.factory.annotation.Value("${app.assistant.mcp.lease.capacity:256}") int capacity) {
        if (ttlSeconds < 1 || ttlSeconds > 300) throw new IllegalArgumentException("Lease TTL must be between 1 and 300 seconds");
        if (capacity < 1 || capacity > 4096) throw new IllegalArgumentException("Lease capacity must be between 1 and 4096");
        return new McpLeaseRegistry(capacity, clocks.getIfAvailable(Clock::systemUTC), Duration.ofSeconds(ttlSeconds));
    }

    @Bean
    @Order(-100)
    SecurityFilterChain mcpSecurityChain(HttpSecurity http, McpEndpoint endpoint) throws Exception {
        harden(http.securityMatcher(request -> request.getRequestURI().equals(request.getContextPath() + "/mcp")))
            .addFilterBefore(endpoint, SecurityContextHolderFilter.class);
        return http.build();
    }

    /**
     * Always registered so a disabled lease path is indistinguishable from a non-existent one (404), instead
     * of falling through to the browser chain's 401/403.
     */
    @Bean
    @Order(-101)
    SecurityFilterChain mcpLeaseSecurityChain(HttpSecurity http, McpEndpoint endpoint,
            org.springframework.beans.factory.ObjectProvider<McpLeaseRegistry> registries,
            @org.springframework.beans.factory.annotation.Value("${app.assistant.mcp.lease.enabled:false}") boolean leaseEnabled)
            throws Exception {
        harden(http.securityMatcher(request -> {
                String context = request.getContextPath();
                String uri = request.getRequestURI();
                String path = context != null && !context.isEmpty() && uri.startsWith(context) ? uri.substring(context.length()) : uri;
                return McpLeaseEndpoint.matches(path);
            }))
            .addFilterBefore(new McpLeaseEndpoint(leaseEnabled, registries.getIfAvailable(), endpoint),
                SecurityContextHolderFilter.class);
        return http.build();
    }

    /** Same isolation for both paths: no CSRF, no ambient principal, no browser session, no cache. */
    private static HttpSecurity harden(HttpSecurity http) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(AbstractHttpConfigurer::disable)
            .securityContext(AbstractHttpConfigurer::disable)
            .requestCache(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable);
    }
}
