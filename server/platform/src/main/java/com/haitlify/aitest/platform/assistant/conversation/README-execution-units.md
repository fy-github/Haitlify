# 执行单元池（用户 × 项目隔离）

`app.assistant.conversation.execution-units.enabled`（默认 `false`）打开后，一轮对话不再共用一个全局网关，
而是由 `(tenantId, projectId, actorId)` 决定它用哪个网关子进程。

## 为什么需要它

Hermes 在一个**进程内**只有一个 MCP 服务器条目，而且这个条目在**会话创建的那一刻**被读走并绑定到会话
（见 `mcp/README.md`）。于是单进程部署里必须有一把全局许可串行「武装 → 配置 → 派发」，第二个用户的并发
提交只能被拒绝（`MCP_PERMIT_BUSY`）。那个拒绝是正确的，但它不应该是终态。

把执行单元做成**独立子进程**之后：

* 每个单元读**自己的** `config.yaml`，别人的 lease URL 不会出现在里面；
* 许可下沉到单元（`GatewayMcpPermit`，每单元一把），跨单元天然并行；
* 回收单元 = 杀子进程 + 删目录，没有跨用户残留的活状态。

## 单元

| 项 | 取值 |
|---|---|
| 键 | `ExecutionUnitKey(tenantId, projectId, actorId)`；三者都是服务端派生的正数 id |
| 目录 | `<root>/unit-XXXXXXXX/`，权限 `0700`（= 该单元的 `HERMES_HOME`） |
| 配置 | `<unit>/config.yaml`（基准 profile 的副本，`0600`）：本单元唯一的 MCP 条目在这里 |
| 凭据 | `<unit>/.env`（基准 profile 的副本，`0600`） |
| 进程 | `app.assistant.hermes.command` 起的子进程，环境里 `HERMES_HOME=<unit>` |
| 内部件 | 自己的 `HermesTuiGateway` / `TcpGatewayReloader` / `ConfigFileGatewayMcpControl` / `GatewayMcpLeaseConfigurator` / `GatewayMcpPermit` / `HermesConversationService` |

**基准 profile 只由配置给出**（`base-profile`），必须同时有 `config.yaml` 与 `.env`，且都是**常规文件**
（符号链接一律拒绝：它可以指向真实用户的默认配置文件）。缺失或不可读时**启动即失败**；
代码里没有任何操作者配置目录默认值。

## 池

`ExecutionUnitPool`：按 key 复用、有界容量、空闲 TTL 回收、关闭即确定性回收。

* **复用**：同一 key 的连续轮次拿到同一个单元（同一份配置、同一把许可、同一张会话绑定表）。
  绑定表是进程内状态，所以单元被回收后下一轮会按账本里的 `stored_session_id` 走 `session.resume`。
* **容量**：`capacity` 是硬上限。满了就 `CAPACITY_EXHAUSTED` 显式失败——**不**驱逐在跑的单元、
  **不**把新 key 塞进既有单元、不排队。
* **在飞租约**（`UnitLease`）：`acquire(key)` 在**返回之前**就给该单元记一份在飞租约（引用计数），
  调用方在终态与**所有**失败/异常出口归还它（幂等）。它是「本单元有一轮正在跑」的**唯一权威标记**，
  **与 MCP 许可无关**：`configure-gateway=false`（默认）时许可一次都不会被取，拿它当标记会让默认配置下的
  在飞轮次被确定性回收。也不依赖 `idle-ttl` 的长短——`idle-ttl` 只决定「最后一轮结束之后多久回收」，
  不参与「这一轮还没结束」的判定。
* **空闲回收**：`idle-ttl-seconds` 内未被使用、**且没有任何在飞租约**的单元由回收线程关闭（先杀子进程、
  再撤销许可、最后删目录）。有在飞租约的单元一律跳过：回收它等于把那一轮的会话连同终态一起扔掉。
* **关闭**：`close()` 拒绝新轮次 → 停回收线程 → **有界等待在飞租约归还**（`CLOSE_LEASE_DRAIN_GRACE`，
  2s）→ 逐个关闭单元。策略是「**等，不静默拆**」：持租约时 `close()` 会阻塞到租约归还；宽限到期才强制
  回收并记 WARN（含单元键与剩余租约数）。正常关闭顺序里这个等待一次都用不到——
  `AssistantConversationService.shutdown()` 先把在途轮次落成 `UNKNOWN` 并归还租约（服务 Bean 依赖池
  Bean，Spring 先销毁依赖方）。之后 `acquire` 一律 `CLOSED`。

## 与对话链路的接线

`AssistantConversationService` 只依赖 `GatewayUnits`：

1. `acquire(key)` 解析本轮单元（拿不到 → `FAILED` + `EXECUTION_UNIT_UNAVAILABLE`，**不**回退共享单元）；
   **返回时已持有一份该单元的在飞租约**，因此这一步之后回收/关闭都不会碰这个单元；
2. 本地闸门（账本 + **该单元**的会话句柄）；
3. 取**该单元**的许可（被占 → `MCP_PERMIT_BUSY`，与单进程形状一致；配置关闭时一次都不取）；
4. 武装租约（`AssistantConversationLeaseProvisioner` 仍是全局权威：租约注册表与账本都是全局的）；
5. `configure`：把 URL 写进**该单元**的 `config.yaml` 并 `reload.mcp`；
6. 派发（`session.create`/`session.resume` + `prompt.submit`）。

