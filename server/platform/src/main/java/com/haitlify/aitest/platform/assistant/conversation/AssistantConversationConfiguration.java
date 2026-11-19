package com.haitlify.aitest.platform.assistant.conversation;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.haitlify.aitest.platform.assistant.execution.ExecutionBindingService;
import com.haitlify.aitest.platform.assistant.hermes.HermesGateway;
import com.haitlify.aitest.platform.assistant.hermes.HermesTcpGateway;
import com.haitlify.aitest.platform.assistant.hermes.HermesTuiGateway;
import com.haitlify.aitest.platform.assistant.mcp.McpLeaseRegistry;
import com.haitlify.aitest.platform.assistant.runtime.HermesConversationService;
import com.haitlify.aitest.platform.auth.ProjectAccessService;
import com.haitlify.aitest.platform.tenant.TenantAccessService;
import tools.jackson.databind.ObjectMapper;

/**
 * 助手对话入口的装配，<b>默认关闭</b>：{@code app.assistant.conversation.enabled=true} 才会注册
 * 服务与控制器（关闭时路由 404，且不会有任何 Hermes 连接被建立——网关是懒连接的）。
 *
 * <p>执行单元的选择是显式的：取上下文里已存在的 {@link HermesGateway}；一个都没有时用
 * {@link UnavailableHermesGateway}（永远不可用）而不是新建一条未经配置的通道，更不会退回旧 AI 链路。
 * 这样「入口开着但传输没配好」的唯一表现是 503 + 需对账，而不是某个默认后端的静默成功。
 */
@Configuration
@ConditionalOnProperty(name = "app.assistant.conversation.enabled", havingValue = "true")
class AssistantConversationConfiguration {

    @Bean
    AssistantConversationRepository assistantConversationRepository(JdbcTemplate jdbc,
                                                                  PlatformTransactionManager transactionManager) {
        return new AssistantConversationRepository(jdbc, transactionManager);
    }

    @Bean
    AssistantConversationAccess assistantConversationAccess(JdbcTemplate jdbc, TenantAccessService tenants,
                                                            ProjectAccessService projects) {
        return new AssistantConversationAccess(jdbc, tenants, projects);
    }

    /**
     * 默认关闭的第二道开关：{@code app.assistant.conversation.mcp-lease.enabled=true}
     * <b>并且</b> {@code app.assistant.mcp.lease.enabled=true} 才会真的铸租约。
     *
     * <p>两个开关是两件事：前者决定「对话入口是否去铸」，后者决定「平台是否持有租约注册表 / 暴露
     * /mcp-lease/**」。缺任何一个都返回 {@code null}（Spring 的 NullBean），服务层按「无租约」处理——
     * 没有请求会因此落到一条更弱的认证路径上。
     */
    @Bean
    @ConditionalOnProperty(name = "app.assistant.conversation.mcp-lease.enabled", havingValue = "true")
    AssistantConversationLeaseProvisioner assistantConversationLeaseProvisioner(
            ObjectProvider<McpLeaseRegistry> registries,
            ExecutionBindingService execution,
            JdbcTemplate jdbc,
            @Value("${app.assistant.conversation.mcp-lease.public-base-url:}") String baseUrl,
            @Value("${app.assistant.conversation.mcp-lease.ttl-seconds:300}") long ttlSeconds) {
        McpLeaseRegistry registry = registries.getIfAvailable();
        if (registry == null || baseUrl.isBlank()) {
            return null;
        }
        return new AssistantConversationLeaseProvisioner(registry, execution, jdbc,
            Duration.ofSeconds(ttlSeconds), baseUrl);
    }

