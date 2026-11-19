# Haitlify 部署状态

## 旧 Compose 已退役

新仓根目录的旧 `docker-compose.yml` 已删除，不再提供旧平台的一键启动、备份恢复或升级入口。旧配置包含固定容器名、旧数据库目标和资源复用指引，不适用于 Hermes 主助手重建。

本次只清理新仓配置和现行文档；不会删除旧仓、容器、镜像、数据库卷、凭据或历史验收材料。需要恢复旧环境时应单独授权，并使用旧归档环境的配置，不能从本新仓启动。

## 隔离环境定义与预检入口（已交付，未验收）

`deploy/isolated/compose.yaml` 已提供隔离栈定义（project `haitlify-isolated`，容器/网络/卷前缀 `haitlify-isolated-*`，库 `haitlify_isolated`，宿主端口 13306 / 19324 / 38082 / 38083，凭据只从 `deploy/isolated/.env` 读取且无默认口令）。

唯一受支持的启动入口是：

```bash
cp deploy/isolated/.env.example deploy/isolated/.env   # 填入真实值（不入库）
scripts/preflight/start-isolated-stack.sh check        # 只做预检
scripts/preflight/start-isolated-stack.sh start        # 预检通过后启动
scripts/preflight/start-isolated-stack.sh status       # 查看状态与预检新旧
scripts/preflight/start-isolated-stack.sh stop         # 停止容器，不删除卷/网络/镜像
```

`start` 会先解析 compose、校验隔离要求、写入绑定配置 hash 的预检结果，再复验 hash 未变，最后才启动。

