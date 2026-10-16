package com.haitlify.aitest.platform.auth;

import java.security.SecureRandom;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Seeds the single admin account on first startup. The generated password is printed to the log
 * once (the only bootstrap mechanism that does not require editing any config file); the admin can
 * change it afterwards through the UI.
 */
@Component
class BootstrapAdminInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminInitializer.class);
    private static final String DEFAULT_USERNAME = "admin";
    private static final int PASSWORD_LENGTH = 16;
    private static final String PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final PlatformUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    BootstrapAdminInitializer(PlatformUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.findByUsername(DEFAULT_USERNAME).isPresent()) {
            return;
        }
        String password = generatePassword();
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
        log.info("  初始密码: {}", password);
        log.info("  首次登录后必须在 UI 中修改密码。");
        log.info("============================================================");
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
