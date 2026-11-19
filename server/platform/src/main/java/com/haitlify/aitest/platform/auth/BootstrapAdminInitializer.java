package com.haitlify.aitest.platform.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Seeds the single admin account on first startup.
 *
 * <p>The generated password is <b>never logged</b>: a log line survives in aggregators, CI output and
 * support bundles long after the operator changed the password, so printing it there converts a
 * one-time bootstrap secret into a permanent credential leak. Instead the password is written to a
 * 0600 file and only the path is logged; {@code APP_BOOTSTRAP_ADMIN_PASSWORD} may supply it
 * explicitly, in which case no file is written. The admin changes it through the UI on first login.
 */
@Component
class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);
    private static final String DEFAULT_USERNAME = "admin";
    private static final int PASSWORD_LENGTH = 16;
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final String DEFAULT_FILE_NAME = ".haitlify/bootstrap-admin-password";

    private final PlatformUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String suppliedPassword;
    private final Path passwordFile;

    BootstrapAdminInitializer(PlatformUserRepository userRepository, PasswordEncoder passwordEncoder,
                              @Value("${app.auth.bootstrap-password:}") String suppliedPassword,
                              @Value("${app.auth.bootstrap-password-file:}") String passwordFile) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.suppliedPassword = suppliedPassword == null ? "" : suppliedPassword.trim();
        this.passwordFile = StringUtils.hasText(passwordFile)
            ? Path.of(passwordFile.trim())
            : Path.of(System.getProperty("user.home"), DEFAULT_FILE_NAME);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.findByUsername(DEFAULT_USERNAME).isPresent()) {
            return;
        }
        boolean supplied = !suppliedPassword.isEmpty();
        String password = supplied ? suppliedPassword : generatePassword();
        Instant now = Instant.now();
        userRepository.save(new PlatformUser(
            null,
            DEFAULT_USERNAME,
            passwordEncoder.encode(password),
            "管理员",
            "",
            "ADMIN",
            true,
            true,
            now,
            now
        ));
        log.info("============================================================");
        log.info("已创建初始管理员账号");
        log.info("  用户名: {}", DEFAULT_USERNAME);
        if (supplied) {
            log.info("  初始密码: 来自 APP_BOOTSTRAP_ADMIN_PASSWORD（不落日志）");
        } else {
            writePasswordFile(password);
            log.info("  初始密码: 已写入 {}（权限 0600，不落日志）", passwordFile);
        }
        log.info("  首次登录后必须在 UI 中修改密码。");
        log.info("============================================================");
    }

    private void writePasswordFile(String password) {
        Path parent = passwordFile.toAbsolutePath().getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
                restrictDirectory(parent);
            }
            Path tmp = Files.createTempFile(parent, ".bootstrap-admin-", ".tmp");
            try {
                Files.writeString(tmp, password + System.lineSeparator(), StandardCharsets.UTF_8);
                restrictFile(tmp);
                Files.move(tmp, passwordFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
            restrictFile(passwordFile);
        } catch (IOException ex) {
            // Fail closed rather than fall back to logging the secret.
            throw new IllegalStateException("无法写入初始管理员密码文件: " + passwordFile, ex);
        }
    }

    private static void restrictFile(Path path) throws IOException {
        if (isPosix(path)) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        }
    }

    private static void restrictDirectory(Path path) {
        try {
            if (isPosix(path)) {
                Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory permissions are best-effort; the file itself is 0600.
        }
    }

    private static boolean isPosix(Path path) {
        try {
            return Files.getFileStore(path).supportsFileAttributeView("posix");
        } catch (IOException ex) {
            return false;
        }
    }

    private String generatePassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            password.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return password.toString();
    }
}
