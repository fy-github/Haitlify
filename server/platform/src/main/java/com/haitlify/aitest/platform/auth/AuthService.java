package com.haitlify.aitest.platform.auth;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional
class AuthService {

    private final AuthenticationManager authenticationManager;
    private final PlatformUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    AuthService(AuthenticationManager authenticationManager, PlatformUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    Authentication authenticate(String username, String password) {
        return authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, password));
    }

    AuthUserResponse currentUser() {
        return toResponse(requireCurrentUser());
    }

    AuthUserResponse changePassword(ChangePasswordRequest request) {
        PlatformUser user = requireCurrentUser();
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("当前密码不正确");
        }
        if (request.newPassword() == null || request.newPassword().length() < 8) {
            throw new IllegalArgumentException("新密码长度至少 8 位");
        }
        user.updatePassword(passwordEncoder.encode(request.newPassword()), Instant.now());
        userRepository.save(user);
        return toResponse(user);
    }

    private PlatformUser requireCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return userRepository.findByUsername(authentication.getName())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
    }

    private AuthUserResponse toResponse(PlatformUser user) {
        return new AuthUserResponse(user.getUsername(), user.getDisplayName(), user.getAvatar(), user.getRole(), user.isMustChangePassword());
    }
}

record LoginRequest(String username, String password) {
}

record AuthUserResponse(String username, String displayName, String avatar, String role, boolean mustChangePassword) {
}

record ChangePasswordRequest(String currentPassword, String newPassword) {
}