- 直接 `docker compose up` **不是**受支持路径。拒绝启动的判定分两层：**预检**（`check`，共 27 项，含 `legacy-endpoints`、`runner-egress-policy`、`required-services` 与 `build-file-contents`）拒绝栈定义里的 `spring.datasource.*` / `SPRING_DATASOURCE_*` / `spring.flyway.*` 覆盖键、配置来源改写键（`spring.config.import` / `spring.config.additional_location` 及其 `SPRING_CONFIG_ADDITIONAL_LOCATION` / `SPRING_CONFIG_ADDITIONALLOCATION` / `spring.config.location` 等写法 / `SPRING_APPLICATION_JSON` / `JAVA_TOOL_OPTIONS` / `JDK_JAVA_OPTIONS` / `_JAVA_OPTIONS`）与指向旧栈宿主端点的环境值（主机形态含 loopback、`host.docker.internal`、`[::1]`、任意 IPv4 与 `.internal`/`.local` 域名，并对端口前导零 `:08080`、短写 `127.1`、整数 `2130706433`、IPv4-mapped `[::ffff:127.0.0.1]` 与展开式 IPv6 `[0:0:0:0:0:0:0:1]` 做等价归一后判定）；**平台进程**在缺少 `APP_ENV_ID`、缺少只读挂载的预检结果（verdict 非 PASS、或 `resolvedConfigHash` 与注入哈希不一致）、数据源不是隔离目标（库 `haitlify_isolated`、主机/端口只允许 `mysql:3306`、`127.0.0.1:13306`、`localhost:13306`；容器名不参与白名单）、或存在能改写数据源目标的属性/池配置时拒绝启动（环境层 + 数据源 bean 层）。预检还校验服务身份与镜像来源（mysql 只允许官方镜像且不得声明 `build`；非 mysql 服务必须由仓库内构建产生、不得使用外部镜像）、禁止 `extra_hosts`/保留名（含大小写变体）与服务名、`container_name`、网络别名抢占/`hostname`/`entrypoint` 覆盖/`profiles`（解析按全部 profile 生效，另在原始文本层面查 `profiles:` 声明）、禁止权限与设备类（`privileged`/`cap_add`/`devices`/`security_opt` 中除 `no-new-privileges:true` 以外的任何取值/`device_cgroup_rules`/`gpus`）、命名空间类（`pid`/`ipc`/`cgroup`/`uts`/`cgroup_parent`）与运行时资源控制类（`runtime`/`sysctls`/`group_add`/`volumes_from`/`deploy`/`ulimits`/`cpu_rt_runtime`/`oom_kill_disable`/`oom_score_adj`/`storage_opt`/`blkio_config`/`mac_address`/`use_api_socket` 等，完整清单见脚本 `FORBIDDEN_SERVICE_KEYS`；`read_only`/`tmpfs` 属加固、不拦）、服务级网络接入面（`link_local_ips`/静态 `ipv4_address`/`ipv6_address`/`priority`/`mac_address`/`driver_opts`/`interface_name`/`gw_priority`/`dns`/`dns_search`/`dns_opt`）、宿主端口绑定范围（每个发布端口**必须显式绑 `127.0.0.1`**——缺省或写 `0.0.0.0`/局域网 IP 都会把隔离服务暴露到局域网）、`secrets`/`configs`、跨服务共享卷与 external 卷/网络、仓库外构建输入（`build.context`/`dockerfile`/`additional_contexts` 的路径与远程形式，含 `git://`、`git@host:path`、`host/path.git#ref`、`docker-image://`）与构建期越界（`build` 子键只允许 `context`/`dockerfile`/`args`）、网络与卷的定义面改写（网络 `driver`/`driver_opts`/`ipam`、卷 `driver`/`driver_opts`）、**`env_file`/`include`/`extends` 三类机制**（见下方「机制禁令与允许清单」）与裸端口/端口范围。
- 环境层之外还有一道**数据源 bean 层**校验：数据源初始化完成、被任何使用者（含 Flyway）拿到之前，平台会读取它实际生效的池配置（`jdbcUrl` / `dataSourceClassName` / `dataSourceProperties`），凡被间接改写（例如通过挂载的配置文件设置 `data-source-class-name` / `data-source-properties`）或读不到目标，都会在建立任何连接之前拒绝启动。
- **机制禁令与允许清单**：`env_file` / `include` / `extends` 三类机制在预检里被拒绝，判定的层次要分清楚：**env_file 有解析层结构性判据**——第二个视图 `docker compose config --no-env-resolution` 会保留 `env_file` 路径（普通视图会把路径丢弃、只内联内容），别名当键/单 `!` 标签/`%TAG` 缩写/显式键/LS·NEL 行分隔/UTF-16/插值路径/长格式 `path`+`required:false` 等写法都被解析器归一后在这里现形（`extends`/`include` 继承来的 `env_file` 也会出现在对应服务上）；该 flag 不被支持或解析失败即 `BLOCKED`（fail closed）。**include/extends 本身没有解析层判据**——compose 在序列化前就把它们解析掉，任何 `config` 视图都不表达它们，因此这两类只能靠**文本层词级判定**（键位、取值、锚点位置出现这三个词即拒绝）。**允许清单**：解析结果的预期服务集合、每服务的服务级键集合与环境变量键集合必须落在 `ALLOWED_*` 常量内，机制以未知键/未知服务生效时会在这一层现形。**明确不宣称**：机制若只触及允许清单内的键，允许清单这层不会报；文本层是已知写法集合而非完备判定；这道门的真正边界是「谁能改这份栈定义文件**或**它引用到的任意路径上的文件（含仓库外）」。**栈定义新增服务或环境变量时必须同步更新这些常量**，否则预检按「未声明」拒绝。
- **镜像来源策略**：`mysql` 只允许官方 `mysql:8.4`（可带 digest）且**不得声明 `build`**（否则会从仓库内代码构建并打上同名标签）；`platform`/`web-runner`/`web` 必须声明 `build`（仓库内上下文）且**不得声明 `image`**——镜像名交给 Compose 按项目名自动生成（`haitlify-isolated-<service>`）；写 `image` 会让 Compose 先尝试拉取该名字对应的外部镜像（第十轮复核 `--dry-run` 实证），因此一律拒绝。
- **值面检查**（`value-surface`）：除 `environment` 外的所有字符串值（`healthcheck.test`、`command`、`build.args` 等）以及卷/网络的字符串值，都要过旧资源标识与旧栈端点检测——包含冒号形态（主机名大小写不敏感）与 `/dev/tcp/host/port`、`nc -z host port`、`mysqladmin -h host -P port`、带选项间隔如 `--connect-timeout=300 -P 3306` 这类非冒号形态；**数值宿主与端口按任意位置的 token 归一**后判定：十六进制/整数/短写点分/点分混合十六进制宿主（`0xc0a80105`、`3232235781`、`192.168.1`、`10.0`、`0xc0.0xa8.0x01.0x05`）与零填充/十六进制端口（`03306`、`0xCEA`）都算等价写法，无论出现在 `-h`、`--host=`、`/dev/tcp/`、`@`、`--resolve`、位置参数还是其它位置；覆盖口径与**已知边界**：纯十进制 ≤65535 的 token 当作端口、不作宿主候选；带前导零的十进制整数不作宿主候选（libc 把带前导零的段当八进制、语义歧义，且真实凭据里就有这种数字串）；纯十六进制且值 ≤65535 的也不作宿主候选。端口按**客户端实际读取语义**归一：十六进制整数（`0xCEA`、`0x000CEA`，≤64 位十六进制）、十进制（`03306`、`0003306`，≤128 位，前导零按十进制解释）、以及只取整数部分的尾缀形式（`3306.0`，即 `atoi` 语义）。点分宿主段按 libc `inet_aton` 语义解释：十进制、八进制（前导 0，如 `0300.0250.01.05` = 192.168.1.5）或十六进制（`0x…`，容许零填充如 `0x00c0.0xa8.1.5`，段宽 ≤64 位十六进制/≤128 位十进制）；**少于 4 段的写法末段承载剩余字节**（2 段=24 位、3 段=16 位、4 段=8 位：`0.0.300` = 0.0.1.44、`1.2.65535` = 1.2.255.255、`192.052000405` = 192.168.1.5）。单 token 十六进制最多 128 位（超过 8 位时**按字符串取低 8 位十六进制**再解析），十进制/八进制同样按**低 32 位**用 BigInt 截断（`4294967296` = 0.0.0.0、`0377777777777` = 255.255.255.255，与 libc 一致；注意不能用 `parseInt(...)%2**32`——超过 2^53 后 double 已舍入）；点分段宽 ≤64 位十六进制/≤128 位十进制。**超过这些上限的 token 不计入判定**（即该写法在门里不可见）——上限的目的是限制正则代价，libc 本身无长度上限（实测 100 位十六进制仍按低 32 位截断），门的上限是**实现边界**而非 libc 边界；含 8/9 的前导零串如 `…0123456789` 语义歧义、不作宿主候选。**已知过拦**（fail-closed 方向、保持并声明）：形如 `1.2.3`/`8.4` 的版本号或镜像 tag 也是合法点分形态，与旧栈端口在同一服务共现时会被拒。环境值另有一项 **`legacy-endpoints`**：`environment` 里的旧栈端点检测（含上面全部形态）记在这一项下；环境值里的**旧资源标识**检测仍归 `environment-values`，环境值里未声明的键归 `env-file-sources`。判定语义是 **fail-closed 的共现规则**：同一值里同时出现「非 loopback 主机 token」与「旧栈端口 token」即失败（因此形如 `curl "http://10.0.0.7:9323/health?max=8080"` 也会被拒）；**同一服务的多个值合并后**再过一遍同一规则（含等值归一；主机与端口拆到不同值同样被拒），合并面是 `environment` 全部值 + `healthcheck.test`/`command`/`entrypoint`/`build.args`（`labels`/`tmpfs`/`volumes` 等不并入），合并前剥掉允许的隔离目标（`mysql:3306`、`localhost`/`127.0.0.1` 加端口）。`APP_PORT` 的值由 `ports` 检查另管：必须是单一整数，且**该服务有发布映射时**必须等于其容器侧 `target`。冒号形态下的 `127.0.0.1:19323` 会失败；**只有非冒号形态**里的 loopback（以及非冒号形态里的 `0.0.0.0`，客户端在容器内等价于自身）被豁免。另有一项 **`runner-egress-policy`**：`web-runner` 必须显式声明**两个**出站开关 `APP_WEB_AUTOMATION_RUNNER_ALLOW_LOCAL_NETWORK_ACCESS` 与 `APP_WEB_AUTOMATION_LOGIN_DISCOVERY_ALLOW_PRIVATE_NETWORKS`，且取值恰为小写 `false`（Runner 侧语义是 `env[KEY] !== 'false'`，即「未设置即开启」；删行或写带引号的 `"FALSE"`/`"0"` 会静默放宽出站范围。未加引号的 YAML `false`/`False`/`FALSE` 经 compose 归一为字符串 `false`，等价、不算放宽）。`runner-egress-policy` 在 `web-runner` 服务**不存在**时自身即判 FAIL（不打印无承载者的肯定结论）；`…LOGIN_DISCOVERY_ALLOWED_HOSTS` **不在允许清单里**——未设置（空列表）才是该维度的最窄状态，任何写入都只会放宽（`*.internal`/`*.local`/私网字面量会短路登录发现的私网判定），因此写它会被 `env-file-sources` 拒。**`required-services`**：预期服务集合（`mysql`/`platform`/`web-runner`/`web`）必须齐全——服务不存在时，绑定在它上面的任何性质（包括上一条出站开关）都不再被宣称成立。**`build-file-contents`**：被引用的 `Dockerfile` 逐行检查（多解码）——判定分四层：① **逻辑行**（`ENV`/`ARG` 的反斜杠续行先拼成一行，FAIL 文案给逻辑行起始的**物理**行号；heredoc 体被移出后，其后的行号按剔除体后的序列计，可能小于物理行号）里按键取值——同时支持 `KEY=value` 与 Docker 的 space 形式 `ENV KEY value`（含裸键 `ARG KEY`；space 形式**不剥引号**，`ENV KEY "false"` 视为非 false——buildkit 旧格式取整行原文，引号会留在镜像值里）：出站开关键只允许恰好 `false`、`…LOGIN_DISCOVERY_ALLOWED_HOSTS` 一律拒绝、其余键位用与 compose 侧同一套锚定正则（`DATASOURCE_OVERRIDE_KEY`，覆盖 `spring.datasource*`/`spring.flyway*`/`spring.config.*`/`spring.application.json`/JVM 参数键，含 `SPRING_DATASOURCE_HIKARI_*` 这类池子键）；compose 侧 `build.args` 的**键名与值**同样检查（键名是禁写键、或值里出现禁写键名即拒绝）；② 行内出现旧栈宿主端点（含等价写法）即拒绝；③ 全文扫描 `…ALLOWED_HOSTS` 与配置来源/JVM 参数键的字面出现（**不限** ENV/ARG 行，但要求以**真正的键名赋值**形式出现——`KEY=…`、`-DKEY=…`（词边界含 `-D` 前缀）、或 ENV/ARG 的键位；注释/说明里只是**提及**键名不算命中，避免误报。注：键名**跨反斜杠续行拆开**也判得到——逻辑行按 buildkit 语义（去掉反斜杠与尾随空白后**粘连**，不插分隔符）拼回；键名若用 `printf '%s%s'` 之类拆名拼装，则由 ④ 的加载通道与 build.args 检查兜住——加载器写进 COPY/ADD 进来的入口脚本时同样成立）；④ 入口/构建命令（`RUN`/`CMD`/`ENTRYPOINT`/`ENV`，以及**会决定启动 shell 的 `SHELL` 与容器内也会执行的 `HEALTHCHECK`**，按逻辑行）采用**结构化禁令**而不是列举具体命令——禁的是「把文件内容读进命令/环境」的公共构造：命令替换（`$(`、反引号）、heredoc 与输入/进程替换（`<<`、`<(`、`<` 重定向）、`--env-file`、`set -a`/`set -o allexport`、命令词位置的 `. `/`source `（不限路径命名）、`NODE_OPTIONS` 里的预加载标志与 `node -r/--require/--import/--loader`、`BASH_ENV=`/`ENV=`、`dotenv` 命令。另外两条：**出站开关键在非 ENV/ARG 位置出现即拒**（`CMD ["sh","-c","export <键>=true; …"]` 会在运行时覆盖 compose 钉死值）；被 `ENTRYPOINT`/`CMD` **直接执行**的脚本按**同一套规则**扫描（与 Dockerfile 共用一份实现）。判定模型分三层：
  1. **直接执行 vs 解释器调用**：`ENTRYPOINT ["/app/entry"]`、`CMD ["/app/entry.txt"]` 是直接执行（该文件本身按脚本审）；`CMD ["node","server.mjs"]`、`ENTRYPOINT ["java","-jar","/app/app.jar"]` 是解释器调用（路径实参是数据，不按脚本审）；只有 shell 家族（`sh`/`bash`/`zsh`/`ksh`/`ash`/`dash`/`env`，**含 `bin/sh`、`usr/bin/env`、`/usr/bin/env` 这类相对与绝对路径形态，**且解释器名（含裸名 `sh`、带扩展名的 `entry.sh`）只有「按 `PATH` 解析后不在构建写入面」才算**（`PATH` = 同文件里的 `ENV PATH=…`/`ENV PATH …` 逐条应用、`$PATH` 自引用展开、相对段按 `WORKDIR`、最后回退 Docker 默认；裸名**不看** `WORKDIR`，Docker 用 `PATH` 查找）——写入面含 COPY/ADD 的具体 dest（同名文件后写胜）、`ADD 归档 /` 的整根、`RUN` 提到过的完整路径或目录（`install … -t /usr/local/bin`、`cp … .`））作首词时，其后的词才继续按脚本人；`env` 之后的内层命令（`env sh -c "exec /app/entry"`）同样会被摊平成词序列逐个判（含**裸相对名** `sh entry.sh`，按 `WORKDIR` 归一）。
  2. **整 token 才算路径**：`--spring.config.additional-location=file:/app/config/`、`-Dk=v`、`ln -s /var/lib/x` 这类「路径只是实参一部分」不再算入口（第二十五轮复核收窄）。
  3. **来源解析**：`COPY`/`ADD` 都可（**含 JSON 数组形式** `COPY ["a.sh", "/opt/app/run"]`）；往某目录 COPY 文件时该**目录**也计入构建写入面；目录源按上下文真实类型展开（`COPY scripts dest`／`COPY scripts/ dest`／`COPY . dest`，含深层子目录）、文件源按 basename 落位、glob 支持 `*`/`?`/`[abc]` 字符类与 POSIX 取反 `[!a]`（正则层等价于 `[^a]`）、`COPY --parents` 按保留的源路径结构解析（目录源=目标相对路径；**文件源=源路径本身**，且只有目标尾等于源路径时才算命中）`--from=`（其它构建阶段）、`ADD <远程 URL>`、源在上下文里不存在或指向仓库外 ⇒ **不可见 ⇒ FAIL**；**参与判定的是「最终镜像的写入面」**：最后一个构建阶段，**加上它通过 `FROM <stage>` 继承的所有前阶段**（被继承阶段的 COPY/ADD/WORKDIR 同样计入——否则继承来的入口脚本会被当基础镜像自带文件放行）；与最终镜像无关的独立前阶段不参与（它们的 COPY 不在最终镜像里，不能当作「将运行的那份」）——阶段边界按**逻辑行**（续行先粘连）判定，并跳过 heredoc 体（**只有 `RUN`/`COPY`/`ADD` 能带 heredoc，定界符必须在引号之外、与 `<<` **同词**（`<<- EOF` 这种带空白的写法在 buildkit 里根本不是 heredoc）、且取 buildkit 的整个词**——`<<EOF-2`、`<<notes.txt` 都合法；一行可有**多个定界符**（`COPY <<A <<B /dest`），每个体都跳过；终止行按 buildkit 语义：整行恰为标签才算结束，仅 `<<-` 去掉前导 TAB）。heredoc 体是**数据**：写 HTML/XML/含 `<<` 的文档都不参与语句层判定；`COPY <<E /app/entry.sh` 这种**用 heredoc 直接提供入口脚本**的写法，其体文本会被当作入口脚本内容扫描（不因为「源不在上下文里」而一刀 fail-closed）；**未终止的 heredoc**（找不到结束标签）⇒ FAIL（buildkit 会报 `unterminated heredoc`，门不能 PASS 一个构建必然失败的文件），因此「续行里伪装一行 `FROM …`」「引号/注释/LABEL 里写 `<<TAG` 制造幻影 heredoc」都不会重置阶段；目标先做路径归一（`/app/../secret` → `/secret`，不再算「在 `/app` 之下」），解析出的候选必须落在仓库内。
  **不可见即 FAIL（fail-closed）**，但只针对**构建会写入的位置**：目标落在某个 COPY/ADD 的具体 dest 之下（`WORKDIR` 只决定相对路径的落点、本身不算写入），或 Dockerfile 文本里被某条 `RUN` 提到过（如 `RUN printf … > /app/entry.txt`）；其余（`/docker-entrypoint.sh`、`/usr/local/bin/docker-entrypoint.sh`、`/usr/sbin/nginx` 这类**已知基础镜像位置**，且构建没往那里写过）视为基础镜像自带文件，不按本层判定。**不再对「无 COPY 来源」的目标做同名文件兜底扫描**（那会审成上下文里的另一份文件，第二十五轮复核已证伪该兜底）。`ENTRYPOINT`/`CMD` 里**命令位**上的变量（`exec $APP_ENTRY`、`CMD ["$APP_ENTRY"]`、`CMD ["sh","-c","exec ${APP_ENTRY}"]`）⇒ FAIL；非命令位的变量（`sh -c "echo $PORT && exec node server.mjs"`）不判。词序列按「JSON 数组展开 → 每个实参再去包裹引号 → 按空白**与 shell 分隔符（`;`/`&&`/`||`/`|`）**切词 → 每个词再去收尾引号」摊平，`cd <目录>` 的实参、选项词、shell 词本身、以及 `then`/`do`/`fi` 这类关键字都不算入口；`tini`/`dumb-init`/`gosu`/`su`/`su-exec`/`chroot`/`setpriv`/`timeout` 等**包装器**（含任意路径形态）进入「包装器模式」：其后的词**逐个**都按命令位候选评估：**带值选项会成对吃掉它的值**（`env -u HOME CMD`、`chpst -e DIR CMD`、`--chdir DIR`），**位置实参包装器的第一个路径是参数**（`envdir DIR CMD`、`flock FILE CMD`），`--exec=PATH`/`--startas=PATH`/`--command=PATH` 的内联值：含 `/` 直接算路径，裸名按「构建上下文里确有同名文件」判定（与命令位裸名同口径）；因此用户名/目录/数字不会被误收，因此在**已登记的工具与其选项表**下，`su -c CMD USER`、`setpriv --opt… CMD`、`timeout 30 CMD`、`start-stop-daemon --exec CMD` 里的真正命令都能被收到（表是白名单：未登记的**选项**按「开关」处理——这一侧只会少吞、不会吞掉真命令；但**内联**形态 `--opt=value` 只在选项属于「命令类」（`--exec`/`--startas`/`--command`/`--session-command`/`--split-string`）时才取其值为命令，其余按开关丢弃；短选项的粘连值（`su -cCMD`、`env -SCMD`）按命令处理；shell 形式的引号（`COPY "a" "/b"`）先剥离再判；但未登记工具的带值选项仍可能把命令当值吃掉，故新增工具时必须把它的带值选项补进表。GNU 长选项缩写（`--comm=`=`--command=`、`--exe=`=`--exec=`）在比对前归一）（包装器名单含 `tini`/`dumb-init`/`catatonit`/`s6-svscan`/`s6-setuidgid`/`s6-applyuidgid`/`start-stop-daemon`/`chpst`/`setuidgid`/`softlimit`/`envdir`/`runsv`/`runsvdir`/`sv`/`supervisord`/`gosu`/`su-exec`/`setpriv`/`timeout`/`stdbuf`/`nohup`/`nice`/`chroot`/`busybox`/`su`/`setsid`/`flock`/`chrt`/`taskset`/`numactl`/`strace`/`unshare`/`nsenter`/`ionice`/`cpulimit`/`setarch`/`prlimit`/`watch`；**带值选项按工具判定**——同一个字母在不同工具里语义相反（`bash -e`/`sh -u`/`flock -n` 是开关，`env -u NAME`/`sh -o errexit`/`flock -w SEC` 才带值），跨工具共用一张表会吃掉真命令）；**收到第一个真入口后退出包装器模式**（后续实参不再当入口）；包装器模式下**变量间接同样按有效命令位判定**（`su app -c $ENTRY`、`gosu app $ENTRY`、`timeout 30s $ENTRY` ⇒ FAIL）；`command`/`builtin`/`time`/`!`/`xargs` 这类执行前缀词保持命令位（`command /app/entry.sh` 仍是入口）；包装器之后若出现**解释器**（`tini node /app/server.mjs`）则其后的词是数据、退出包装器模式；`chroot <目录> CMD` 的目录实参被吃掉；`exec`/`.`/`source` 分支会清除「待吃目录实参」标志（否则 `chroot . CMD` 会把真命令一起吃掉）；`--` 同样保持命令位；`FOO=1 cmd` 的赋值前缀不打断命令位；dest 里的 `$VAR`/`${VAR}`/`${VAR:-default}` 用同文件 `ENV`/`ARG` 展开（`KEY=v`、`K=v K2=v2`、`K value` 三种写法都认；`ARG K` 无默认值时取 compose `build.args`；同名时 `ENV` 覆盖 `ARG`；解析不出的变量只在**同 basename** 的目标上按 fail-closed 处理），`WORKDIR` 同样展开；`SHELL` 与 `HEALTHCHECK CMD` 用**同一套「只审被构建写过的路径」规则**：把整条指令（含 `-c`/组合短选项 `-euc` 的载荷、`env -S` 的载荷、包装器之后、`--opt=PATH` 内联值）里像路径的 token 都取出来，只有**精确文件被构建写过**（COPY dest / RUN 写同名 / 不可见来源）或**整根写入面**才送审；变量间接（`sh -c "exec $ENTRY"`）按间接判 FAIL——`curl`/`wget`/`node`/基础镜像工具与 URL 天然放过（标准 HTTP healthcheck 不会被误判），裸名按 PATH 解析；相对路径接受 `./x` 与 `../x`（按当前 `WORKDIR` 解析）；`RUN` 的写入判定只认写动词（`cp/install/mv/ln/tee/dd/tar/unzip/curl/wget/git/rsync/scp/sed`）或**重定向进该目标**（`>/tmp/v`、`2>/dev/null` 不算，包管理器（`apt-get install bash`、`apt-get -y install`、`DEBIAN_FRONTEND=… apt-get install`、`sudo apt-get`、`apt update && apt-get -y install`）不算写文件）；**命令位上只要含 `/` 的词都算路径**（`/x`、`./x`、`../x`、`bin/tini`、`sub/entry.sh`、`usr/bin/dumb-init`，含 `=` 的整 token 路径如 `/srv/run=x` 也算）；**裸命令位上的无扩展名名字**（`exec entry`）只有在构建上下文里确实存在同名**文件**时才当入口（同名目录不算，避免把 `gosu appuser data` 这类实参误收）——否则会把 `CMD ["nginx", …]` 这类基础镜像命令误拦；含 `=` 的实参（`--spring.config.additional-location=file:/app/x`）不算路径。
  **不作内容扫描**：`.jar`/`.war`/二进制/字体等非脚本文本（判定是**扩展名 denylist**，不看内容）；**但若这类文件本身是被直接执行的入口**（`ENTRYPOINT ["/app/entry.bin"]`），一律按「内容无法静态审计」fail-closed；**解释器调用**里的应用模块（`CMD ["node", "/app/server.mjs"]`）也不扫。但**直接执行**的应用模块（`ENTRYPOINT ["/app/server.mjs"]`、`["/app/app.py"]`）仍会被读，只是**只做「禁写键名」判定**（`COPY <<E /app/app.py` 这类 heredoc 提供的入口脚本同样按扩展名分类，不套 shell 规则；**行注释与行尾注释里的提及不算命中**，但引号字符串里的 `KEY=…` 仍算）（出站开关子串命中；白名单键/配置来源与 JVM 键在 `KEY=`、`KEY =`、`os.environ['KEY']=`、`"KEY":` 这类**赋值/键值形态**下命中）——`if (a < b)`、`1 << 3`、`const ENV=…` 这类语言写法不再误拦。直接执行的目标**不看扩展名**：`.txt`/`.conf` 这类被 `RUN` 写出的入口同样按脚本判（第二十五轮修复的绕过面）；改名成 `.bin`/`.so` 的脚本同样会被执行，因此**可见性判定与扩展名无关**。
  **RUN 文本归一与写入面（第四十四轮口径）**：判定「RUN 写没写过某个名字」前先做 shell 归一——去引号、去反斜杠、`[s]h`→`sh`；整路径匹配带右边界（`sh.bak` 不算 `sh`）；`ADD` 的本地归档解到任意目录 ⇒ 该目录及以下都算写入面，解包器带 `-C/-d DIR` 时 DIR 整棵子树也算；`--parents` 的落点按 `dest + 源路径` 计算。`SHELL`/`HEALTHCHECK` 的路径只在**命令位**算入口，数据位变量（`test -f /app/$MARKER`、`echo $PATH`）与数据/产物扩展名（`.jar`/`.log`…）不送审。
  **解包与写动词的归一（第四十五轮口径）**：判定「RUN 写过什么」时先把 RUN 文本做 shell 归一（去引号/反斜杠、`[s]h`/`[]s]h` → `sh`），**写动词也在归一后的文本上判**（`c\p`、`c"p"` 算 `cp`）；解包器认 `tar`/`bsdtar`/`gtar`/`unzip`/`7z`，其目标目录支持 `-C /x`、`-C/x`、`--directory=/x`、`--directory /x`、`-d/x`、`-o/x`，**没有显式目录时按当前 `WORKDIR`（通常 `/`）**；`xargs` 视为命令前缀。
  **多阶段与多条指令的语义**：同一阶段里出现多条 `ENTRYPOINT`/`CMD` 时**只有最后一条生效**；`CMD` 不会重置继承来的 `ENTRYPOINT`，但**设置 `ENTRYPOINT` 会清空继承来的 `CMD`**；两者各自回溯到最近声明的阶段（都没有时，最近祖先的 `CMD` 就是运行时命令）。`COPY a.sh /app/` 与 `COPY b.sh /app/` 是**两个文件并存**，只有**同名文件**的后一次写入才覆盖前一次。
  **`ADD <归档|目录> /`**（只有 `ADD` 会解包，`COPY` 从不解包；`zip` 与裸 `.gz` 不会被解包）：整根都可能被写入，`/usr/local/bin` 这类已知基础镜像目录不再视为「镜像自带」。归档按**内容**嗅探（tar 的 ustar magic 与 V7 头校验和（含 signed/unsigned 双口径）、gzip/zstd/xz/bzip2 头），**不看扩展名**——纯文本 `rootfs.tar` 不算归档；`ADD rootfs /` 同样识别；glob 支持多段（`rootfs/*/*.tar`）；`--from` 与远程 URL 只写一个已知文件名（`dest + basename`）且不作本地解包；`--from` 内容未知时 dest `/` 视为整根；本地源不存在 ⇒ FAIL；`ENV KEY value with spaces` 是合法写法（整段剩余都是值），不判失败。