    @Bean(destroyMethod = "shutdown")
    AssistantConversationService assistantConversationService(
            AssistantConversationRepository repository,
            AssistantConversationAccess access,
            ObjectProvider<HermesGateway> gateways,
            ObjectProvider<AssistantConversationLeaseProvisioner> leaseProvisioners,
            ObjectProvider<GatewayMcpConfigurator> mcpConfigurators,
            ObjectProvider<GatewayMcpControl> mcpControls,
            ObjectProvider<ExecutionUnitPool> executionUnits,
            ObjectMapper mapper,
            @Value("${app.assistant.conversation.workspace:/state/workspace}") String workspace,
            @Value("${app.assistant.conversation.reply-timeout-seconds:60}") long replyTimeoutSeconds,
            @Value("${app.assistant.conversation.resume-idle-wait-seconds:10}") long resumeIdleWaitSeconds,
            // 陈旧预算要按真实链路推导，所以这两个上限必须显式传进去，不能在服务里写死默认值。
            @Value("${app.assistant.hermes.timeout-seconds:60}") long rpcTimeoutSeconds,
            @Value("${app.assistant.conversation.max-conversations:256}") int maxConversations,
            // 第三道开关：默认关闭。关闭时服务层一次都不碰配置器；打开却没有实现则启动即失败（不静默跳过）。
            @Value("${app.assistant.conversation.mcp-lease.configure-gateway:false}") boolean configureGateway) {
        Duration resumeIdleWait = Duration.ofSeconds(resumeIdleWaitSeconds);
        ExecutionUnitPool pool = executionUnits.getIfAvailable();
        if (pool != null) {
            // 单元化装配：每个 (tenant, project, actor) 一个网关子进程 + 一份私有 profile。
            // 网关实例、会话表与条目配置器都由单元提供；全局的 hermes 传输 Bean 在本模式下一次都不会被用到。
            return new AssistantConversationService(repository, access, leaseProvisioners.getIfAvailable(),
                workspace, Duration.ofSeconds(replyTimeoutSeconds), Duration.ofSeconds(rpcTimeoutSeconds),
                resumeIdleWait, null, pool, configureGateway);
        }
        HermesGateway gateway = gateways.orderedStream().findFirst().orElseGet(UnavailableHermesGateway::new);
        HermesConversationService conversations = new HermesConversationService(gateway, mapper, maxConversations,
            resumeIdleWait);
        return new AssistantConversationService(repository, access, conversations, gateway, workspace,
            Duration.ofSeconds(replyTimeoutSeconds), Duration.ofSeconds(rpcTimeoutSeconds), resumeIdleWait, null,
            leaseProvisioners.getIfAvailable(), gatewayMcp(configureGateway, mcpConfigurators, mcpControls));
    }

