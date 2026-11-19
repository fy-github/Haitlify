package com.haitlify.aitest.platform.startup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 隔离环境启动保护（计划 §7.1）：服务启动/迁移入口在启动前复验目标环境与资源命名。
 *
 * <p>受支持的启动路径只有一个：{@code scripts/preflight/start-isolated-stack.sh start}。
 * 该入口先解析 {@code deploy/isolated/compose.yaml}、校验隔离要求并写入绑定配置 hash 的预检结果，
 * 再把同一个 hash 注入平台进程；平台在这里复验环境标识、预检结果与**实际生效的数据源目标**，
 * 缺失或不匹配即拒绝启动。
 *
 * <p>独立复核（2026-10-16 前五轮）暴露的绕过都已在此封堵：只校验 {@code spring.datasource.url} 会被
 * {@code spring.datasource.hikari.jdbc-url} 之类的次生属性覆盖；只比对库名会漏掉「库名合法但主机/端口指向别处」；
 * 只做哈希形状校验则 shell 环境里随便一个 64 位十六进制都能通过。因此这里校验**全部候选数据源属性**，
 * 校验主机/端口白名单（只接受 compose 服务名 {@code mysql:3306} 与宿主侧 {@code 127.0.0.1:13306} /
 * {@code localhost:13306}；容器名可被同网其它容器抢占，不在白名单内），
 * 并要求 {@code APP_ENV_PREFLIGHT_HASH} 与只读挂载的预检结果一致且 verdict=PASS。
 */
public final class IsolatedEnvironmentGuard {

    public static final String ENV_ID_PROPERTY = "APP_ENV_ID";
    public static final String PREFLIGHT_HASH_PROPERTY = "APP_ENV_PREFLIGHT_HASH";
    public static final String DATASOURCE_URL_PROPERTY = "spring.datasource.url";
    public static final String EXPECTED_ENV_ID = "haitlify-isolated";
    public static final String EXPECTED_DATABASE_NAME = "haitlify_isolated";
    /** 预检结果文件路径（容器内只读挂载）；由启动入口写入，平台据此把哈希与真实配置绑定。 */
    public static final String PREFLIGHT_RESULT_PROPERTY = "APP_ENV_PREFLIGHT_RESULT";
    public static final String DEFAULT_PREFLIGHT_RESULT_PATH = "/app/preflight/isolated-preflight.json";

    /** 能决定实际连接目标的属性：Hikari/DataSource 的 jdbc-url 会覆盖 spring.datasource.url。 */
    public static final List<String> DATASOURCE_URL_PROPERTIES = List.of(
            "spring.datasource.url",
            "spring.datasource.jdbc-url",
            "spring.datasource.jdbcUrl",
            "spring.datasource.hikari.jdbc-url");

    private static final Pattern PREFLIGHT_HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MYSQL_URL =
            Pattern.compile("^jdbc:mysql:(?:[a-z]+:)?//([^/]+)/([^?;]+)", Pattern.CASE_INSENSITIVE);
    /** 预检结果里的 verdict 与配置 hash：平台据此确认「这份预检结果就是这次启动的依据」。 */
    private static final Pattern RESULT_VERDICT = Pattern.compile("\"verdict\"\\s*:\\s*\"([A-Z]+)\"");
    private static final Pattern RESULT_HASH = Pattern.compile("\"resolvedConfigHash\"\\s*:\\s*\"([0-9a-f]{64})\"");
    /** jdbc URL 内嵌的 user:password@ 必须遮蔽后才能进日志/异常信息。 */
    private static final Pattern URL_USERINFO =
            Pattern.compile("(\\b[a-z][a-z0-9+.-]*://)[^/@\\s]+@", Pattern.CASE_INSENSITIVE);
    /** 只带 host:port 的目标串（例如解析出的 user:pw@host:3306）同样要遮蔽 userinfo。 */
    private static final Pattern BARE_USERINFO =
            Pattern.compile("([^\\s@/]+)@(?=[^\\s@/]*:\\d+|\\d{1,3}(?:\\.\\d{1,3}){3})");
    private static final Set<String> LEGACY_RESOURCE_TOKENS = Set.of("ai_test_platform", "cloudv4");
    // 只认 compose 服务名 mysql 与宿主侧隔离端口；容器名（haitlify-isolated-mysql）可被同网其它容器
    // 抢占，因此不作为可信目标（第四轮复核实测的绕过面）。
    private static final Map<String, Set<Integer>> ALLOWED_TARGETS = Map.of(
            "mysql", Set.of(3306),
            "127.0.0.1", Set.of(13306),
            "localhost", Set.of(13306));
    private static final int DEFAULT_MYSQL_PORT = 3306;