被引用的构建文件缺失即 FAIL（fail-closed）。**已知边界**：入口脚本扫描的是**构建上下文里的那一份**；若构建期用 `RUN` 覆写了 COPY 进来的同名路径（例如既 `COPY entry.sh /app/entry.sh` 又在后面 `RUN … > /app/entry.sh`），门审的是上下文里那份而不是镜像里最终那份——这类「同路径覆写」不在可见范围内。**已知过拦**（fail-closed 方向、保持）：`JAVA_TOOL_OPTIONS` 等常见的 JVM 调优键被整键拒绝；构建文件里的注释行同样参与端点扫描；禁写键名以**赋值形式**出现即拒绝（含写在引号字符串里的 `KEY=…`；注释/说明里只是提及不算命中）；④ 的结构化禁令会连带拒绝一些合法写法——`RUN echo "build: $(date)" > f`（命令替换）、`RUN source /opt/venv/bin/activate`、`. /etc/profile`、任何 `RUN x < file` 形式的输入重定向、`RUN pip install dotenv` 这类含 `dotenv` 词的命令。（第二十二轮已收窄：`-c` 之后不是引号时不再当作命令词边界，故 `RUN grep -c source f`、`RUN tar -c . -f x` 不再误报；引号内的 `<`（如 `node -e "1<2"`）与无词边界的 `npm config set -a foo` 也不再误报。）另：`-Dspring.*=…`/`--spring.*=…` 这类**命令行属性覆盖**在 RUN/CMD/ENTRYPOINT 里出现即拒（③ 的口径，第二十五轮补实现）。**已知过拦**（新收窄后的残余）：① 基础镜像把入口放在**构建写入面之内**的路径（`ENTRYPOINT ["/app/foo"]` 而 `/app/foo` 来自基础镜像）；② 无 COPY 来源的直接执行目标只要**不在已知基础镜像位置**（`/home`、`/srv`、`/data`、`/app2` 等）即拒——设计上保守，如遇误报请把该文件纳入构建上下文或改成解释器调用形式；③ 内层复合命令（`sh -c "exec /app/entry; echo done"`）里带分隔符的 token 会被当作路径而 fail-closed。历史品牌字串不在此项判定（属 A0 拆除清单跟踪的品牌元数据，扫描面从不覆盖）。
- **构建期越界**：`build` 子键采用**允许清单**（`context`/`dockerfile`/`args`），其余（`tags`〔可把自造镜像改名成 `mysql:8.4` 之类的保留标签〕/`dockerfile_inline`/`privileged`/`network`/`ssh`/`extra_hosts`/`entitlements`/`cache_from`/`cache_to`/`ulimits`/`shm_size`/`labels`/`no_cache`/`target` 等）一律拒绝；`build.context`/`dockerfile`/`additional_contexts` 必须是仓库内**真实**路径（符号链接按 realpath 判定）。
- **对抗性测试集**：`node --test scripts/preflight/environment-preflight.test.mjs`（CI 的 `environment-isolation-guard` 作业会跑）。**覆盖口径**：`check` 的 27 项检查里 25 项有**检查级**用例承重（第十九轮复核实测：把任一项强制判 PASS 都会让对应用例变红）；**检查级承重 ≠ 每条子规则都被钉住**——第二十二轮用禁用式变异实验台逐条回退时，发现 4 条整改与 1 处数值上限在用例集里零承重（已补用例）；另有「**单条子规则互为冗余**」形态（如行内端点的原始分支与归一分支），单独回退任一条都不会变红，只有整层回退才变红——这类冗余按「整层必须有钉」为口径，不按「每条分支各自有钉」，`host-port-availability` 与 `resource-inventory` 是宿主态检查（`--no-host-checks` 下为 SKIP/WARN），按设计无用例；`--expect-hash` 模式才会出现的 `config-hash` 另有相符/不符两条断言。用例集把第五～十九轮独立复核发现的绕过形态固化为负例（必须 FAIL，**每条都断言失败检查项**——断言只在 `[FAIL]` 行里匹配，含 CR-only 与 UTF-16 两条编码用例）与正例（必须 PASS）；另有一组**判定原语的直接单元断言**（`splitComposeLines`/`decodingsForScan`/`canonicalHostForm`/`collectLegacyPortTokens`/`looseLegacyEndpointMatch`，由预检脚本在直接运行时才执行 CLI 的守卫支持 `import`）——端到端用例无法区分这些层被拆掉时，由这组断言负责；只做 compose 解析，不访问 daemon、不起容器。新增检查或调整判定时，应同时补对应用例。
- 预检只读：不启动、不停止、不删除容器/卷/网络/镜像；资源清单快照写入 `deploy/isolated/.preflight/`（不入库）。