    /**
     * <b>执行单元池</b>（{@code app.assistant.conversation.execution-units.enabled=true} 才注册）。
     *
     * <p>这个 Bean 的存在本身就是「本轮用哪个网关进程」的装配选择：注册了它，对话服务就按
     * {@code (tenant, project, actor)} 解析单元；没有它，装配退回单进程形状（行为与单元化之前一致）。
     *
     * <p><b>四条启动纪律</b>（都在这里 fail-fast，而不是等到第一个用户提交）：
     * <ol>
     *   <li>必须给出<b>基准 profile</b> 与<b>单元目录根</b>；没有默认值，绝不回退到 {@code ~/.hermes}；</li>
     *   <li>基准 profile 必须同时有 {@code config.yaml} 与 {@code .env}，且都是常规文件（不跟随符号链接）；</li>
     *   <li>必须给出网关子进程命令（{@code app.assistant.hermes.command}）：单元是<b>子进程</b>，
     *       没有命令就没有隔离；</li>
     *   <li>传输必须是 {@code stdio}：执行单元自成子进程，{@code tcp} 模式下「每个单元一个进程」不存在，
     *       静默退回共享连接等于把这一轮的隔离关掉。</li>
     * </ol>
     *
     * <p><b>位置校验</b>（{@link ExecutionUnitRootPlacement}）：单元目录根落在推导出的 Hermes 根树之外时
     * 默认只 WARN（每个新单元都会重装一次运行时：秒级变分钟级），
     * {@code require-root-under-hermes-root=true} 才拒绝启动。推导不出根时只 WARN，不猜路径。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "app.assistant.conversation.execution-units.enabled", havingValue = "true")
    ExecutionUnitPool executionUnitPool(
            ObjectMapper mapper,
            @Value("${app.assistant.conversation.execution-units.base-profile}") String baseProfile,
            @Value("${app.assistant.conversation.execution-units.root}") String unitsRoot,
            @Value("${app.assistant.conversation.execution-units.capacity:8}") int capacity,
            @Value("${app.assistant.conversation.execution-units.idle-ttl-seconds:900}") long idleTtlSeconds,
            @Value("${app.assistant.conversation.execution-units.reap-interval-millis:1000}") long reapIntervalMillis,
            @Value("${app.assistant.hermes.transport:stdio}") String transport,
            @Value("${app.assistant.hermes.command:}") String command,
            @Value("${app.assistant.hermes.timeout-seconds:60}") long rpcTimeoutSeconds,
            @Value("${app.assistant.conversation.mcp-lease.gateway-reload-timeout-seconds:60}") long reloadTimeoutSeconds,
            @Value("${app.assistant.conversation.mcp-lease.gateway-server-name:haitlify}") String serverName,
            @Value("${app.assistant.conversation.max-conversations:256}") int maxConversations,
            @Value("${app.assistant.conversation.resume-idle-wait-seconds:10}") long resumeIdleWaitSeconds,
            // 位置校验的两个输入：HERMES_HOME 只在 base-profile 形状推导不出根时才是后备（缺省 = 没有）；
            // 开关默认 false（只 WARN），打开才把「根外」升级成启动失败。
            @Value("${HERMES_HOME:}") String hermesHome,
            @Value("${app.assistant.conversation.execution-units.require-root-under-hermes-root:false}")
            boolean requireRootUnderHermesRoot) {
        if (!"stdio".equals(transport)) {
            throw new IllegalStateException(
                "app.assistant.conversation.execution-units.enabled=true 只支持 stdio 传输（当前 "
                    + transport + "）。拒绝启动：执行单元必须是一个独立的网关子进程，"
                    + "tcp 模式下退回共享连接等于关掉用户×项目隔离。");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalStateException(
                "app.assistant.conversation.execution-units.enabled=true 但没有 app.assistant.hermes.command。"
                    + "拒绝启动：没有子进程命令就没有执行单元。");
        }
        if (baseProfile == null || baseProfile.isBlank() || unitsRoot == null || unitsRoot.isBlank()) {
            throw new IllegalStateException(
                "app.assistant.conversation.execution-units.enabled=true 但没有给出基准 profile 或单元目录根。"
                    + "拒绝启动：不回退到 ~/.hermes 或任何默认 profile。");
        }
        // 位置校验先于建目录：根外 + require=true 时一个目录都不留（拒绝要早于副作用）。
        ExecutionUnitRootPlacement.enforce(Path.of(baseProfile), Path.of(unitsRoot), hermesHome,
            requireRootUnderHermesRoot);
        ExecutionUnitSettings settings = new ExecutionUnitSettings(Path.of(baseProfile), Path.of(unitsRoot),
            List.of(command.trim().split("\\s+")), capacity, Duration.ofSeconds(idleTtlSeconds),
            Duration.ofMillis(reapIntervalMillis), Duration.ofSeconds(rpcTimeoutSeconds),
            Duration.ofSeconds(reloadTimeoutSeconds), serverName, maxConversations,
            Duration.ofSeconds(resumeIdleWaitSeconds), Map.of());
        return new ExecutionUnitPool(settings, mapper);
    }

    /**
     * 真实的网关控制面（写条目 + reload）。<b>只有显式给出配置文件路径时才注册</b>：
     * {@code app.assistant.conversation.mcp-lease.gateway-config-path} 在 {@code application.yml} 里
     * 没有默认值，所以默认部署（以及现有测试上下文）不会出现这个 Bean，也不会触碰任何配置文件。
     *
     * <p>同时要求上下文里有一个 {@link GatewayReloader}：只写文件不 reload，网关仍按旧配置建会话，
     * 而平台会以为本轮已配置。缺 reload 落点就启动即失败，不静默跳过。
     *
     * <p>路径与服务器名都由部署方给出；条目位置固定为<b>顶层</b> {@code mcp_servers.<name>.url}
     * （{@code <name>} 默认 {@code haitlify}），控制面只改这一段，绝不自动创建文件或父段。
     */
    @Bean
    @ConditionalOnProperty(name = "app.assistant.conversation.mcp-lease.gateway-config-path")
    GatewayMcpControl configFileGatewayMcpControl(
            @Value("${app.assistant.conversation.mcp-lease.gateway-config-path}") String configPath,
            @Value("${app.assistant.conversation.mcp-lease.gateway-server-name:haitlify}") String serverName,
            ObjectProvider<GatewayReloader> reloaders,
            ObjectProvider<GatewayMcpReceipt> receipts) {
        GatewayReloader reloader = reloaders.getIfAvailable();
        if (reloader == null) {
            throw new IllegalStateException(
                "app.assistant.conversation.mcp-lease.gateway-config-path 已设置，但没有 GatewayReloader Bean。"
                    + "拒绝启动：只写不 reload，网关会继续按旧配置建会话，而平台会以为本轮已配置。");
        }
        return new ConfigFileGatewayMcpControl(Path.of(configPath), serverName,
            ConfigFileGatewayMcpControl.DEFAULT_PARENT_PATH, reloader, receipts.getIfAvailable());
    }

