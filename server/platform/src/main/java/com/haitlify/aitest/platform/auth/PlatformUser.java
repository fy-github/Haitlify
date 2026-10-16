package com.haitlify.aitest.platform.auth;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("platform_users")
class PlatformUser {

    @Id
    @Column("id")
    private Long id;
    @Column("username")
    private String username;
    @Column("password_hash")
    private String passwordHash;
    @Column("display_name")
    private String displayName;
    @Column("avatar")
    private String avatar;
    @Column("role")
    private String role;
    @Column("enabled")
    private boolean enabled;
    @Column("must_change_password")
    private boolean mustChangePassword;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    PlatformUser() {
    }

    PlatformUser(
        Long id,
        String username,
        String passwordHash,
        String displayName,
        String avatar,
        String role,
        boolean enabled,
        boolean mustChangePassword,
        Instant createdAt,
        Instant updatedAt
    ) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.avatar = avatar;
        this.role = role;
        this.enabled = enabled;
        this.mustChangePassword = mustChangePassword;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    void updatePassword(String newPasswordHash, Instant now) {
        this.passwordHash = newPasswordHash;
        this.updatedAt = now;
        this.mustChangePassword = false;
    }

    void updateProfile(String displayName, String avatar, String role, boolean enabled, String passwordHash, boolean mustChangePassword, Instant now) {
        this.displayName = displayName;
        this.avatar = avatar;
        this.role = role;
        this.enabled = enabled;
        this.passwordHash = passwordHash;
        this.mustChangePassword = mustChangePassword;
        this.updatedAt = now;
    }

    Long getId() {
        return id;
    }

    String getUsername() {
        return username;
    }

    String getPasswordHash() {
        return passwordHash;
    }

    String getDisplayName() {
        return displayName;
    }

    String getAvatar() {
        return avatar;
    }

    String getRole() {
        return role;
    }

    boolean isEnabled() {
        return enabled;
    }

    boolean isMustChangePassword() {
        return mustChangePassword;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