**这不等于 A0-E 通过。** 尚未完成的门：在目标主机实跑含宿主端口占用与资源清单对照的预检、Hermes 执行单元的隔离拓扑实测、协议与身份传播探针。交付与证据见 [A0 交付记录](docs/superpowers/a0/2026-10-16-a0-isolated-environment-and-legacy-inventory.md)。

## A0-E 验收口径（2026-10-19 变更：清单级正确性 + 运行时护栏）

前 46 轮的验收方式是「派独立复核者逐轮找新的 shell 写法绕过，整改后再找」，结果是每轮都能再找出新写法，而每修一处又引入新的误拒（第四十七轮实测：`tar -czf` 打包、`tar -tzf` 列表、`echo "tar -xzf …"` 文本、标准 healthcheck 探针被误判为失败）。这个方向已经接近边际收益为零，因此**验收口径改为两项，缺一不可**：

**（一）清单级正确性（文本层）**

文本层的承诺是**清单级**的，不是「覆盖任意 shell 构造」：

- 用例集（`scripts/preflight/environment-preflight.test.mjs`，当前 **235 条**）就是这份清单：清单内的危险写法**必须 FAIL**，清单内的合法写法**必须 PASS**。
- 清单之外**不再承诺拦截**。已知边界在本文与 A0 记录里逐条声明（解释器内联代码、复杂 shell 构造、构建期动态生成、`curl | sh`、多段 `cd`/子 shell 内的 `cd`、`HEALTHCHECK` 包装器链等）。
- 新增绕过形态时按**清单增补**处理：补一条负例 + 修一处判定，**不启动新一轮「无限对抗」**；不再为了未登记写法把判定改成大范围正则。
- 判定仍保持 fail-closed 方向：拿不准的写法宁可拒绝并记录为已知过拦，也不静默放行。