    /**
     * 把上面那个控制面接到<b>唯一</b>被接受的租约实现上（{@link GatewayMcpLeaseConfigurator}）。
     * 注册条件与控制面相同：没有显式配置路径就不存在，所以默认关闭时服务层一次都不会调用。
     */
    @Bean
    @ConditionalOnProperty(name = "app.assistant.conversation.mcp-lease.gateway-config-path")
    GatewayMcpConfigurator configFileGatewayMcpConfigurator(GatewayMcpControl control) {
        return new GatewayMcpLeaseConfigurator(control);
    }

    /**
     * 真实的 reloader：复用 {@link HermesTcpGateway} 的连接、换行分隔帧与 pending 表发 {@code reload.mcp}
     * （{@code confirm=true}，默认 60s 预算），并判定 {@code status == "reloaded"} 且 {@code loaded_rev} 非空。
     *
     * <p>注册条件与 {@code HermesTcpGateway} 本身一致（{@code transport=tcp}），再加一条「显式配置了路径」——
     * 没有控制面时不该出现一个无人使用的网关出站通道。
     */
    @Configuration
    @ConditionalOnProperty(name = "app.assistant.hermes.transport", havingValue = "tcp")
    static class TcpGatewayReloaderConfiguration {

        @Bean
        @ConditionalOnProperty(name = "app.assistant.conversation.mcp-lease.gateway-config-path")
        GatewayReloader tcpGatewayReloader(
                HermesTcpGateway gateway,
                ObjectMapper mapper,
                @Value("${app.assistant.conversation.mcp-lease.gateway-reload-timeout-seconds:60}")
                long timeoutSeconds) {
            // reload 会全局 shutdown + rediscover，预算单独给（不跟着普通会话 RPC 的超时）。
            return new TcpGatewayReloader(gateway::rpc, mapper, Duration.ofSeconds(timeoutSeconds));
        }
    }

    /**
     * stdio 传输下的同一个 reloader：真实链路走 stdio 子进程，所以这里复用 {@link HermesTuiGateway} 的
     * 子进程、换行分隔帧与 pending/id 映射发 {@code reload.mcp}（{@code confirm=true}，默认 60s 预算）。
     *
     * <p>判定逻辑一份都不重写：{@link TcpGatewayReloader} 只依赖「发一条 RPC 拿回原始回包」这一个函数，
     * 传输由装配决定。注册条件与 {@link HermesTuiGateway} 本身一致（{@code transport=stdio}，
     * <b>未设置也算 stdio</b>——与网关 Bean 的 {@code matchIfMissing} 对齐），再加「显式配置了路径」：
     * 没有控制面时不该出现一个无人使用的出站通道。
     *
     * <p>不注册的反面仍然保留：既没给 {@code gateway-config-path} 就一个 Bean 都不出现；给了路径却没有
     * reload 落点，控制面 Bean 依旧启动即失败（只写文件不 reload = 平台以为配好了、网关还在用旧条目）。
     */
    @Configuration
    @ConditionalOnProperty(name = "app.assistant.hermes.transport", havingValue = "stdio", matchIfMissing = true)
    static class StdioGatewayReloaderConfiguration {

