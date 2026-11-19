package com.haitlify.aitest.platform.assistant.explore;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.haitlify.aitest.platform.webautomation.WebAutomationProperties;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 受控探索（计划 §6.3）：只读地采集页面元素，供人工确认后生成草稿。
 *
 * <p>边界写死在这里，不交给调用方：
 * <ul>
 *   <li>只接受 http(s) 绝对 URL；</li>
 *   <li>只调用 Runner 的只读采集端点（`/page-locator-discovery`），不提供点击/提交能力；</li>
 *   <li>返回元素数量与字段长度都有上限，避免把整页 DOM 灌进草稿与账本。</li>
 * </ul>
 */
@Service
public class AssistantExplorationService {

    private static final Pattern HTTP_URL = Pattern.compile("^https?://[^\\s\"'<>]+$");
    private static final int MAX_ELEMENTS = 60;

    public record Element(String tag, String role, String label, String selector, String text) {}

    public record ExplorationResult(String pageUrl, String status, List<Element> elements, int totalElements) {}

    /** 解析主机名 → 地址；测试可注入假解析，生产用系统 DNS。 */
    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final WebAutomationProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final boolean allowLocalNetwork;
    private final boolean resolveDns;
    private final HostResolver hostResolver;

    @org.springframework.beans.factory.annotation.Autowired
    public AssistantExplorationService(WebAutomationProperties properties, ObjectMapper objectMapper,
                                       @Value("${app.assistant.exploration.allow-local-network:false}")
                                       boolean allowLocalNetwork,
                                       @Value("${app.assistant.exploration.resolve-dns:true}")
                                       boolean resolveDns) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.allowLocalNetwork = allowLocalNetwork;
        this.resolveDns = resolveDns;
        this.hostResolver = InetAddress::getAllByName;
    }

    /** 测试用：注入假解析器，避免用例依赖真实 DNS。 */
    AssistantExplorationService(WebAutomationProperties properties, ObjectMapper objectMapper,
                               boolean allowLocalNetwork, boolean resolveDns, HostResolver hostResolver) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.allowLocalNetwork = allowLocalNetwork;
        this.resolveDns = resolveDns;
        this.hostResolver = hostResolver;
    }

    public ExplorationResult discover(String url) {
        if (url == null || !HTTP_URL.matcher(url).matches()) {
            throw new IllegalArgumentException("受控探索只接受 http(s) 绝对 URL：" + url);
        }
        if (!allowLocalNetwork) {
            rejectLocalTargets(url);
            // 语法层能拦住字面量 IP 与本机别名，但拦不住「公网域名解析到内网」。
            if (resolveDns) {
                rejectResolvedPrivateTargets(url);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("url", url);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/page-locator-discovery"))
            .timeout(Duration.ofMillis(properties.runner().timeoutMs()))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
            .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException ex) {
            throw new IllegalStateException("探索请求失败：" + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("探索请求被中断", ex);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("探索被 Runner 拒绝：HTTP " + response.statusCode() + " "
                + truncate(response.body()));
        }
        JsonNode body = objectMapper.readTree(response.body());
        List<Element> elements = new ArrayList<>();
        JsonNode rawElements = body.path("elements");
        if (rawElements.isArray()) {
            for (JsonNode node : rawElements) {
                if (elements.size() >= MAX_ELEMENTS) {
                    break;
                }
                elements.add(new Element(text(node, "tag"), text(node, "role"), text(node, "label"),
                    text(node, "selector", "css"), text(node, "text")));
            }
        }
        String pageUrl = text(body, "pageUrl");
        if (!allowLocalNetwork && pageUrl != null && !pageUrl.isBlank()) {
            // 跟随跳转后的最终地址必须重新校验：否则 302 到内网即可绕过入口检查。
            try {
                if (HTTP_URL.matcher(pageUrl).matches()) {
                    rejectLocalTargets(pageUrl);
                    if (resolveDns) {
                        rejectResolvedPrivateTargets(pageUrl);
                    }
                }
            } catch (RuntimeException ex) {
                throw new IllegalStateException("探索最终地址越界（跳转后指向本机/私网）：" + pageUrl, ex);
            }
        }
        int total = rawElements.isArray() ? rawElements.size() : 0;
        return new ExplorationResult(pageUrl, text(body, "status"), elements, total);
    }

    /**
     * 默认拒绝回环、私网与链路本地地址：受控探索是「看页面」，不该变成访问内网/云元数据的内应。
     * 需要访问本机夹具时显式打开 app.assistant.exploration.allow-local-network。
     */
    private static void rejectLocalTargets(String url) {
        String host = java.net.URI.create(url).getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("受控探索需要明确的主机名：" + url);
        }
        // 归一：去掉末尾点（"localhost." 与 "localhost" 等价）、小写；拒绝纯数字主机
        // （十进制整数 IP 如 2130706433 会被 libc 解析成 127.0.0.1）。
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        while (lower.endsWith(".")) {
            lower = lower.substring(0, lower.length() - 1);
        }
        // URI.getHost 对 IPv6 保留方括号，先去掉再判。
        lower = lower.replaceAll("^\\[|\\]$", "");
        // IPv4-mapped IPv6：::ffff:127.0.0.1 与 0:0:0:0:0:ffff:127.0.0.1 等价，取最后一段 IPv4 再判。
        if (lower.contains(":") && lower.lastIndexOf(':') < lower.length() - 1) {
            String tail = lower.substring(lower.lastIndexOf(':') + 1);
            if (tail.matches("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")) {
                lower = tail;
            }
        }
        boolean numericHost = lower.matches("^[0-9]+$")
            || lower.matches("^0x[0-9a-f]+$")              // 十六进制整数（0x7f000001）
            || lower.matches("^0[0-7]+(\\.[0-7]+)*$")      // 八进制点分（0177.0.0.1）
            || lower.matches("^(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}$"); // IPv6
        boolean mappedV4 = lower.startsWith("::ffff:127.") || lower.startsWith("::ffff:10.")
            || lower.startsWith("::ffff:192.168.") || lower.startsWith("::ffff:169.254.");
        boolean local = numericHost || mappedV4 || lower.equals("localhost") || lower.endsWith(".localhost")
            || lower.endsWith(".local") || lower.endsWith(".internal") || lower.endsWith(".nip.io")
            || lower.equals("0.0.0.0") || lower.equals("::") || lower.equals("::1")
            || lower.matches("^127\\..*") || lower.matches("^10\\..*")
            || lower.matches("^192\\.168\\..*") || lower.matches("^169\\.254\\..*")
            || lower.matches("^172\\.(1[6-9]|2[0-9]|3[01])\\..*");
        if (local) {
            throw new IllegalArgumentException("受控探索默认拒绝本机/私网/链路本地目标：" + host);
        }
    }

    /**
     * 解析主机名并检查每个地址：堵住「public.example → 127.0.0.1」和 DNS rebinding 的常见形态。
     * 解析失败按拒绝处理（探索失败比访问未知目标安全）。
     */
    private void rejectResolvedPrivateTargets(String url) {
        String host = URI.create(url).getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("受控探索需要明确的主机名：" + url);
        }
        InetAddress[] resolved;
        try {
            resolved = hostResolver.resolve(host);
        } catch (UnknownHostException ex) {
            throw new IllegalArgumentException("受控探索无法解析主机：" + host, ex);
        }
        for (InetAddress address : resolved) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                throw new IllegalArgumentException("受控探索默认拒绝解析到本机/私网的域名：" + host
                    + " → " + address.getHostAddress());
            }
            // 云元数据地址（169.254.169.254）通常已是 link-local，这里再挡 IPv6 唯一的本地地址段。
            if (address.getHostAddress().startsWith("fd00:ec2::")) {
                throw new IllegalArgumentException("受控探索默认拒绝云元数据地址：" + host);
            }
        }
    }

    private String baseUrl() {
        return properties.runner().baseUrl();
    }

    private static String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                String text = value.asString("");
                if (!text.isBlank()) {
                    return text.length() <= 200 ? text : text.substring(0, 200);
                }
            }
        }
        return "";
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 300 ? value : value.substring(0, 300) + "…";
    }
}