**（二）运行时护栏（真正的隔离边界）**

即使文本层漏掉某种写法，运行时约束也要让越界写入无法生效。这些不是文档承诺，是**可执行断言**：

| 护栏 | 要求 | 强制点 |
|---|---|---|
| 只读根文件系统 | `platform`/`web-runner`/`web` 必须 `read_only: true` | 预检 `runtime-hardening`；启动后 `docker inspect` 的 `ReadonlyRootfs` |
| 最小 capabilities | 四个服务都必须 `cap_drop: [ALL]` | 预检 `runtime-hardening`；`docker inspect` 的 `CapDrop` |
| 禁止提权 | 四个服务都必须 `security_opt: [no-new-privileges:true]` | 预检 `container-hardening`（只放行这一个取值）+ `runtime-hardening`；`docker inspect` 的 `SecurityOpt` |
| 非 root 运行 | 三个应用服务必须声明非 root `user` | 预检 `runtime-hardening`；`docker inspect` 的 `Config.User` |
| 可写面收敛 | 只读根下仅 `/tmp`（web 另加 nginx 的 cache/run、runner 加 `/dev/shm`）可写 | compose `tmpfs`；预检要求 `/tmp` 挂点存在 |

落地方式：

```bash
scripts/preflight/start-isolated-stack.sh check     # 27 项，含 runtime-hardening
scripts/preflight/start-isolated-stack.sh start     # 起栈后自动断言运行时护栏，不满足即判启动失败
scripts/preflight/start-isolated-stack.sh runtime   # 单独复验运行时护栏（审计/排障用）
```

