package com.haitlify.aitest.platform.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * T01：版本化连接/档案注册表——追加式版本、历史快照不可覆盖、乐观并发 409、幂等读取、
 * 未知能力 fail-closed、并发注册版本唯一。
 */
@SpringBootTest
@ActiveProfiles("test")
class AiModelProfileServiceTest {

    @Autowired
    private AiConnectionService connectionService;

    @Autowired
    private AiModelProfileService profileService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        jdbcTemplate.update("delete from ai_model_profiles");
        jdbcTemplate.update("delete from ai_connections");
    }

    @Test
    void registrationsAppendVersionsAndHistoryStaysReadable() {
        connectionService.register("conn-a", "https://a.test/v1", "alias:a", "chat_completions", true,
            List.of(1L));
        var second = connectionService.register("conn-a", "https://a.test/v2", "alias:a", "CHAT_COMPLETIONS",
            true, List.of(1L, 2L));

        assertThat(second.version()).isEqualTo(2);
        assertThat(connectionService.latest("conn-a").baseUrl()).isEqualTo("https://a.test/v2");
        // 历史版本不可被新版本覆盖（回滚读取窗口）。
        assertThat(connectionService.at("conn-a", 1).baseUrl()).isEqualTo("https://a.test/v1");
        assertThat(connectionService.at("conn-a", 1).allowedProjectIds()).containsExactly(1L);
        assertThat(connectionService.list()).hasSize(1);
    }

    @Test
    void profilesRequireAnExistingConnectionAndKeepPerVersionReads() {
        assertThatThrownBy(() -> profileService.register("p-orphan", "missing-conn", "m", List.of(), "{}", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("connection not found");

        connectionService.register("conn-a", "https://a.test/v1", "alias:a", "CHAT_COMPLETIONS", true, null);
        profileService.register("p-a", "conn-a", "model-1", List.of("TEXT"), "{}", null);
        profileService.register("p-a", "conn-a", "model-2", List.of("TEXT", "JSON_OBJECT"), "{}", null);

        assertThat(profileService.latest("p-a").modelId()).isEqualTo("model-2");
        // 旧版本仍可按版本复读（历史快照不被新 profile 更新覆盖）。
        assertThat(profileService.at("p-a", 1).modelId()).isEqualTo("model-1");
        assertThat(profileService.at("p-a", 1).capabilities()).containsExactly("TEXT");
    }

    @Test
    void twoConnectionsSameModelIdAndOneConnectionManyProfilesAreAllowed() {
        connectionService.register("conn-a", "https://a.test/v1", "alias:a", "CHAT_COMPLETIONS", true, null);
        connectionService.register("conn-b", "https://b.test/v1", "alias:b", "RESPONSES", true, null);
        profileService.register("p-a1", "conn-a", "shared-model", List.of("TEXT"), "{}", null);
        profileService.register("p-b1", "conn-b", "shared-model", List.of("TEXT"), "{}", null);
        profileService.register("p-a2", "conn-a", "second-model", List.of("TEXT"), "{}", null);

        assertThat(profileService.latest("p-a1").modelId()).isEqualTo("shared-model");
        assertThat(profileService.latest("p-b1").connectionId()).isEqualTo("conn-b");
        assertThat(profileService.latest("p-a2").connectionId()).isEqualTo("conn-a");
    }

    @Test
    void staleExpectedVersionIsA409() {
        connectionService.register("conn-a", "https://a.test/v1", "alias:a", "CHAT_COMPLETIONS", true, null);

        assertThatThrownBy(() -> connectionService.register("conn-a", "https://a.test/v2", "alias:a",
            "CHAT_COMPLETIONS", true, null, 5))
            .isInstanceOf(ConfigurationVersionConflictException.class)
            .hasMessageContaining("expected 5");
        assertThat(ConfigurationVersionConflictException.class.getAnnotation(ResponseStatus.class).value())
            .isEqualTo(HttpStatus.CONFLICT);

        var ok = connectionService.register("conn-a", "https://a.test/v2", "alias:a", "CHAT_COMPLETIONS",
            true, null, 1);
        assertThat(ok.version()).isEqualTo(2);
    }

    @Test
    void unknownCapabilitiesAreRejectedAndEmptyMeansUnknownNotAssumed() {
        connectionService.register("conn-a", "https://a.test/v1", "alias:a", "CHAT_COMPLETIONS", true, null);

        assertThatThrownBy(() -> profileService.register("p-bad", "conn-a", "m",
            List.of("TEXT", "IMAGINARY_CAPABILITY"), "{}", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown capability");

        profileService.register("p-empty", "conn-a", "m", List.of(), "{}", null);
        var profile = profileService.latest("p-empty");
        assertThat(profile.capabilities()).isEmpty();
        assertThat(profile.verificationJson()).contains("UNKNOWN");
    }

    @Test
    void credentialRefsAreOpaqueAndStoredVerbatim() {
        connectionService.register("conn-a", "https://a.test/v1", "alias:totally-opaque-ref", "CHAT_COMPLETIONS",
            true, null);
        assertThat(connectionService.latest("conn-a").credentialRef()).isEqualTo("alias:totally-opaque-ref");

        assertThatThrownBy(() -> connectionService.register("conn-b", "https://b.test/v1", " ",
            "CHAT_COMPLETIONS", true, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("credentialRef");

        assertThatThrownBy(() -> connectionService.register("conn-c", "https://c.test/v1", "ref",
            "GRPC_FAKE", true, null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("unknown protocol");
    }

    @Test
    void concurrentRegistrationsNeverShareAVersion() throws Exception {
        connectionService.register("race-conn", "https://r.test/v1", "alias:r", "CHAT_COMPLETIONS", true, null);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Integer> versions = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < threads; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    versions.add(connectionService.register("race-conn", "https://r.test/v" + (index + 2),
                        "alias:r", "CHAT_COMPLETIONS", true, null).version());
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // 成功者版本两两不同；冲突只允许是 409 语义异常（Clean conflict, not corruption）。
        assertThat(Set.copyOf(versions)).hasSize(versions.size());
        assertThat(failures).allSatisfy(failure ->
            assertThat(failure).isInstanceOf(ConfigurationVersionConflictException.class));
        assertThat(versions).isNotEmpty();
        assertThat(connectionService.latest("race-conn").version()).isEqualTo(1 + versions.size());
    }
}