终态收尾在**同一个单元**里执行：先清条目、再放许可、**最后归还在飞租约**（`activeTurns` 里连单元一起记，
所以即使池在这之间回收了单元，也不会去清别人的条目）。归还点在结构上是 `finally`，因此超时、派发失败、
`Error` 这些出口同样会归还——租约漏还的后果不是「慢一点回收」，而是那个单元永远回收不掉。

## 单元目录根的位置（必须在 Hermes 根树之下）

单元的 `HERMES_HOME` 就是它的目录 `<root>/unit-XXXXXXXX`。子网关只有在**默认 Hermes 根树之下**
（例如 `<Hermes-root>/execution-units`）才会**借用宿主已经装好的运行时**，1~2 秒就绪；一旦落在外面（`/tmp`、`/var/lib` 等），
它会把这个单元目录当成一个全新的数据根，打印 `preparing dependencies for this data root` 并跑一次完整的
`uv sync`（约 1.3G、分钟级），并发建单元还会在同一把安装锁上互抢。也就是**每建一个单元重装一次运行时，
秒级变分钟级**。

所以装配阶段会做一次位置校验（`ExecutionUnitRootPlacement`）：

| Hermes 根怎么来 | 说明 |
|---|---|
| `base-profile` 形如 `<root>/profiles/<name>` | 取 `<root>`。部署方已经表达过意图，比环境变量更权威 |
| 否则 `HERMES_HOME` | 由宿主给出 |
| 都没有 | **无法判定**：只 WARN 一次，不猜路径、不默认任何 Hermes 根 |

| 结论 | 默认行为 | `require-root-under-hermes-root=true` |
|---|---|---|
| 根内 | 无日志，正常启动 | 无日志，正常启动 |
| 根外 | **WARN**（含后果与修法），**正常启动** | **启动失败**（且发生在建任何目录之前） |
| 无法判定 | **WARN** 一次，正常启动 | 仍是 WARN + 正常启动（不把「不知道」当「根外」） |

比较是**路径段**比较（两边先转绝对路径并归一化，再用 `Path.startsWith`），不是字符串前缀：
`<root>/hermes-extra` 与 `<root>/hermes` 共享字符串前缀却是两个目录，用字符串前缀会把前者误判成「在根树里」。
归一化也吃掉了 `<root>/units/../outside` 这种写法。比较是**词法**的（不解析符号链接，启动期不碰文件系统），
因此 `root` 不必预先存在。

代码里没有任何操作者配置目录默认值：这个校验只**报告**位置，从不替部署方改路径。

## 配置

```yaml
app.assistant:
  hermes:
    transport: stdio            # 必须；tcp 下没有「每单元一个进程」，开启即启动失败
    command: "<gateway cmd>"    # 必须
  conversation:
    execution-units:
      enabled: true
      base-profile: /etc/haitlify/hermes-profile   # 必须有 config.yaml 与 .env
      root: /var/lib/haitlify/execution-units      # 单元目录根（0700 子目录建在它下面）
                                                   # 放在 Hermes 根树之外 → 每单元重装运行时（默认只 WARN）
      capacity: 8
      idle-ttl-seconds: 900
      reap-interval-millis: 1000
      require-root-under-hermes-root: false        # true 时「根外」直接拒绝启动
```

开启但缺 `base-profile`/`root`/`command`、或 `transport != stdio`、或基准 profile 缺文件 → **启动失败**。
单元目录根在推导出的 Hermes 根树之外 → 默认只 **WARN**（`require-root-under-hermes-root=true` 才是启动失败）。
`mcp-lease.configure-gateway=false` 时单元仍会建，但一次都不写条目（与本功能之前的行为一致）。

## 测试

* `ExecutionUnitPoolTest`：临时基准 profile + `python3` 桩子进程，钉住隔离、复用、容量、回收、并行，
  以及在飞租约的四条性质（挡回收、逐份计数、释放幂等、只保护自己的单元）与关闭时的「等租约」策略。
* `ExecutionUnitRootPlacementTest`：位置校验的四种结论——根内无 WARN、根外 WARN 且启动成功、
  根外 + `require=true` 启动失败（且一个目录都没建）、无法判定时只 WARN；另外钉住推导顺序
  （`<root>/profiles/<name>` 优先于 `HERMES_HOME`）与「`.hermesX` 不在 `.hermes` 之下」的路径段比较。
* `AssistantConversationExecutionUnitTest`：真实 Spring 上下文 + 真实租约铸造 + 两个用户各一轮，
  断言两个单元在 `prompt.submit` 时刻的配置文件里**只有各自的** lease URL；另外钉住
  「默认配置（`configure-gateway=false`）+ `idle-ttl=1ms` 下在飞轮次不被回收、终态后可被回收」，
  以及超时 / 派发失败 / `Error` 三个出口都归还在飞租约。
* `AssistantConversationActiveTurnRemovalTest`：扫描服务源码，钉住 `activeTurns` 的摘除点必须传
  `ActiveTurn`（`remove(k, String)` 编译通过但恒不命中，是最危险的那类「看起来守住了」）。

三者都不读任何真实 profile、不启真实 Hermes、不调模型。