`start` 里这一步直接问 Docker daemon（而不是相信 compose 文件）：`ReadonlyRootfs`、`CapDrop`、`SecurityOpt`、`Config.User` 四项逐容器核对，任一不符即输出 `拒绝：运行时护栏未满足` 并以非零退出——**「已 up」不再等于启动成功**。

**仍未闭合的宿主级项**（不在这两项口径内，属 A0 其余 Gate）：

- 构建期无网络：`build.network` 被预检禁用，但「构建容器默认能出网」这件事要靠宿主/CI 的 builder 配置（`--network=none` 或离线 builder）保证，当前未在目标主机验证。
- 镜像签名与 SBOM：未实现，属发布链路要求。
- 目标 Linux 主机实跑：宿主端口占用、资源清单、Docker 版本差异需在目标机上复跑一次。

**口径变更后的状态**：预检 **27 项 PASS**（24 PASS + 1 SKIP + 1 WARN + 新增 `runtime-hardening` PASS，`--no-host-checks` 下）、用例 **235/235**、`scan` 0 命中 / 6 skipped、`git diff --check` 干净。**这不等于 A0-E 通过**：目标主机实跑、Hermes 服务进 compose、A1 真实写入/回读仍未完成。

## 数据边界

不运行删除卷、清空数据库或全局 Docker prune。已应用数据库迁移不得改写；新 schema 如需重建，使用独立迁移位置和独立数据库。新部署配置实现且预检通过之前，不执行新服务启动或数据迁移。