    private IsolatedEnvironmentGuard() {
    }

    /**
     * 返回全部违规项；空列表表示满足隔离环境启动契约。
     *
     * @param datasourceUrls 属性名 → 实际生效的数据源地址（必须包含 {@link #DATASOURCE_URL_PROPERTY}）
     */
    public static List<String> violations(String envId, String preflightHash, Map<String, String> datasourceUrls) {
        List<String> violations = new ArrayList<>();
        if (envId == null || envId.isBlank()) {
            violations.add("缺少 " + ENV_ID_PROPERTY
                    + "：只允许通过 scripts/preflight/start-isolated-stack.sh 启动隔离环境");
        } else if (!EXPECTED_ENV_ID.equals(envId.strip())) {
            violations.add(ENV_ID_PROPERTY + "=" + envId.strip() + " 不是隔离环境标识 " + EXPECTED_ENV_ID);
        }

        if (preflightHash == null || preflightHash.isBlank()) {
            violations.add("缺少 " + PREFLIGHT_HASH_PROPERTY
                    + "：请先运行 scripts/preflight/start-isolated-stack.sh check 生成预检结果");
        } else if (!PREFLIGHT_HASH.matcher(preflightHash.strip().toLowerCase(Locale.ROOT)).matches()) {
            violations.add(PREFLIGHT_HASH_PROPERTY + " 不是 64 位十六进制预检哈希");
        }

        Map<String, String> urls = datasourceUrls == null ? Map.of() : datasourceUrls;
        String primary = urls.get(DATASOURCE_URL_PROPERTY);
        if (primary == null || primary.isBlank()) {
            violations.add("缺少数据源地址 " + DATASOURCE_URL_PROPERTY);
        }
        for (Map.Entry<String, String> entry : urls.entrySet()) {
            String url = entry.getValue();
            if (url == null || url.isBlank()) {
                continue;
            }
            boolean primaryProperty = DATASOURCE_URL_PROPERTY.equals(entry.getKey());
            for (String violation : datasourceViolations(url)) {
                violations.add(primaryProperty ? violation
                        : "属性 " + entry.getKey() + " 覆盖了数据源目标：" + violation);
            }
        }
        return List.copyOf(violations);
    }

    /** 校验单个数据源地址：旧资源标识、库名、主机与端口都必须落在隔离取值内。 */
    static List<String> datasourceViolations(String datasourceUrl) {
        List<String> violations = new ArrayList<>();
        String normalized = datasourceUrl.toLowerCase(Locale.ROOT);
        for (String token : LEGACY_RESOURCE_TOKENS) {
            if (normalized.contains(token)) {
                violations.add("数据源地址命中旧资源标识 " + token + "：隔离环境不得连接旧数据库");
            }
        }

        String database = databaseName(datasourceUrl);
        if (database == null) {
            violations.add("无法从数据源地址解析库名：" + redact(withoutQuery(datasourceUrl)));
        } else if (!EXPECTED_DATABASE_NAME.equalsIgnoreCase(database)) {
            violations.add("数据源目标库为 " + database + "，期望 " + EXPECTED_DATABASE_NAME);
        }

        List<String> targets = targets(datasourceUrl);
        if (targets == null) {
            violations.add("无法从数据源地址解析主机/端口：" + redact(withoutQuery(datasourceUrl)));
        } else {
            for (String target : targets) {
                if (!isAllowedTarget(target)) {
                    violations.add("数据源主机/端口 " + redact(target) + " 不是隔离目标（允许：mysql:3306、"
                            + "127.0.0.1:13306、localhost:13306；容器名不参与白名单）");
                }
            }
        }
        return List.copyOf(violations);
    }

