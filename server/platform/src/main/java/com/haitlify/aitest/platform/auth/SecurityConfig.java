package com.haitlify.aitest.platform.auth;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
@EnableWebSecurity
class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private static final String[] PUBLIC_PATHS = {
        "/api/auth/login",
        "/api/system/health",
        "/actuator/health",
        "/actuator/info"
    };

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(PlatformUserRepository userRepository) {
        return username -> userRepository.findByUsername(username)
            .filter(PlatformUser::isEnabled)
            .map(user -> User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .roles(user.getRole())
                .build())
            .orElseThrow(() -> new UsernameNotFoundException("用户不存在"));
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        ObjectMapper objectMapper,
        @Value("${app.security.enabled:true}") boolean securityEnabled
    ) throws Exception {
        if (!securityEnabled) {
            log.warn("app.security.enabled=false —— 认证与 CSRF 已全部关闭，所有 /api/** 端点无需登录即可访问。仅用于本地开发/测试，切勿在生产环境使用。");
            http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_PATHS).permitAll()
                // 用户管理仅管理员可见;状态变更统一要求 EDITOR/ADMIN(VIEWER 只读)
                .requestMatchers("/api/users/**").hasRole("ADMIN")
                // 租户/成员运维面同样只给 ADMIN：普通成员不能给自己加租户或改成员关系
                .requestMatchers("/api/tenants/**").hasRole("ADMIN")
                // 登出与修改本人密码对任意角色开放,须先于 /api/** 的写规则匹配
                .requestMatchers(HttpMethod.POST, "/api/auth/**").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/auth/**").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/auth/**").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/**").hasAnyRole("EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/**").hasAnyRole("EDITOR", "ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/**").hasAnyRole("EDITOR", "ADMIN")
                .anyRequest().authenticated())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
            .formLogin(AbstractHttpConfigurer::disable)
            .httpBasic(AbstractHttpConfigurer::disable)
            .logout(AbstractHttpConfigurer::disable)
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) ->
                    writeJsonError(response, 401, "未登录或会话已过期", objectMapper))
                .accessDeniedHandler((request, response, exception) ->
                    writeJsonError(response, 403, "无权限执行此操作", objectMapper)));

        return http.build();
    }

    private static void writeJsonError(jakarta.servlet.http.HttpServletResponse response, int status, String message, ObjectMapper objectMapper) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(), java.util.Map.of("message", message));
    }
}
