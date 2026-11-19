package com.haitlify.aitest.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 初始管理员密码不得落日志：日志进聚合器/CI 输出/支持包之后，一次性引导密码就变成长期凭据。
 * 这里断言日志里没有密码、密码只出现在 0600 文件里，且该文件内容确实能通过库中哈希校验。
 */
class BootstrapAdminInitializerTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void generatedPasswordGoesToAOwnerOnlyFileAndNeverToTheLog(@TempDir Path dir) throws Exception {
        PlatformUserRepository repository = mock(PlatformUserRepository.class);
        when(repository.findByUsername("admin")).thenReturn(Optional.empty());
        Path file = dir.resolve("nested/bootstrap-admin-password");
        BootstrapAdminInitializer initializer = new BootstrapAdminInitializer(repository, encoder, "", file.toString());

        String logOutput = runCapturingLog(initializer);

        PlatformUser saved = captureSaved(repository);
        assertThat(Files.exists(file)).isTrue();
        String onDisk = Files.readString(file, StandardCharsets.UTF_8).strip();
        assertThat(onDisk).hasSize(16);
        assertThat(encoder.matches(onDisk, saved.getPasswordHash())).isTrue();
        if (supportsPosix(file)) {
            assertThat(Files.getPosixFilePermissions(file))
                .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        }
        assertThat(logOutput).doesNotContain(onDisk);
        assertThat(logOutput).contains(file.toString());
    }

    @Test
    void suppliedPasswordIsUsedWithoutWritingAFileOrAppearingInTheLog(@TempDir Path dir) {
        PlatformUserRepository repository = mock(PlatformUserRepository.class);
        when(repository.findByUsername("admin")).thenReturn(Optional.empty());
        Path file = dir.resolve("unused-password-file");
        String supplied = "Supplied-Passw0rd-9";
        BootstrapAdminInitializer initializer = new BootstrapAdminInitializer(repository, encoder, supplied, file.toString());

        String logOutput = runCapturingLog(initializer);

        PlatformUser saved = captureSaved(repository);
        assertThat(encoder.matches(supplied, saved.getPasswordHash())).isTrue();
        assertThat(Files.exists(file)).isFalse();
        assertThat(logOutput).doesNotContain(supplied);
    }

    @Test
    void anExistingAdminSeedsNothing(@TempDir Path dir) {
        PlatformUserRepository repository = mock(PlatformUserRepository.class);
        when(repository.findByUsername("admin")).thenReturn(Optional.of(new PlatformUser(
            1L, "admin", "hash", "管理员", "", "ADMIN", true, true, null, null)));
        Path file = dir.resolve("password-file");
        BootstrapAdminInitializer initializer = new BootstrapAdminInitializer(repository, encoder, "", file.toString());

        runCapturingLog(initializer);

        verify(repository, never()).save(any());
        assertThat(Files.exists(file)).isFalse();
    }

    private String runCapturingLog(BootstrapAdminInitializer initializer) {
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BootstrapAdminInitializer.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            initializer.run(new DefaultApplicationArguments());
        } finally {
            logger.detachAppender(appender);
        }
        StringBuilder text = new StringBuilder();
        appender.list.forEach(event -> text.append(event.getFormattedMessage()).append('\n'));
        return text.toString();
    }

    private PlatformUser captureSaved(PlatformUserRepository repository) {
        ArgumentCaptor<PlatformUser> captor = ArgumentCaptor.forClass(PlatformUser.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private static boolean supportsPosix(Path path) {
        try {
            return Files.getFileStore(path).supportsFileAttributeView("posix");
        } catch (Exception ex) {
            return false;
        }
    }
}