## 租户与成员（V71/V72 之后）

**管理 API（ADMIN）**：`GET /api/tenants`、`POST /api/tenants {"code","name"}`、
`PUT /api/tenants/{id} {"name","active"}`、`GET /api/tenants/{id}/members`、
`POST /api/tenants/{id}/members {"username"}`、`DELETE /api/tenants/{id}/members/{username}`。
`default` 租户不允许停用（无租户用户的兜底归属）。
写操作走 Cookie 会话并校验 CSRF：curl 直调需先取 `XSRF-TOKEN` 再带 `X-XSRF-TOKEN`，否则 403。
以下 SQL 仅作接口不可用时的兜底。

- 迁移 `V71__tenant_model.sql` 建立 `tenants` / `tenant_members`，并给 `projects` 加 `tenant_id`；`V72__backfill_legacy_tenants.sql` 为账本里出现过的 tenant_id 补 `legacy-<id>` 租户、按历史 operation 回填项目归属。
- 启动引导 `TenantBootstrapInitializer` 幂等保证 `default` 租户存在，并把仍未绑定租户的项目挂到它。
- **非 ADMIN 用户必须先加入租户成员，否则所有助手端点都会 403**（`GET /api/projects` 返回空列表）。优先用上面的管理 API；接口不可用时手工执行：