        @Bean
        @ConditionalOnProperty(name = "app.assistant.conversation.mcp-lease.gateway-config-path")
        GatewayReloader stdioGatewayReloader(
                HermesTuiGateway gateway,
                ObjectMapper mapper,
                @Value("${app.assistant.conversation.mcp-lease.gateway-reload-timeout-seconds:60}")
                long timeoutSeconds) {
            // 预算与 tcp 版同一个属性：reload 是全局 shutdown + rediscover，不跟普通会话 RPC 的超时。
            return new TcpGatewayReloader(gateway::rpc, mapper, Duration.ofSeconds(timeoutSeconds));
        }
    }

    /**
     * 配置器的注入策略：<b>开关关闭就绝不注入</b>（即使上下文里有实现，也一次都不调用）；开关打开时，
     * 装配必须能证明「真的会写网关条目」，任一条件不满足就启动即失败：
     * <ol>
     *   <li>上下文里必须有一个 {@link GatewayMcpConfigurator}；</li>
     *   <li>那个对象必须是 {@link GatewayMcpLeaseConfigurator}——只认这一类实现，因为只有它按
     *       「先写条目、再 reload」的顺序把<b>租约 URL</b> 写进去。手注册一个 {@code url -> {}} 的空实现
     *       同样能通过旧检查（类型对、实际什么都不做），网关会一直挂着上一次的 URL，而平台以为
     *       「本轮已配置」——这正是要 fail-fast 的那类静默；</li>
     *   <li>上下文里必须有一个 {@link GatewayMcpControl}（写条目 + reload 的实际落点）；没有它，
     *       上面那个实现只是在进程内空转。</li>
     * </ol>
     *
     * <p>真实的控制面 Bean 只由 {@code gateway-config-path} 触发（见上一个 Bean），默认配置里没有这个属性；
     * 因此「开关打开但没有落点」仍然启动即失败，默认部署行为不变。
     */
    static GatewayMcpConfigurator gatewayMcp(boolean configureGateway,
                                             ObjectProvider<GatewayMcpConfigurator> configurators,
                                             ObjectProvider<GatewayMcpControl> controls) {
        if (!configureGateway) {
            return null;
        }
        GatewayMcpConfigurator configurator = configurators.getIfAvailable();
        if (configurator == null) {
            throw new IllegalStateException(
                "app.assistant.conversation.mcp-lease.configure-gateway=true 但没有 GatewayMcpConfigurator 实现"
                    + "（需要 GatewayMcpControl）。拒绝静默跳过：跳过会让网关继续持有旧的租约 URL。");
        }
        if (!(configurator instanceof GatewayMcpLeaseConfigurator)) {
            throw new IllegalStateException(
                "app.assistant.conversation.mcp-lease.configure-gateway=true 但绑定的 GatewayMcpConfigurator 是 "
                    + configurator.getClass().getName() + "，不是 GatewayMcpLeaseConfigurator。拒绝启动："
                    + "只有租约实现会按「先写条目再 reload」把本轮 URL 写进网关，其它实现可能什么都不做，"
                    + "而平台会以为本轮已配置（网关继续持有旧 URL）。");
        }
        if (controls.getIfAvailable() == null) {
            throw new IllegalStateException(
                "app.assistant.conversation.mcp-lease.configure-gateway=true 但没有 GatewayMcpControl Bean。"
                    + "拒绝启动：没有写条目 + reload 的落点，租约 URL 不会真的到达网关。");
        }
        return configurator;
    }
}
