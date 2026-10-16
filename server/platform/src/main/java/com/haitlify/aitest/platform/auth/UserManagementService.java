package com.haitlify.aitest.platform.auth;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 多用户与角色管理。角色为层级模型:ADMIN 全部权限,EDITOR 可读写,VIEWER 只读。
 * 服务层只保护「锁死」场景(最后一个可用管理员、本人账号),细粒度权限由 SecurityConfig 的 URL 规则强制。
 */
@Service
@Transactional
class UserManagementService {

    static final Set<String> VALID_ROLES = Set.of("ADMIN", "EDITOR", "VIEWER");
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final PlatformUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    UserManagementService(PlatformUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    List<UserSummaryResponse> list() {
        List<PlatformUser> users = new java.util.ArrayList<>();
        userRepository.findAll().forEach(users::add);
        return users.stream()
            .sorted(Comparator.comparing(PlatformUser::getUsername))
            .map(this::toResponse)
            .toList();
    }

    UserSummaryResponse create(CreateUserRequest request) {
        String username = trim(request.username());
        if (username == null || username.isBlank() || username.length() > 64) {
            throw new IllegalArgumentException("用户名不能为空且不超过 64 个字符");
        }
        if (request.password() == null || request.password().length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("初始密码长度至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        String role = roleOf(request.role());
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("用户名已存在");
        }
        Instant now = Instant.now();
        PlatformUser user = new PlatformUser(
            null,
            username,
            passwordEncoder.encode(request.password()),
            trim(request.displayName()),
            trim(request.avatar()),
            role,
            true,
            true,
            now,
            now
        );
        return toResponse(userRepository.save(user));
    }

    UserSummaryResponse update(String username, UpdateUserRequest request) {
        PlatformUser user = requireUser(username);
        String role = request.role() == null ? user.getRole() : roleOf(request.role());
        boolean enabled = request.enabled() == null ? user.isEnabled() : request.enabled();
        String passwordHash = user.getPasswordHash();
        if (request.password() != null) {
            if (request.password().length() < MIN_PASSWORD_LENGTH) {
                throw new IllegalArgumentException("新密码长度至少 " + MIN_PASSWORD_LENGTH + " 位");
            }
            passwordHash = passwordEncoder.encode(request.password());
        }
        // 防止锁死:最后一个可用管理员不能被降级或停用,本人也不能被删除/停用(除非先另设管理员)。
        if (user.isEnabled() && user.getRole().equals("ADMIN") && (!enabled || !role.equals("ADMIN"))) {
            ensureAnotherEnabledAdmin(username);
        }
        if (user.getUsername().equals(currentUsername()) && !enabled) {
            throw new IllegalArgumentException("不能停用当前登录的账号");
        }
        user.updateProfile(
            request.displayName() == null ? user.getDisplayName() : trim(request.displayName()),
            request.avatar() == null ? user.getAvatar() : trim(request.avatar()),
            role,
            enabled,
            passwordHash,
            request.password() != null || user.isMustChangePassword(),
            Instant.now()
        );
        return toResponse(userRepository.save(user));
    }

    void delete(String username) {
        PlatformUser user = requireUser(username);
        if (user.getUsername().equals(currentUsername())) {
            throw new IllegalArgumentException("不能删除当前登录的账号");
        }
        if (user.isEnabled() && user.getRole().equals("ADMIN")) {
            ensureAnotherEnabledAdmin(username);
        }
        userRepository.delete(user);
    }

    private String roleOf(String role) {
        if (role == null || !VALID_ROLES.contains(role)) {
            throw new IllegalArgumentException("角色必须是 ADMIN / EDITOR / VIEWER 之一");
        }
        return role;
    }

    private void ensureAnotherEnabledAdmin(String exceptUsername) {
        List<PlatformUser> users = new java.util.ArrayList<>();
        userRepository.findAll().forEach(users::add);
        long otherEnabledAdmins = users.stream()
            .filter(other -> !other.getUsername().equals(exceptUsername))
            .filter(PlatformUser::isEnabled)
            .filter(other -> other.getRole().equals("ADMIN"))
            .count();
        if (otherEnabledAdmins == 0) {
            throw new IllegalArgumentException("平台必须保留至少一个可用的管理员");
        }
    }

    private PlatformUser requireUser(String username) {
        return userRepository.findByUsername(username)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private UserSummaryResponse toResponse(PlatformUser user) {
        return new UserSummaryResponse(
            user.getUsername(),
            user.getDisplayName(),
            user.getAvatar(),
            user.getRole(),
            user.isEnabled(),
            user.isMustChangePassword(),
            user.getCreatedAt().toString()
        );
    }
}

record CreateUserRequest(String username, String password, String displayName, String avatar, String role) {
}

record UpdateUserRequest(String displayName, String avatar, String role, Boolean enabled, String password) {
}

record UserSummaryResponse(String username, String displayName, String avatar, String role, boolean enabled, boolean mustChangePassword, String createdAt) {
}
