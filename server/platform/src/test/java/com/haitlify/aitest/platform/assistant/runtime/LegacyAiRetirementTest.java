package com.haitlify.aitest.platform.assistant.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 旧模型循环必须从生产源码退出，而不是靠隐藏菜单或关闭开关掩盖。 */
class LegacyAiRetirementTest {
    @Test
    void productionSourcesContainNoLegacyAiPackageOrImports() throws Exception {
        Path root = Path.of("src/main/java/com/haitlify/aitest/platform");
        try (var files = Files.walk(root)) {
            var offenders = files.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> {
                    try {
                        return p.startsWith(root.resolve("ai"))
                            || Files.readString(p).contains("com.haitlify.aitest.platform.ai.");
                    } catch (java.io.IOException ex) {
                        throw new java.io.UncheckedIOException(ex);
                    }
                }).map(Path::toString).toList();
            assertThat(offenders).isEmpty();
        }
    }
}
