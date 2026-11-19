package com.haitlify.aitest.platform.assistant.conversation;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * 真实的 {@link GatewayMcpControl}：在给定配置文件里<b>原子替换单条</b> MCP 服务器条目，然后 reload。
 *
 * <p>这一层只做四件事，顺序是协议的一部分：
 * <ol>
 *   <li><b>写</b>：读入文件 → 只改顶层 {@code mcp_servers.<name>} 这一段（<b>整条</b>写成
 *       {@code {url: ...}}）→ 同目录临时文件 + fsync + 原子 rename；权限固定 0600。绝不整文件重写，
 *       也绝不自动创建缺失的文件或父段；</li>
 *   <li><b>reload</b>：通过注入的 {@link GatewayReloader} 触发（真实实现对应 {@code reload.mcp}）。
 *       写失败就没有 pending 序列，reload 会被拒绝——把旧配置再加载一遍会伪装成「已生效」；</li>
 *   <li><b>回读</b>：reload 成功后重新解析文件，并（若有）比对 {@link GatewayMcpReceipt}；两处都必须是
 *       本轮 URL（或都为「无条目」）。「reload 返回了」不等于「条目是本轮 URL」；</li>
 *   <li><b>回滚</b>：reload 失败或回读不一致时，把条目恢复成写入前的值（原来是「无条目」就删掉），
 *       再 reload 一次；恢复写入或第二次 reload 也失败时保留现状，抛
 *       {@link GatewayMcpControlException.Reason#ROLLBACK_FAILED} 交给调用方撤销本轮租约。</li>
 * </ol>
 *
 * <p><b>并发</b>：同一实例内所有读写用一把锁串行；一次写入会开启一个「待 reload 序列」，只有<b>写它的
 * 那个线程</b>能 reload 它。另一轮线程在这个窗口里写入会让旧序列失效——旧线程的 reload 以
 * {@link GatewayMcpControlException.Reason#CROSSED_RELOAD} 拒绝；来晚一步、新一轮已经完成时则表现为
 * {@link GatewayMcpControlException.Reason#NO_WRITE_PENDING}。两种都表示「本轮未生效」，都不会把别人写的
 * 条目当成自己的来 reload 或回滚。文件层面另有「写前再读一次」的乐观校验：别的进程在我们读完之后改了无关键，写入以
 * {@link GatewayMcpControlException.Reason#ENTRY_CHANGED} 拒绝，而不是用旧快照覆盖掉。
 *
 * <p><b>安全</b>：不记录 URL / lease id；异常只带原因码、不链接底层异常（外部 reloader 的消息可能
 * 打印 URL）；{@link #toString()} 固定脱敏。文件 0600、不跟随符号链接（改用 rename 会把链接换成普通
 * 文件，等于悄悄换掉目标）。
 *
 * <p><b>为什么是顶层 {@code mcp_servers} 而不是 {@code mcp.servers}</b>：Hermes 的真实配置把 MCP server
 * 定义放在<b>顶层</b> {@code mcp_servers} 映射下（{@code mcp} 段只放全局开关，如超时）。写到
 * {@code mcp.servers} 是一个<b>网关根本不读</b>的位置：写成功、reload 成功，而新会话仍然拿不到本轮的条目。
 * 所以旧布局一律 {@code CONFIG_INVALID} 拒写，绝不"顺手建一棵自己能解释的树"。
 *
 * <p><b>为什么整条替换</b>：条目里的 {@code auth}/{@code headers}/{@code command} 都是凭据或执行入口。
 * 只改 {@code url} 会把上一轮（或手工配置期）留下的 Authorization 继续带给新会话——那是另一张仍然有效的
 * 凭据在冒充本轮租约。因此写入时该条目被整体替换成 {@code {url: <本轮 URL>}}，其它 server 条目与所有
 * 顶层无关键原样保留。
 *
 * <p>本类默认<b>不装配</b>：只有显式设置
 * {@code app.assistant.conversation.mcp-lease.gateway-config-path}（并因此同时提供
 * {@link GatewayReloader} Bean）时才会创建。默认配置里没有这个属性，部署行为不变。
 */
final class ConfigFileGatewayMcpControl implements GatewayMcpControl {

    /** 默认条目位置：顶层 {@code mcp_servers.<name>.url}（pin 的真实配置形状）。 */
    static final List<String> DEFAULT_PARENT_PATH = List.of("mcp_servers");

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final long MAX_CONFIG_BYTES = 1L << 20;
    private static final String TEMP_PREFIX = ".haitlify-mcp-";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final Set<java.nio.file.attribute.PosixFilePermission> OWNER_ONLY =
        PosixFilePermissions.fromString("rw-------");

    private final Path configFile;
    private final String serverName;
    private final List<String> parentPath;
    private final GatewayReloader reloader;
    private final GatewayMcpReceipt receipt;

    /** 所有对配置文件的读、写、reload、回读都在这一把锁内（单实例串行化）。 */
    private final Object lock = new Object();

    private long generation;
    private boolean writePending;
    private long writeGeneration;
    private Thread writeThread;
    private boolean previousEntryPresent;
    private String previousEndpoint;
    /** 本轮写入的值；{@code null} = 本轮要求「无条目」。只在锁内读写，永不打印。 */
    private String writtenEndpoint;

    ConfigFileGatewayMcpControl(Path configFile, String serverName, List<String> parentPath,
                                GatewayReloader reloader, GatewayMcpReceipt receipt) {
        if (configFile == null) {
            throw new IllegalArgumentException("config file path required");
        }
        if (serverName == null || !NAME.matcher(serverName).matches()) {
            throw new IllegalArgumentException("server name must match " + NAME.pattern());
        }
        if (parentPath == null || parentPath.isEmpty()
            || parentPath.stream().anyMatch(key -> key == null || !NAME.matcher(key).matches())) {
            throw new IllegalArgumentException("parent path must be non-empty mapping keys");
        }
        if (reloader == null) {
            throw new IllegalArgumentException("gateway reloader required");
        }
        this.configFile = configFile;
        this.serverName = serverName;
        this.parentPath = List.copyOf(parentPath);
        this.reloader = reloader;
        this.receipt = receipt;
    }

    ConfigFileGatewayMcpControl(Path configFile, GatewayReloader reloader) {
        this(configFile, "haitlify", DEFAULT_PARENT_PATH, reloader, null);
    }

    // ------------------------------------------------------------------ 写

    @Override
    public void writeServerEntry(String leaseEndpoint) {
        String endpoint = validateEndpoint(leaseEndpoint);
        synchronized (lock) {
            // 任何新写入都作废上一个未完成序列：失败时绝不能留下「可以 reload」的假序列。
            clearPending();
            Document document = readDocument();
            String updated = document.withEntry(endpoint);
            if (!updated.equals(document.text)) {
                writeAtomically(document.text, updated);
            }
            previousEntryPresent = document.entryPresent;
            previousEndpoint = document.entryUrl;
            writtenEndpoint = endpoint;
            writeGeneration = ++generation;
            writeThread = Thread.currentThread();
            writePending = true;
        }
    }

    // ------------------------------------------------------------------ reload + 回读 + 回滚

    @Override
    public void reload() {
        synchronized (lock) {
            if (!writePending) {
                throw new GatewayMcpControlException(GatewayMcpControlException.Reason.NO_WRITE_PENDING);
            }
            if (writeThread != Thread.currentThread() || writeGeneration != generation) {
                // 另一轮在本轮 write 之后又写了条目：此刻 reload 会加载别人的 URL，回滚会覆盖别人的写。
                throw new GatewayMcpControlException(GatewayMcpControlException.Reason.CROSSED_RELOAD);
            }
            try {
                // 回执（loaded_rev / coalesced）在这里刻意丢弃：控制面另有独立观测点——文件回读与
                // GatewayMcpReceipt。「网关说它重载了某一版」不等于「该条目是本轮 URL」，后者才是判据。
                reloader.reload();
            } catch (RuntimeException failure) {
                throw rollbackAndFail(GatewayMcpControlException.Reason.RELOAD_FAILED);
            }
            try {
                verifyReadback();
            } catch (RuntimeException failure) {
                throw rollbackAndFail(GatewayMcpControlException.Reason.READBACK_MISMATCH);
            }
            clearPending();
        }
    }

    /** reload 成功后必须证明「网关看到的确实是本轮值」：先文件，再（可选）回执。 */
    private void verifyReadback() {
        Document current = readDocument();
        boolean matches = writtenEndpoint == null
            ? !current.entryPresent
            : current.entryPresent && writtenEndpoint.equals(current.entryUrl);
        if (!matches) {
            throw new GatewayMcpControlException(GatewayMcpControlException.Reason.READBACK_MISMATCH);
        }
        if (receipt != null) {
            String receiptEntry = receipt.serverEntry();
            boolean receiptMatches = writtenEndpoint == null
                ? receiptEntry == null
                : writtenEndpoint.equals(receiptEntry);
            if (!receiptMatches) {
                throw new GatewayMcpControlException(GatewayMcpControlException.Reason.READBACK_MISMATCH);
            }
        }
    }

    /**
     * 回滚到写入前的条目，再 reload 一次；任一步失败都返回 ROLLBACK_FAILED（现状未知，调用方撤销租约）。
     *
     * <p>不链接任何底层异常：外部实现的消息可能含 URL / lease id。
     */
    private GatewayMcpControlException rollbackAndFail(GatewayMcpControlException.Reason primary) {
        try {
            restorePreviousEntry();
        } catch (RuntimeException restoreFailed) {
            clearPending();
            return new GatewayMcpControlException(GatewayMcpControlException.Reason.ROLLBACK_FAILED);
        }
        try {
            reloader.reload();
        } catch (RuntimeException secondReloadFailed) {
            clearPending();
            return new GatewayMcpControlException(GatewayMcpControlException.Reason.ROLLBACK_FAILED);
        }
        clearPending();
        return new GatewayMcpControlException(primary);
    }

    private void restorePreviousEntry() {
        Document current = readDocument();
        String updated = current.withEntry(previousEntryPresent ? previousEndpoint : null);
        if (!updated.equals(current.text)) {
            writeAtomically(current.text, updated);
        }
    }

    private void clearPending() {
        writePending = false;
        writeThread = null;
        previousEntryPresent = false;
        previousEndpoint = null;
        writtenEndpoint = null;
    }

    // ------------------------------------------------------------------ 文件

    private Document readDocument() {
        if (Files.isSymbolicLink(configFile) || !Files.isRegularFile(configFile, LinkOption.NOFOLLOW_LINKS)
            || !Files.isReadable(configFile)) {
            throw new GatewayMcpControlException(GatewayMcpControlException.Reason.CONFIG_UNAVAILABLE);
        }
        long size;
        try {
            size = Files.size(configFile);
        } catch (IOException | SecurityException failure) {
            throw new GatewayMcpControlException(GatewayMcpControlException.Reason.CONFIG_UNAVAILABLE);
        }
        if (size > MAX_CONFIG_BYTES) {
            throw new GatewayMcpControlException(GatewayMcpControlException.Reason.CONFIG_INVALID);
        }
        String text;
        try {
            text = Files.readString(configFile, StandardCharsets.UTF_8);
        } catch (CharacterCodingException failure) {
            throw new GatewayMcpControlException(GatewayMcpControlException.Reason.CONFIG_INVALID);
        } catch (IOException | SecurityException failure) {
            throw new GatewayMcpControlException(GatewayMcpControlException.Reason.CONFIG_UNAVAILABLE);
        }
        return Document.parse(text, serverName, parentPath);
    }

    /**
     * 原子写入：同目录临时文件（0600）→ 写入 → fsync → 原子 rename。不支持原子 rename 就失败，
     * 不退化成原地覆盖（半写文件会让网关读到非法配置）。
     *
     * @param expectedCurrent 读快照；写入前如果再读一次发现文件变了，拒绝覆盖其它进程的写入
     */
    private void writeAtomically(String expectedCurrent, String updated) {
        Path target = configFile.toAbsolutePath();
        Path directory = target.getParent();
        if (directory == null) {
            throw new GatewayMcpControlException(GatewayMcpControlException.Reason.CONFIG_UNAVAILABLE);
        }
        if (!Files.isWritable(target)) {
            throw new GatewayMcpControlException(GatewayMcpControlException.Reason.WRITE_FAILED);
        }
        requireUnchanged(target, expectedCurrent);
        Path temp = null;
        try {
            temp = Files.createTempFile(directory, TEMP_PREFIX, TEMP_SUFFIX,
                PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(updated.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.setPosixFilePermissions(temp, OWNER_ONLY);
            } catch (UnsupportedOperationException ignored) {
                // 非 POSIX 文件系统：保留 createTempFile 声明的权限。
            }
            // rename 前再确认一次，尽量缩小「别的进程刚写完无关键」被覆盖的窗口。
            requireUnchanged(target, expectedCurrent);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
        } catch (AtomicMoveNotSupportedException failure) {
            throw new GatewayMcpControlException(GatewayMcpControlException.Reason.WRITE_FAILED);
        } catch (IOException | SecurityException failure) {
            throw new GatewayMcpControlException(GatewayMcpControlException.Reason.WRITE_FAILED);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 尽力清理；万一残留，临时文件也是 0600，且只含这次准备写入的配置内容。
                }
            }
        }
        fsyncDirectory(directory);
    }

    private void requireUnchanged(Path target, String expectedCurrent) {
        String current;
        try {
            current = Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException | SecurityException failure) {
            throw new GatewayMcpControlException(GatewayMcpControlException.Reason.WRITE_FAILED);
        }
        if (!current.equals(expectedCurrent)) {
            throw new GatewayMcpControlException(GatewayMcpControlException.Reason.ENTRY_CHANGED);
        }
    }

    private static void fsyncDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | RuntimeException ignored) {
            // 目录 fsync 在部分平台/文件系统上不支持；rename 本身已经保证了原子可见性。
        }
    }

    // ------------------------------------------------------------------ 校验

    private static String validateEndpoint(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.isBlank() || !raw.equals(raw.trim())) {
            throw new IllegalArgumentException(
                "lease endpoint must be a non-blank HTTP(S) URL without surrounding whitespace");
        }
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new IllegalArgumentException("lease endpoint must not contain control characters");
            }
        }
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("lease endpoint must be a valid URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("lease endpoint must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("lease endpoint must have a host");
        }
        return raw;
    }

    @Override
    public String toString() {
        return "ConfigFileGatewayMcpControl[config=" + configFile + ", redacted]";
    }

    // ------------------------------------------------------------------ 文档模型

    /**
     * 一次读入的配置快照：原始文本 + 已解析模型 + {@code mcp_servers} 段与条目的行范围。
     *
     * <p>为什么既要模型又要行号：模型用来<b>证明</b>改写结果（改完重新解析必须等于「旧模型改一处」），
     * 行号用来只动目标条目、保留其它键的注释与写法。两者不一致（flow 风格父段、重复键、别名等）时
     * 一律 {@code CONFIG_INVALID}，绝不写出一个自己无法解释的文件。
     */
    private static final class Document {

        private final String serverName;
        private final List<String> parentPath;
        private final String text;
        private final String newline;
        private final boolean trailingNewline;
        private final List<String> lines;
        private final Map<Object, Object> model;
        private final int parentHeaderLine;
        private final int parentIndent;
        private final int parentEnd;
        private final boolean parentEmptyFlow;
        private final int childIndent;
        private final int entryLine;
        private final int entryEnd;
        private final int entryIndent;
        private final boolean entryPresent;
        private final String entryUrl;

        private Document(String serverName, List<String> parentPath, String text, String newline,
                         boolean trailingNewline, List<String> lines, Map<Object, Object> model,
                         int parentHeaderLine, int parentIndent, int parentEnd, boolean parentEmptyFlow,
                         int childIndent, int entryLine, int entryEnd, int entryIndent, boolean entryPresent,
                         String entryUrl) {
            this.serverName = serverName;
            this.parentPath = parentPath;
            this.text = text;
            this.newline = newline;
            this.trailingNewline = trailingNewline;
            this.lines = lines;
            this.model = model;
            this.parentHeaderLine = parentHeaderLine;
            this.parentIndent = parentIndent;
            this.parentEnd = parentEnd;
            this.parentEmptyFlow = parentEmptyFlow;
            this.childIndent = childIndent;
            this.entryLine = entryLine;
            this.entryEnd = entryEnd;
            this.entryIndent = entryIndent;
            this.entryPresent = entryPresent;
            this.entryUrl = entryUrl;
        }

        private static Document parse(String text, String serverName, List<String> parentPath) {
            Map<Object, Object> root = asModel(text, parentPath);
            String newline = text.contains("\r\n") ? "\r\n" : "\n";
            String normalized = text.replace("\r\n", "\n");
            boolean trailing = normalized.endsWith("\n");
            List<String> lines = new ArrayList<>(List.of(normalized.split("\n", -1)));
            if (trailing) {
                lines.remove(lines.size() - 1);
            }

            int from = 0;
            int to = lines.size();
            int minIndent = 0;
            int header = -1;
            int indent = -1;
            boolean emptyFlow = false;
            for (String key : parentPath) {
                header = findKeyLine(lines, from, to, minIndent, key);
                if (header < 0) {
                    throw invalid();
                }
                indent = indentOf(lines.get(header));
                String rest = restAfterKey(lines.get(header), indent, key);
                if (!rest.isEmpty() && !"{}".equals(rest)) {
                    // 只支持 block 风格的父段；flow / 标量父段无法安全做行级替换。
                    throw invalid();
                }
                emptyFlow = "{}".equals(rest);
                from = header + 1;
                to = blockEnd(lines, header, indent);
                minIndent = indent + 1;
            }

            Map<Object, Object> parent = parentModel(root, parentPath);
            int foundChildIndent = -1;
            int foundEntryLine = -1;
            for (int i = from; i < to; i++) {
                String line = lines.get(i);
                if (isIgnorable(line)) {
                    continue;
                }
                int lineIndent = indentOf(line);
                if (foundChildIndent < 0) {
                    foundChildIndent = lineIndent;
                }
                if (lineIndent != foundChildIndent) {
                    continue;
                }
                String key = keyOf(line, lineIndent);
                if (key == null) {
                    throw invalid();
                }
                if (key.equals(serverName)) {
                    if (foundEntryLine >= 0) {
                        throw invalid();
                    }
                    foundEntryLine = i;
                }
            }

            Map<Object, Object> entry = asEntry(parent.get(serverName));
            boolean present = entry != null;
            String url = present ? (String) entry.get("url") : null;
            if ((foundEntryLine >= 0) != present) {
                // 行定位与语义模型不一致（例如键的写法无法被行扫描识别）：不做猜测，直接失败。
                throw invalid();
            }

            int foundEnd = present ? blockEnd(lines, foundEntryLine, indentOf(lines.get(foundEntryLine))) : -1;
            int foundIndent = present ? indentOf(lines.get(foundEntryLine)) : -1;
            return new Document(serverName, parentPath, text, newline, trailing, lines, root, header, indent, to,
                emptyFlow, foundChildIndent, foundEntryLine, foundEnd, foundIndent, present, url);
        }

        /** 返回改写后的完整文本；不落盘。改写结果必须与「旧模型改一处」解析相等，否则失败。 */
        private String withEntry(String endpoint) {
            Map<Object, Object> expected = deepCopy(model);
            Map<Object, Object> parent = mapAt(expected, parentPath);
            List<String> updated = new ArrayList<>(lines);
            if (endpoint == null) {
                if (!entryPresent) {
                    return text;
                }
                parent.remove(serverName);
                updated.subList(entryLine, entryEnd).clear();
                if (!hasRemainingChildren(updated)) {
                    updated.set(parentHeaderLine, spaces(parentIndent) + parentKey() + ": {}");
                }
            } else {
                // 即使 url 与现有值相同也要重写这一条：条目里可能还挂着上一轮的 auth/headers/command，
                // 「url 没变」不等于「这一条是本轮的形态」。规范化的两行块与原文一致时，调用方不会落盘。
                Map<Object, Object> entry = new LinkedHashMap<>();
                entry.put("url", endpoint);
                parent.put(serverName, entry);
                int indent = entryPresent ? entryIndent : (childIndent >= 0 ? childIndent : parentIndent + 2);
                List<String> block = List.of(
                    spaces(indent) + serverName + ":",
                    spaces(indent + 2) + "url: " + quote(endpoint));
                if (entryPresent) {
                    updated.subList(entryLine, entryEnd).clear();
                    updated.addAll(entryLine, block);
                } else {
                    int insertAt = parentEmptyFlow ? parentHeaderLine + 1 : parentEnd;
                    if (parentEmptyFlow) {
                        updated.set(parentHeaderLine, spaces(parentIndent) + parentKey() + ":");
                    }
                    updated.addAll(insertAt, block);
                }
            }
            String candidate = String.join("\n", updated) + (trailingNewline ? "\n" : "");
            if ("\r\n".equals(newline)) {
                candidate = candidate.replace("\n", "\r\n");
            }
            Map<Object, Object> reparsed = asModel(candidate, parentPath);
            if (!reparsed.equals(expected)) {
                throw invalid();
            }
            return candidate;
        }

        private boolean hasRemainingChildren(List<String> updated) {
            // parentEnd 会因删除而左移；按原范围判断删除后是否还有兄弟条目。
            for (int i = parentHeaderLine + 1; i < updated.size(); i++) {
                String line = updated.get(i);
                if (isIgnorable(line)) {
                    continue;
                }
                int lineIndent = indentOf(line);
                if (lineIndent <= parentIndent) {
                    return false;
                }
                return true;
            }
            return false;
        }

        private String parentKey() {
            return parentPath.get(parentPath.size() - 1);
        }

        private static Map<Object, Object> asModel(String text, List<String> parentPath) {
            Object loaded = parseYaml(text);
            if (!(loaded instanceof Map<?, ?> map)) {
                throw invalid();
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> root = (Map<Object, Object>) map;
            parentModel(root, parentPath);
            return root;
        }

        private static Map<Object, Object> parentModel(Map<Object, Object> root, List<String> parentPath) {
            Object current = root;
            for (String key : parentPath) {
                if (!(current instanceof Map<?, ?> map)) {
                    throw invalid();
                }
                current = map.get(key);
            }
            if (!(current instanceof Map<?, ?>)) {
                throw invalid();
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> parent = (Map<Object, Object>) current;
            return parent;
        }

        /** 条目必须存在时是 {@code {url: <String>}}；任一不符即失败（无法可靠快照/回滚）。 */
        private static Map<Object, Object> asEntry(Object value) {
            if (value == null) {
                return null;
            }
            if (!(value instanceof Map<?, ?> map)) {
                throw invalid();
            }
            if (!(map.get("url") instanceof String)) {
                throw invalid();
            }
            @SuppressWarnings("unchecked")
            Map<Object, Object> entry = (Map<Object, Object>) map;
            return entry;
        }
    }

    // ------------------------------------------------------------------ YAML / 行工具

    private static Object parseYaml(String text) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setAllowRecursiveKeys(false);
        options.setMaxAliasesForCollections(16);
        options.setNestingDepthLimit(32);
        try {
            return new Yaml(new SafeConstructor(options)).load(text);
        } catch (YAMLException failure) {
            throw invalid();
        }
    }

    private static Map<Object, Object> mapAt(Map<Object, Object> root, List<String> path) {
        Object current = root;
        for (String key : path) {
            if (!(current instanceof Map<?, ?> map)) {
                throw invalid();
            }
            current = map.get(key);
        }
        if (!(current instanceof Map<?, ?>)) {
            throw invalid();
        }
        @SuppressWarnings("unchecked")
        Map<Object, Object> parent = (Map<Object, Object>) current;
        return parent;
    }

    private static Map<Object, Object> deepCopy(Map<Object, Object> source) {
        @SuppressWarnings("unchecked")
        Map<Object, Object> copy = (Map<Object, Object>) deepCopyValue(source);
        return copy;
    }

    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(deepCopyValue(key), deepCopyValue(item)));
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(deepCopyValue(item)));
            return copy;
        }
        return value;
    }

    /** 在 [from, to) 里找第一个缩进 >= minIndent 且键为 {@code key} 的映射行。 */
    private static int findKeyLine(List<String> lines, int from, int to, int minIndent, String key) {
        for (int i = from; i < to; i++) {
            String line = lines.get(i);
            if (isIgnorable(line)) {
                continue;
            }
            int indent = indentOf(line);
            if (indent < minIndent) {
                continue;
            }
            if (key.equals(keyOf(line, indent))) {
                return i;
            }
        }
        return -1;
    }

    /** 当前缩进块的结束行（第一条非空/非注释且缩进 <= 块头的行）。 */
    private static int blockEnd(List<String> lines, int header, int indent) {
        for (int i = header + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (isIgnorable(line)) {
                continue;
            }
            if (indentOf(line) <= indent) {
                return i;
            }
        }
        return lines.size();
    }

    private static int indentOf(String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        return indent;
    }

    private static boolean isIgnorable(String line) {
        String trimmed = line.trim();
        return trimmed.isEmpty() || trimmed.startsWith("#");
    }

    /** 行首（给定缩进处）的映射键；不是 {@code key:} 形式时返回 {@code null}。 */
    private static String keyOf(String line, int indent) {
        String body = line.substring(Math.min(indent, line.length()));
        int colon = body.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String key = body.substring(0, colon);
        if (key.isBlank() || key.indexOf(' ') >= 0 || key.indexOf('\t') >= 0) {
            return null;
        }
        return key;
    }

    private static String restAfterKey(String line, int indent, String key) {
        String body = line.substring(Math.min(indent, line.length()));
        if (!body.startsWith(key + ":")) {
            return "";
        }
        return body.substring(key.length() + 1).trim();
    }

    private static String spaces(int count) {
        return " ".repeat(count);
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static GatewayMcpControlException invalid() {
        return new GatewayMcpControlException(GatewayMcpControlException.Reason.CONFIG_INVALID);
    }
}
