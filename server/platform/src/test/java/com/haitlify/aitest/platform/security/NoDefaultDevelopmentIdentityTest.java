package com.haitlify.aitest.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * T-R5 轮 2 回归（复核发现 5）：主代码与迁移里不得再存在可冒充的默认开发身份。
 * 只扫字面量/DDL（注释中的历史说明不算）；身份必须来自 {@link ActorResolver}。
 */
class NoDefaultDevelopmentIdentityTest {

    private static final Pattern QUOTED_LITERAL = Pattern.compile("\"system-dev\"|'system-dev'|\"Development User\"|'Development User'");

    @Test
    void mainSourcesAndMigrationsContainNoDefaultDevelopmentIdentity() throws IOException {
        List<String> offenders = new ArrayList<>();
        Path root = Path.of("src/main");
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                String name = path.toString();
                if (!name.endsWith(".java") && !name.endsWith(".sql")) {
                    continue;
                }
                // 本测试类自身允许引用；历史迁移（V1–V47）按纪律 byte-exact 不得改写，
                // 其默认值由前向迁移 V48 修正——扫描只对 V48+ 与 Java 主代码生效。
                if (name.contains("NoDefaultDevelopmentIdentityTest")) {
                    continue;
                }
                if (name.contains("/db/migration/")) {
                    java.util.regex.Matcher version = java.util.regex.Pattern
                        .compile("V(\\d+)__").matcher(name);
                    if (version.find() && Integer.parseInt(version.group(1)) <= 47) {
                        continue;
                    }
                }
                String content = Files.readString(path, StandardCharsets.UTF_8);
                Matcher matcher = QUOTED_LITERAL.matcher(content);
                while (matcher.find()) {
                    offenders.add(name + ": " + matcher.group());
                }
            }
        }
        assertThat(offenders)
            .describedAs("主代码/迁移不得含默认开发身份字面量（应为 ActorResolver.currentOrSystem() 或 'system'）")
            .isEmpty();
    }

    @Test
    void actorResolverNeverReturnsTheLegacyIdentity() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        assertThat(ActorResolver.currentOrSystem()).isEqualTo("system");
    }
}
