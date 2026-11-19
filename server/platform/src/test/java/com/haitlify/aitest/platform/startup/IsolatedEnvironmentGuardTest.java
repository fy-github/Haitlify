package com.haitlify.aitest.platform.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class IsolatedEnvironmentGuardTest {

    @TempDir
    static Path tempDir;

    private static final String VALID_HASH = "1f4f67b26bf676356a00ace2a0a0a6b43536eae6ef7619c0f515ed2474bd4bef";
    private static final String VALID_URL =
            "jdbc:mysql://mysql:3306/haitlify_isolated?useUnicode=true&characterEncoding=utf8";

    private static Map<String, String> urls(String url) {
        Map<String, String> urls = new LinkedHashMap<>();
        urls.put(IsolatedEnvironmentGuard.DATASOURCE_URL_PROPERTY, url);
        return urls;
    }

    private static List<String> violations(String url) {
        return IsolatedEnvironmentGuard.violations("haitlify-isolated", VALID_HASH, urls(url));
    }

    /** 写一份合法的预检结果文件（供启动保护复验）。 */
    private static String validResultPath() {
        return writeResult("PASS", VALID_HASH);
    }

    private static String writeResult(String verdict, String hash) {
        try {
            Path file = Files.createTempFile(tempDir, "preflight", ".json");
            Files.writeString(file, "{\n  \"verdict\": \"" + verdict + "\",\n"
                    + "  \"resolvedConfigHash\": \"" + hash + "\"\n}\n");
            return file.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    @Test
    void acceptsIsolatedEnvironment() {
        assertTrue(violations(VALID_URL).isEmpty());
    }

    @Test
    void acceptsIsolatedHostSideTargetOnly() {
        assertTrue(violations("jdbc:mysql://127.0.0.1:13306/haitlify_isolated").isEmpty());
        assertTrue(violations("jdbc:mysql://localhost:13306/haitlify_isolated").isEmpty());
    }

    @Test
    void rejectsContainerNameAsDatabaseTarget() {
        // 容器名不是服务名，可被同网其它容器抢占（第四轮复核实测的绕过面）。
        List<String> violations = violations("jdbc:mysql://haitlify-isolated-mysql:3306/haitlify_isolated");
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("不是隔离目标"));
    }

    @Test
    void acceptsCaseAndSubprotocolVariantsOfTheIsolatedTarget() {
        assertTrue(violations("JDBC:MYSQL://MYSQL:3306/haitlify_isolated").isEmpty());
        assertTrue(violations("jdbc:mysql:loadbalance://mysql:3306,127.0.0.1:13306/haitlify_isolated")
                .isEmpty());
    }

    @Test
    void rejectsMissingEnvId() {
        List<String> violations = IsolatedEnvironmentGuard.violations(null, VALID_HASH, urls(VALID_URL));
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("APP_ENV_ID"));
    }

    @Test
    void rejectsForeignEnvId() {
        List<String> violations = IsolatedEnvironmentGuard.violations("local-dev", VALID_HASH, urls(VALID_URL));
        assertTrue(violations.get(0).contains("不是隔离环境标识 haitlify-isolated"));
    }

    @Test
    void rejectsMissingPreflightHash() {
        List<String> violations = IsolatedEnvironmentGuard.violations("haitlify-isolated", "  ", urls(VALID_URL));
        assertTrue(violations.get(0).contains("APP_ENV_PREFLIGHT_HASH"));
    }

    @Test
    void rejectsMalformedPreflightHash() {
        List<String> violations = IsolatedEnvironmentGuard.violations("haitlify-isolated", "not-a-hash", urls(VALID_URL));
        assertTrue(violations.get(0).contains("64 位十六进制"));
    }

    @Test
    void rejectsLegacyDatabaseTarget() {
        List<String> violations = violations("jdbc:mysql://127.0.0.1:3306/ai_test_platform?useUnicode=true");
        assertTrue(violations.stream().anyMatch(message -> message.contains("旧资源标识 ai_test_platform")));
        assertTrue(violations.stream().anyMatch(message -> message.contains("期望 haitlify_isolated")));
    }

    @Test
    void rejectsLegacyHostToken() {
        List<String> violations = violations("jdbc:mysql://cloudv4-api-mysql:3306/haitlify_isolated");
        assertTrue(violations.stream().anyMatch(message -> message.contains("旧资源标识 cloudv4")));
    }

    @Test
    void rejectsForeignDatabaseName() {
        List<String> violations = violations("jdbc:mysql://mysql:3306/haitlify");
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("目标库为 haitlify"));
    }

    @Test
    void rejectsLegacyHostPortEvenWhenDatabaseNameIsIsolated() {
        List<String> violations = violations("jdbc:mysql://127.0.0.1:3306/haitlify_isolated");
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("127.0.0.1:3306 不是隔离目标"));
    }

    @Test
    void rejectsForeignHostOnAnyPort() {
        List<String> violations = violations("jdbc:mysql://host.docker.internal:3306/haitlify_isolated");
        assertTrue(violations.stream().anyMatch(message -> message.contains("host.docker.internal:3306 不是隔离目标")));
    }

    @Test
    void rejectsOverridePropertyEvenWhenPrimaryUrlIsIsolated() {
        Map<String, String> datasourceUrls = urls(VALID_URL);
        datasourceUrls.put("spring.datasource.hikari.jdbc-url",
                "jdbc:mysql://host.docker.internal:3306/haitlify_isolated");
        List<String> violations = IsolatedEnvironmentGuard.violations("haitlify-isolated", VALID_HASH, datasourceUrls);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("spring.datasource.hikari.jdbc-url"));
        assertTrue(violations.get(0).contains("覆盖了数据源目标"));
        assertTrue(violations.get(0).contains("不是隔离目标"));
    }

    @Test
    void rejectsOverridePropertyPointingAtLegacyDatabase() {
        Map<String, String> datasourceUrls = urls(VALID_URL);
        datasourceUrls.put("spring.datasource.jdbc-url", "jdbc:mysql://mysql:3306/ai_test_platform");
        List<String> violations = IsolatedEnvironmentGuard.violations("haitlify-isolated", VALID_HASH, datasourceUrls);
        assertTrue(violations.stream().anyMatch(message -> message.contains("旧资源标识 ai_test_platform")));
    }

    @Test
    void rejectsOverridePropertyWithoutPrimaryUrl() {
        Map<String, String> datasourceUrls = new LinkedHashMap<>();
        datasourceUrls.put("spring.datasource.hikari.jdbc-url", VALID_URL);
        List<String> violations = IsolatedEnvironmentGuard.violations("haitlify-isolated", VALID_HASH, datasourceUrls);
        assertTrue(violations.stream().anyMatch(message -> message.contains("缺少数据源地址")));
    }

    @Test
    void rejectsMissingDatasourceUrl() {
        List<String> violations = IsolatedEnvironmentGuard.violations("haitlify-isolated", VALID_HASH, Map.of());
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("spring.datasource.url"));
    }

    @Test
    void parsesDatabaseNameAndTargetsFromJdbcUrl() {
        assertEquals("haitlify_isolated", IsolatedEnvironmentGuard.databaseName("jdbc:mysql://host:3306/haitlify_isolated"));
        // 解析保留原始大小写，校验用 equalsIgnoreCase，因此大小写变体仍被接受。
        assertEquals("Haitlify_Isolated", IsolatedEnvironmentGuard.databaseName("JDBC:MYSQL://HOST:3306/Haitlify_Isolated"));
        assertEquals(null, IsolatedEnvironmentGuard.databaseName("jdbc:h2:mem:aitest"));
        assertEquals(List.of("mysql:3306"), IsolatedEnvironmentGuard.targets(VALID_URL));
        assertEquals(List.of("h1:3306", "h2:3306"),
                IsolatedEnvironmentGuard.targets("jdbc:mysql:loadbalance://h1:3306,h2:3306/haitlify_isolated"));
        assertEquals(null, IsolatedEnvironmentGuard.targets("jdbc:h2:mem:aitest"));
    }

    @Test
    void violationMessageDropsQueryString() {
        // 无法解析库名时会把地址写进异常信息，查询串必须被裁掉（可能带口令类参数）。
        List<String> violations = violations("jdbc:h2:mem:legacy?password=super-secret");
        assertTrue(violations.get(0).contains("jdbc:h2:mem:legacy"));
        assertTrue(violations.get(0).indexOf("super-secret") < 0);
    }

    @Test
    void violationMessagesNeverCarryQueryParameters() {
        List<String> violations = violations("jdbc:mysql://mysql:3306/haitlify?password=super-secret");
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("目标库为 haitlify"));
        assertTrue(violations.stream().noneMatch(message -> message.contains("super-secret")));
    }

    @Test
    void startupGuardBlocksEnvironmentWithoutPreflightResult() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", VALID_URL);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> IsolatedEnvironmentStartupGuard.verify(environment));
        assertTrue(error.getMessage().contains("隔离环境启动保护拒绝启动"));
        assertTrue(error.getMessage().contains("APP_ENV_ID"));
        assertTrue(error.getMessage().contains("APP_ENV_PREFLIGHT_HASH"));
    }

    @Test
    void startupGuardBlocksLegacyDatabaseEvenWhenIdentifiersPresent() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_ENV_ID", "haitlify-isolated")
                .withProperty("APP_ENV_PREFLIGHT_HASH", VALID_HASH)
                .withProperty("APP_ENV_PREFLIGHT_RESULT", validResultPath())
                .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/ai_test_platform");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> IsolatedEnvironmentStartupGuard.verify(environment));
        assertTrue(error.getMessage().contains("旧资源标识 ai_test_platform"));
    }

    @Test
    void startupGuardBlocksHikariOverrideOfTheCheckedUrl() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_ENV_ID", "haitlify-isolated")
                .withProperty("APP_ENV_PREFLIGHT_HASH", VALID_HASH)
                .withProperty("APP_ENV_PREFLIGHT_RESULT", validResultPath())
                .withProperty("spring.datasource.url", VALID_URL)
                .withProperty("spring.datasource.hikari.jdbc-url",
                        "jdbc:mysql://host.docker.internal:3306/haitlify_isolated");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> IsolatedEnvironmentStartupGuard.verify(environment));
        assertTrue(error.getMessage().contains("spring.datasource.hikari.jdbc-url"));
        assertTrue(error.getMessage().contains("覆盖了数据源目标"));
    }

    @Test
    void startupGuardBlocksHostPortOutsideTheIsolatedTargets() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_ENV_ID", "haitlify-isolated")
                .withProperty("APP_ENV_PREFLIGHT_HASH", VALID_HASH)
                .withProperty("APP_ENV_PREFLIGHT_RESULT", validResultPath())
                .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/haitlify_isolated");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> IsolatedEnvironmentStartupGuard.verify(environment));
        assertTrue(error.getMessage().contains("不是隔离目标"));
    }

    @Test
    void startupGuardAllowsIsolatedEnvironment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_ENV_ID", "haitlify-isolated")
                .withProperty("APP_ENV_PREFLIGHT_HASH", VALID_HASH)
                .withProperty("APP_ENV_PREFLIGHT_RESULT", validResultPath())
                .withProperty("spring.datasource.url", VALID_URL);
        IsolatedEnvironmentStartupGuard.verify(environment);
    }

    @Test
    void startupGuardCatchesUnlistedRelaxedBindingSpellings() {
        // 固定清单之外的环境变量写法同样会改写连接目标，枚举式扫描必须接住。
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_ENV_ID", "haitlify-isolated")
                .withProperty("APP_ENV_PREFLIGHT_HASH", VALID_HASH)
                .withProperty("APP_ENV_PREFLIGHT_RESULT", validResultPath())
                .withProperty("spring.datasource.url", VALID_URL)
                .withProperty("SPRING_DATASOURCE_HIKARI_JDBCURL",
                        "jdbc:mysql://host.docker.internal:3306/haitlify_isolated");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> IsolatedEnvironmentStartupGuard.verify(environment));
        assertTrue(error.getMessage().contains("SPRING_DATASOURCE_HIKARI_JDBCURL"));
    }

    @Test
    void startupGuardCatchesFlywayUrlPointingAtLegacyDatabase() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_ENV_ID", "haitlify-isolated")
                .withProperty("APP_ENV_PREFLIGHT_HASH", VALID_HASH)
                .withProperty("APP_ENV_PREFLIGHT_RESULT", validResultPath())
                .withProperty("spring.datasource.url", VALID_URL)
                .withProperty("spring.flyway.url", "jdbc:mysql://127.0.0.1:3306/ai_test_platform");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> IsolatedEnvironmentStartupGuard.verify(environment));
        assertTrue(error.getMessage().contains("spring.flyway.url"));
    }

    @Test
    void startupGuardIgnoresJdbcLookingValuesWithoutDatasourceName() {
        // 名字与数据源无关的属性即使值是 jdbc: 形式也不在检查范围，避免误报。
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_ENV_ID", "haitlify-isolated")
                .withProperty("APP_ENV_PREFLIGHT_HASH", VALID_HASH)
                .withProperty("APP_ENV_PREFLIGHT_RESULT", validResultPath())
                .withProperty("spring.datasource.url", VALID_URL)
                .withProperty("app.web-automation.sample-value", "jdbc:mysql://example.invalid:3306/whatever");
        IsolatedEnvironmentStartupGuard.verify(environment);
    }

    @Test
    void datasourceCandidatesCollectsOverridesAndSkipsNonJdbcValues() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", VALID_URL)
                .withProperty("spring.datasource.username", "haitlify_app")
                .withProperty("spring.datasource.hikari.jdbc-url",
                        "jdbc:mysql://mysql:3306/haitlify_isolated");
        Map<String, String> candidates = IsolatedEnvironmentStartupGuard.datasourceCandidates(environment);
        assertTrue(candidates.containsKey("spring.datasource.url"));
        assertTrue(candidates.containsKey("spring.datasource.hikari.jdbc-url"));
        assertTrue(candidates.keySet().stream().noneMatch(key -> key.contains("username")));
    }

    @Test
    void preflightResultViolationsRejectMissingFile() {
        List<String> violations = IsolatedEnvironmentGuard.preflightResultViolations(
                tempDir.resolve("absent.json").toString(), VALID_HASH);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("缺少预检结果文件"));
    }

    @Test
    void preflightResultViolationsDefaultToContainerPathWhenUnset() {
        List<String> violations = IsolatedEnvironmentGuard.preflightResultViolations(null, VALID_HASH);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains(IsolatedEnvironmentGuard.DEFAULT_PREFLIGHT_RESULT_PATH));
    }

    @Test
    void preflightResultViolationsRejectNonPassVerdict() {
        List<String> violations = IsolatedEnvironmentGuard.preflightResultViolations(
                writeResult("FAIL", VALID_HASH), VALID_HASH);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("verdict 不是 PASS"));
    }

    @Test
    void preflightResultViolationsRejectHashMismatch() {
        List<String> violations = IsolatedEnvironmentGuard.preflightResultViolations(
                writeResult("PASS", "b".repeat(64)), VALID_HASH);
        assertEquals(1, violations.size());
        assertTrue(violations.get(0).contains("与预检结果的 resolvedConfigHash 不一致"));
    }

    @Test
    void preflightResultViolationsAcceptMatchingResult() {
        assertTrue(IsolatedEnvironmentGuard.preflightResultViolations(validResultPath(), VALID_HASH).isEmpty());
    }

    @Test
    void startupGuardBlocksForgedHashWithoutPreflightResult() {
        // 第二轮复核的绕过：shell 环境注入任意 64 位十六进制即可通过形状校验，现在必须与预检结果一致。
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_ENV_ID", "haitlify-isolated")
                .withProperty("APP_ENV_PREFLIGHT_HASH", "b".repeat(64))
                .withProperty("APP_ENV_PREFLIGHT_RESULT", tempDir.resolve("absent.json").toString())
                .withProperty("spring.datasource.url", VALID_URL);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> IsolatedEnvironmentStartupGuard.verify(environment));
        assertTrue(error.getMessage().contains("缺少预检结果文件"));
    }

    @Test
    void startupGuardBlocksHashThatDoesNotMatchPreflightResult() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_ENV_ID", "haitlify-isolated")
                .withProperty("APP_ENV_PREFLIGHT_HASH", "c".repeat(64))
                .withProperty("APP_ENV_PREFLIGHT_RESULT", validResultPath())
                .withProperty("spring.datasource.url", VALID_URL);
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> IsolatedEnvironmentStartupGuard.verify(environment));
        assertTrue(error.getMessage().contains("resolvedConfigHash 不一致"));
    }

    @Test
    void violationMessagesRedactInlineCredentials() {
        List<String> violations = IsolatedEnvironmentGuard.violations("haitlify-isolated", VALID_HASH,
                urls("jdbc:mysql://user:pw@127.0.0.1:3306/haitlify_isolated"));
        assertTrue(violations.stream().noneMatch(message -> message.contains("pw@")));
        assertTrue(violations.stream().anyMatch(message -> message.contains("«redacted»")));
    }

    @Test
    void redactHidesUserInfoEverywhere() {
        assertEquals("jdbc:mysql://«redacted»@mysql:3306/db",
                IsolatedEnvironmentGuard.redact("jdbc:mysql://user:pw@mysql:3306/db"));
        assertEquals("«redacted»@127.0.0.1:3306",
                IsolatedEnvironmentGuard.redact("user:pw@127.0.0.1:3306"));
        assertEquals(null, IsolatedEnvironmentGuard.redact(null));
    }
}