    /**
     * 复验预检结果文件：必须可读、verdict=PASS、且 resolvedConfigHash 与注入的哈希一致。
     * 文件由启动入口写入并以只读方式挂载；缺失（例如直接 {@code docker compose up}）即拒绝启动。
     */
    public static List<String> preflightResultViolations(String preflightResultPath, String preflightHash) {
        List<String> violations = new ArrayList<>();
        String path = preflightResultPath == null || preflightResultPath.isBlank()
                ? DEFAULT_PREFLIGHT_RESULT_PATH
                : preflightResultPath.strip();
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            violations.add("缺少预检结果文件 " + path
                    + "：只允许通过 scripts/preflight/start-isolated-stack.sh 生成并挂载预检结果后启动");
            return List.copyOf(violations);
        }
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException error) {
            violations.add("无法读取预检结果文件 " + path + "：" + error.getClass().getSimpleName());
            return List.copyOf(violations);
        }
        Matcher verdict = RESULT_VERDICT.matcher(content);
        if (!verdict.find() || !"PASS".equals(verdict.group(1))) {
            violations.add("预检结果 verdict 不是 PASS：" + path);
        }
        Matcher hash = RESULT_HASH.matcher(content);
        if (!hash.find()) {
            violations.add("预检结果缺少 resolvedConfigHash：" + path);
        } else if (preflightHash == null || preflightHash.isBlank()
                || !hash.group(1).equalsIgnoreCase(preflightHash.strip())) {
            violations.add("注入的 " + PREFLIGHT_HASH_PROPERTY
                    + " 与预检结果的 resolvedConfigHash 不一致：配置在预检之后发生变化，需要重新预检");
        }
        return List.copyOf(violations);
    }

    /** 主机:端口 列表（多主机 URL 会返回多项）；无法解析返回 null。 */
    static List<String> targets(String jdbcUrl) {
        Matcher matcher = MYSQL_URL.matcher(jdbcUrl);
        if (!matcher.find()) {
            return null;
        }
        List<String> targets = new ArrayList<>();
        for (String hostPort : matcher.group(1).split(",")) {
            String trimmed = hostPort.trim().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty()) {
                return null;
            }
            targets.add(trimmed);
        }
        return List.copyOf(targets);
    }

    static String databaseName(String jdbcUrl) {
        Matcher matcher = MYSQL_URL.matcher(jdbcUrl);
        return matcher.find() ? matcher.group(2) : null;
    }

    private static boolean isAllowedTarget(String hostPort) {
        String host = hostPort;
        int port = DEFAULT_MYSQL_PORT;
        int colon = hostPort.lastIndexOf(':');
        if (colon >= 0) {
            host = hostPort.substring(0, colon);
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException error) {
                return false;
            }
        }
        Set<Integer> allowedPorts = ALLOWED_TARGETS.get(host);
        return allowedPorts != null && allowedPorts.contains(port);
    }

    /** 去掉查询串并遮蔽 URL 内嵌凭据：异常信息进日志前不能带出口令。 */
    static String withoutQuery(String jdbcUrl) {
        int cutoff = jdbcUrl.length();
        int question = jdbcUrl.indexOf('?');
        int semicolon = jdbcUrl.indexOf(';');
        if (question >= 0) {
            cutoff = Math.min(cutoff, question);
        }
        if (semicolon >= 0) {
            cutoff = Math.min(cutoff, semicolon);
        }
        return jdbcUrl.substring(0, cutoff);
    }

    /** 把 {@code jdbc:mysql://user:pw@host} 或裸 {@code user:pw@host:port} 里的 userinfo 换成掩码。 */
    static String redact(String text) {
        if (text == null) {
            return null;
        }
        return BARE_USERINFO.matcher(URL_USERINFO.matcher(text).replaceAll("$1«redacted»@"))
                .replaceAll("«redacted»@");
    }

    /** 监听器按属性声明顺序收集候选值，消息里主属性在前。 */
    static Map<String, String> newCandidateMap() {
        return new LinkedHashMap<>();
    }
}