```sql
-- 1) 建租户（code 全局唯一）
INSERT INTO tenants(code, name, active, created_at, updated_at)
VALUES ('team-a', 'A 团队', TRUE, NOW(), NOW());

-- 2) 加成员（username 必须已存在于 platform_users）
INSERT INTO tenant_members(tenant_id, username, created_at)
SELECT id, 'alice', NOW() FROM tenants WHERE code = 'team-a';

-- 3) 把项目挂到该租户
UPDATE projects SET tenant_id = (SELECT id FROM tenants WHERE code = 'team-a') WHERE code = 'PROJ-1';
```

- ADMIN 跨租户放行，但租户本身必须存在且 active；`projects.tenant_id` 为空的旧项目不参与一致性校验，建议尽快按上一步绑定。
- 回滚：`V71/V72/V73` 只新增表/列，不改旧列；回滚应用版本不需要删表，但新写入的 `tenant_id` 会被旧版本忽略。

### 升级到 V72/V73 的顺序与注意事项

- `V72` 为历史 `tenant_id` 建 `legacy-<id>` 租户，id 固定为 `历史 id + 1000000000`（避免与历史 id 或自增 id 相撞）；`V73` 用 `tenant_legacy_map` 把账面 `tenant_id` 搬到对应租户，并用 `tenant_migration_guard` 保证重放 no-op。
- **历史租户的新 id 不是原 id**，需要时按 `select from_id, tenant_id from tenant_legacy_map` 或 `select id from tenants where code = 'legacy-<原id>'` 查。
- 若 `V73` 因唯一键冲突失败（同一 `request_id`/`idempotency_key` 在两个历史租户下都有记录），**不要**自动去重：这是审计数据谁权威的问题，先人工确认，再 `flyway repair` 后重试。重跑是幂等的。
- 滚动升级窗口里，旧实例可能在本迁移之后继续写入旧 `tenant_id`。补救：删除 `tenant_migration_guard` 中 `v73_align_legacy_tenant_ids` 一行后，重跑 `V72` 再跑 `V73`（只跑 V73 会搬账本但不会重新绑定项目）。
- 偏移 id 会把升级库的 `tenants.AUTO_INCREMENT` 顶到 `1e9+`；**不要假设租户 id 连续或小于某个小值**。新库不受影响。
