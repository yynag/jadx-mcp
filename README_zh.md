# jadx-mcp

`jadx-mcp` 为 AI Agent 提供统一的 JADX 无头分析与 GUI 会话能力。服务提供 REST 管理 API，并通过 `/mcp` 暴露 **MCP Streamable HTTP**。不支持 MCP Stdio。

> **安全提示：** Master 没有通用身份认证或内置 TLS，默认监听 `127.0.0.1`。CORS 宽松且 MCP DNS rebinding 防护关闭；回环地址也不等于身份认证，仅允许可信客户端与输入。优先使用 SSH 隧道。显式使用 `--host` 开放内网访问时，必须配置防火墙/访问控制；禁止直接暴露公网。[^runtime]

## 组件说明

- **Master 服务**：管理会话、提供 MCP/REST 接口并路由请求。
- **无头 Worker**：每个 APK/DEX/JAR 使用独立 JVM，仅监听本机回环地址。
- **JADX GUI 插件**：将每个 GUI 实例注册到 Master，通过客户端主动发起的长轮询通道通信；Master 不反向连接 GUI。
- **MCP 客户端**：通过 Streamable HTTP 连接 Master；没有 Stdio 传输方式。

所有分析调用都使用 `session_id`。无头与 GUI 是同一 MCP 接口下互补的两种后端：工具会按目标会话类型路由，后端专属操作和参数会明确限制。同一会话请求串行处理，不同会话可并行。

## 环境要求

- 构建和运行 Master/Worker 需要**完整 JDK 21**，不使用裁剪 JRE/自定义运行时。Worker 使用 `jdk.httpserver`，HTTP 客户端和 XML 处理还需要 `java.net.http`、`java.xml`；GUI 需要桌面模块（`java.desktop`），Java 测试样例需要编译器。仅 `--help` 通过不能验证这些分析/运行依赖。
- GUI 插件针对 **JADX GUI 1.5.6**，其它版本未验证。JADX core/GUI 和 Gson API 由宿主提供，不在插件中打包替换宿主 API。[^build]
- MCP 客户端须能访问 Master；使用 GUI 插件时，GUI 所在主机须能主动向 Master 发起 HTTP 请求。

项目已包含 Gradle Wrapper，无需单独安装 Gradle。

## 构建

在仓库根目录运行：

```bash
./gradlew releaseArtifacts
```

构建产物：

| 组件                   | 产物                                            |
| ---------------------- | ----------------------------------------------- |
| Master + Worker 完整包 | `build/release/jadx-mcp.jar`                   |
| JADX GUI 插件          | `build/release/jadx-mcp-gui-plugin.jar` |

统一分发 `build/release/` 的两个 Shadow JAR；应用普通 `plain` JAR 仅供内部使用，GUI 普通 `jar` 任务已禁用。当前发布任务和工作流收集两个完整包。[^build]

运行四个模块的完整测试：

```bash
./gradlew test --rerun-tasks
```

## 安装并启动 Master 服务

1. 按上文运行 `releaseArtifacts`，或从可信发布渠道获取 `jadx-mcp.jar`。
2. 在负责分析 APK 的主机启动服务：

   ```bash
   java -jar build/release/jadx-mcp.jar --help
   java -jar build/release/jadx-mcp.jar --port 8650
   ```

   无需也不接受缓存根目录配置。参数有 `--host`（默认 `127.0.0.1`）、`--port`（默认 `8650`）、`--worker-heap`（默认 `2g`）、`--log-level`（默认 `INFO`）。支持 `--log-level=DEBUG` 和 `--log-level DEBUG`，等级严格使用大写名称；非法值或空值会使启动失败。`--help` / `-h` 须作为单独参数使用。例如：

   ```bash
   java -jar build/release/jadx-mcp.jar --worker-heap 8g
   ```

3. 检查服务是否可达：

   ```bash
   curl http://127.0.0.1:8650/health
   ```

只有确需远程访问时才显式指定 `--host <可信内网地址>`。无头会话使用的 APK 路径由 **Master 主机**解析，而不是 MCP 客户端所在主机。Worker JVM 使用回环地址和每进程 bearer token，仅供内部通信，不是公开 MCP 端点。[^runtime]

### 日志

```bash
java -jar build/release/jadx-mcp.jar --log-level=DEBUG
```

| 所选等级 | 应用输出（包含更高严重等级） |
|---|---|
| `TRACE`、`DEBUG` | 请求/后端开始完成、ID、操作名、耗时，以及生命周期 |
| `INFO`（默认） | 组件启停、MCP client 初始化/关闭、HEADLESS 打开关闭、GUI 注册/重连/断连/关闭 |
| `WARN` | 可恢复拒绝、加载中止、terminal worker 警告，以及失败 |
| `ERROR` | 仅失败 |
| `OFF` | 不输出应用/依赖库日志 |

等级在构建运行时之前生效，并由每个 worker JVM 继承；它会替换 Logback 显式 logger 等级。内置控制台和滚动 `logs/server.log` 遵守所选阈值，`logs/error.log` 额外仅收 ERROR。Worker 文件始终位于本会话生成目录的 `session-<unique>/logs/`，不会写入 Master 日志。**所有第三方 logger，包括 JADX、MCP SDK、Ktor、Netty，即使在 DEBUG/TRACE 下也额外限制为 WARN+。** 这是保守的 payload 安全过滤策略，DEBUG 提供应用诊断，**不等于开启所有库 DEBUG**；SDK 的 TRACE 工具参数转储不会开启，直接 stdout 初始化横幅也被关闭。该参数不重配已运行的原生 GUI 宿主。[^logging]

日志不记录 token、payload、源码、选区、注释正文或输入路径。应用失败仅记 code/type，DEBUG 也不追加异常 message/stack；依赖库 WARN/ERROR 可能自带堆栈，分享前仍须脱敏。帮助/非法 CLI 及 JVM/native 诊断不属于普通 Logback 事件。Poll/heartbeat、每次 HTTP 请求都不是 INFO 生命周期。MCP `sdkSessionId` 是协议会话标识，不是认证身份，也未必等于 HTTP `Mcp-Session-Id`。SDK 初始化后只记录一次接入，transport/session 关闭（DELETE 或 shutdown）只记录一次退出。**无 DELETE 的静默断网没有 SDK 默认空闲过期，不能声称立刻检测退出。** GUI 注册单独标注，不计作外部 MCP client。见[使用说明](docs/usage/gui-and-cache.md#logging-and-connection-semantics)和[新发布包验收](docs/testing/validation.md#logging-acceptance-latest)。[^logging]

## 输入同级生成数据

无头会话使用固定后缀 `.jadx_mcp_cache`，加在**包含扩展名的完整输入文件名**之后。`/work/demo.apk` 的生成数据位于 `/work/demo.apk.jadx_mcp_cache/`；JAR、DEX 等支持的文件输入使用同一命名规则。原 `_jadx_cache` 目录不读取、不自动迁移、不删除。相对路径转绝对路径并规范化；输入 symlink 解析到真实目标，因此使用目标的目录和文件名。缓存位置不可写或不安全时明确报错（`PERSISTENCE_FAILED`），不回退到集中目录或系统临时目录。[^cache]

```text
/work/
├── app.apk
├── app.apk.jadx                  # 用户原生工程，不归本清理逻辑所有
└── app.apk.jadx_mcp_cache/
    ├── session-<唯一值-A>/
    │   ├── .jadx-mcp-owner
    │   ├── jvm-temp/
    │   ├── plugin-config/
    │   ├── plugin-cache/
    │   ├── temp/
    │   ├── output/               # 原生输出目标，按需创建
    │   └── generated-aliases.jobf # 按需生成，不是用户修改
    └── session-<唯一值-B>/       # 独立并行会话
```

每次新建会话都分配新的独占子目录，**不复用之前生成的缓存或别名**。普通关闭保留数据。`session_close(clear_cache=true)` 先停止该 worker，再仅删除 marker 和目录身份校验通过的本会话子目录，不跟随 symlink，不碰其它会话、输入、原生 `.jadx` 工程或共享根目录内已有数据。共享根目录即使清空也保留；有活动会话时不要手动删除它。普通删除 APK **不会**自动删除同级目录：没有 watcher 或自动联删，用户可在关闭会话后将输入与缓存目录一起清理。

此布局管控无头 worker 生成数据，**不是所有 JADX GUI 数据**。GUI 原生工程与宿主缓存仍归宿主，不擅自迁移；GUI 插件自建的源码响应缓存位于内存。生成别名不补齐既有 headless 工程/修改持久化缺项。清理检查拒绝 marker/path 替换，但可移植 Java 路径操作无法保证抵御恶意同用户进程在校验与删除间竞态换目录。[^cache]

## 连接 MCP 客户端

在客户端配置远程 **Streamable HTTP** 传输，使用下列 URL（没有 Stdio 启动命令或 OAuth 服务）：

```text
http://127.0.0.1:8650/mcp
```

默认 HTTP 连接没有认证和加密。请使用可信隔离网络、防火墙规则或 SSH 隧道。例如客户端可通过 `ssh -L 8650:127.0.0.1:8650 <server>` 转发本地端口，再连接 `http://127.0.0.1:8650/mcp`。

## 安装 JADX GUI 插件

1. 构建插件：

   ```bash
    ./gradlew releaseArtifacts
   ```

2. 在 JADX-GUI 1.5.6 的 **Preferences → Plugins → Install plugin** 中使用本地 JAR 选择器，选取 `build/release/jadx-mcp-gui-plugin.jar`，安装并重启。使用宿主安装器，不猜测操作系统插件目录。
3. 先启动 Master。同一主机上的 GUI 默认连接 `http://127.0.0.1:8650`。
4. 打开唯一入口 **Plugins → Jadx MCP**，同一个非模态窗口内查看实时计数并配置连接。分别填写 **IP** 和 **Port**，例如 `192.168.1.100`、`8650`，默认 `127.0.0.1`、`8650`。支持数字 IPv4/IPv6，IPv6 不带方括号（如 `::1`），端口范围 1–65535。不要填写域名、scheme、路径或完整 URL，插件内部自动构造 HTTP 地址。**Apply** 后台保存 Java Preferences 并切换 bridge，窗口保持打开；没有 **Cancel** 按钮，成功提示为 **Applied!**，两项均已通过最终隔离 GUI 检查。重复点击菜单只聚焦同一个窗口。既有 HTTP 数字 IP origin 自动拆分，旧域名/HTTPS 配置需重新填写。首次保存前读取内部 origin 系统属性 `jadx.mcp.url`、`jadx-mcp.master`，也遵循相同限制。
5. 打开输入/项目并等待运行时绑定后，用 `session_list` 确认注册，使用返回的 GUI `session_id`。只有项目成功加载/绑定后桥接才启用；卸载时注销，断连 lease 超时后过期。现有运行实例需要替换插件并重新加载，构建不会自动更新它们。[^gui]

### 后端能力

以下分析工具支持两种后端，但输出细节可能不同：`meta_summary`、`meta_manifest`、`meta_class`、`decompile`、`resource`、`xref`、`search`，以及类/方法/字段 `rename`。

以下工具仅支持 GUI，会拒绝无头会话：`fetch_current_class`、`get_selected_text`、`get_method_by_name`、`get_main_application_classes_names`、`get_main_application_classes_code`、`get_package_tree`、`get_cache_stats`、`clear_cache`、`rename_package`、`rename_variable`、`add_comment`、`list_comments`、`debug_get_stack_frames`、`debug_get_threads`、`debug_get_variables`。调试器工具要求已连接并暂停，只读取已物化的 UI 数据，不是原子 VM 快照，不展开变量或求值。两种后端的 decompile 都接受 `method_signature`、`slim` 和 `with_line_numbers`（`SMALI` 不接受呈现选项）。输入相关支持以 `session_get` capabilities 为准。GUI `session_close` 只禁用 MCP 会话，不关闭 GUI 应用；`clear_cache=true` 仅适用于无头会话。[^protocol]

### 修改、持久化与同步

- `state.applied` 表示修改已应用到原生内存；`state.persisted` 单独确认持久化，不能由修改成功推断已落盘。
- **无头 rename 当前返回 `persisted=false`。** Worker 没有配置项目 owner store，忽略用户重命名映射，不自动保存/重载用户修改或私有 sidecar。关闭再建会话不会恢复这些修改；生成别名/缓存不等于用户项目持久化。
- GUI 修改归原生 JADX 项目所有。`ASK` 不强制保存或 Save As，修改可以仅在内存中；`ALWAYS` 仅在已有项目路径且原生保存成功时确认落盘。保存失败可能保留内存修改并返回 `persisted=false`，必须分别查看两个字段。
- **没有公开 `sync_project_data` 操作**，没有自动跨会话 SHA 同步，也没有配置无头 sidecar 持久化。本地 `ProjectDataPort.snapshot/importData` primitive 会校验 SHA-256 输入标识和 revision，但它们不是公开 MCP/REST 同步功能。
- GUI `clear_cache` 清理插件运行时响应/源码缓存，不清理宿主磁盘缓存。无头生成缓存删除通过 `session_close(clear_cache=true)` 请求，不能调用 GUI 专属的 `clear_cache`。[^persistence]

同一个 **Plugins → Jadx MCP** 窗口内可持续查看连接状态及**最近 5/10/30 秒业务请求数**，每秒刷新。收到不同 requestId 的命令即计数，不是成功次数，不计 poll/heartbeat/register；连接 generation/项目切换清零，空闲自然归零，不采集请求或操作详情。网络故障按 250ms 起步、30s 封顶退避自动重连；Master 重启遗失已建立 lease 时可重新注册恢复。主动 `session_close`/CLOSED 不自动复活，认证/lease 冲突仍停用，未知完成状态的编辑不重放。见[使用说明](docs/usage/gui-and-cache.md)。

每个 GUI 实例都有独立 session ID，须选择对应会话。2026-11-09 最新日志验收通过 **239 项测试**（protocol 13、analysis 71、app 61、GUI 94，失败/错误/跳过均为零）、两个重新构建的发布包和 **7 轮隔离发布包日志配置**。此前原生 GUI 验收另行通过真实 GUI 唯一菜单、**sample.jar 44/44** 及**同 APK GUI+HEADLESS 54/54**；日志变更后没有重跑这些场景。历史 APK 覆盖 launcher 一致性、编辑恢复与重连不重放。历史原生 rename 后立即读回通过 **sample.jar 96/96、APK 40/40**，修改与读回之间没有 sleep/重试：未复现不稳定，**不等于证明不存在竞态**。所有自建隔离进程均已回收。构建没有替换/重启当前 Master/GUI 或根目录运行 JAR。见[证据、哈希与限制](docs/testing/validation.md)、[复验命令](docs/testing/README.md)。

边界：未覆盖暂停设备调试的成功路径，也未穷尽 27 个工具的全部参数组合；原生保存重开及更广泛竞态/压力行为仍未证明。此前 MCP 工具包装器不暴露原始 `isError`；最终直接 HTTP 客户端检查了错误，但不消除该包装限制。GitHub Actions 仅静态检查，没有 PyYAML/完整 YAML 解析，也未运行远端 CI。未 commit/push。

### 真实 APK headless smoke 证据

2026-11-09 一份经授权复制的 APK 在发布包 HTTP 有界 smoke 中取得 **60 PASS、4 NO_OBJECT、0 FAIL**，覆盖元信息/manifest、search/xref、Java/方法/Smali、资源读取、内存改名、双 worker 隔离、deadline 和清理。Smali 资源 ID 成功解析到名称，未验证字符串值。该历史 HTTP 专项未执行 APK、未测 GUI/MCP/设备调试，也不是穷尽验证。见[证据与限制](docs/testing/validation.md)。

## 会话使用流程

无头分析时，调用 `session_create` 并传入 `type: "HEADLESS"` 和 `apk_path`；保存返回的 `result.session_id`，后续分析工具均传入此 ID。可用 `session_list` 查找 GUI 会话，用 `session_get` 查询会话信息，用 `session_close` 关闭会话。GUI 会话由插件注册，不能通过 `session_create` 创建。

推荐分析顺序：`meta_summary` → 定向 `search` → `xref` → `decompile`（优先 `target=METHOD`）。避免枚举大型 APK 的全部类。昂贵搜索使用 `timeout_ms` 与嵌套 `budget.max_scan` / `budget.max_decompile`。

## MCP 工具与能力

- 会话管理：`session_create`、`session_list`、`session_get`、`session_close`。
- 分析工具：`meta_summary`、`meta_manifest`、`meta_class`、`decompile`、`resource`、`xref`、`search`、`rename`。
- 加上前述 15 个 GUI 专属操作，共 **27 个操作**，REST/MCP 共用同一注册表；没有旧版兼容入口。
- 所有分析工具均要求 `session_id`。错误通过 `isError=true` 返回；反编译输出超过 80,000 字符时会标记截断。
- 枚举必须使用精确的**大写值**：搜索范围 `CLASS`、`STRING`、`METHOD_NAME`、`FIELD`、`CODE`、`COMMENT`；反编译目标 `JAVA`、`SMALI`、`METHOD`、`MAIN_ACTIVITY`；符号/组件/后端枚举同样大写。
- 类名支持 `p000.`、`defpackage.` 等 JADX 别名。
- 参数/结果采用 `snake_case`。未知字段、显式 JSON null、旧 `apk_id` 别名、小写枚举和 `count=0` 均被拒绝。分页嵌套为 `page: {offset: 0, count: 50}`，offset ≥ 0，count 为 **1..10000**。
- 整次调用 `timeout_ms` 包含排队（默认 120000，范围 1..600000）；可选 `project_epoch` 为乐观 fence，显式传入后不会被偷偷替换。
- `STRING` 默认 `string_mode=DEX_CONSTANTS`（仅 DEX 输入）；源码读取/非 DEX 输入须显式使用 `SOURCE_TEXT`。`budget.max_decompile` 和 `stats.decompiled` 计算包含缓存命中的唯一源码访问尝试次数，而非新反编译次数；零禁止所有源码读取，包括缓存。[^protocol]

REST 使用 `GET /health` 和 **`POST /api/<operation>`（全部 27 个操作）**，`session_list` 也是 POST（请求体 `{}`）。使用 `Content-Type: application/json`，请求体直接传参数对象，不传 protocol frame。成功返回 `{version: 1, operation: "…", result: …}`；REST 错误返回 `{error: {code, message, retryable}}`。GUI 通道 POST 路径为 `/gui/register`、`/gui/poll`、`/gui/complete`、`/gui/heartbeat`、`/gui/unregister`，不是 MCP 工具。旧 `/session/*` 路由已删除。[^runtime]

REST 示例（请替换真实路径、会话 ID 和符号）：

```bash
curl -H 'Content-Type: application/json' http://127.0.0.1:8650/api/session_create -d '{"type":"HEADLESS","apk_path":"/absolute/path/app.apk","max_heap":"4g"}'
curl -H 'Content-Type: application/json' http://127.0.0.1:8650/api/search -d '{"session_id":"SESSION_ID","scope":"STRING","term":"https://","string_mode":"DEX_CONSTANTS","page":{"offset":0,"count":50},"budget":{"max_scan":500,"max_decompile":0},"timeout_ms":120000}'
curl -H 'Content-Type: application/json' http://127.0.0.1:8650/api/decompile -d '{"session_id":"SESSION_ID","target":"METHOD","class_name":"com.example.Main","method_name":"run","method_signature":"()V"}'
curl -H 'Content-Type: application/json' http://127.0.0.1:8650/api/rename -d '{"session_id":"SESSION_ID","target_type":"CLASS","target":{"class_name":"com.example.Main"},"new_name":"EntryPoint"}'
```

新 Master 不读取旧部署变量 `JADX_WORKER_XMX`、`JADX_WORKER_TIMEOUT_SEC`、`JADX_DECOMPILE_TIMEOUT`。分别按用途使用 `--worker-heap`、会话 `max_heap` 和请求 `timeout_ms`。[^runtime]

## 开发

```bash
./gradlew test --rerun-tasks
./gradlew releaseArtifacts
```

代码注释、KDoc 和日志消息使用英文。README 同时维护英文和中文版本：[README.md](README.md)。

使用与测试主题说明见[文档索引](docs/README.md)。

唯一运行入口为 `com.yyang.jadxmcp.bootstrap.MasterMain`、`com.yyang.jadxmcp.worker.WorkerMain` 和 GUI ServiceLoader 的 `com.yyang.jadxmcp.runtime.JadxMcpGuiPlugin`。四模块分别是 `protocol`（DTO/codec/ports）、`analysis`（原生分析）、`app`（Master/Worker/传输）和 `gui-plugin`（借用宿主的适配器）。旧 `com.yyang.jadx_server` 实现、`runLegacy` 和兼容路径已删除，不再使用；开发任务 `:app:runRewrite` 也调用同一 Master。[^build]

[^build]: [发布任务](build.gradle.kts)、[应用构建](app/build.gradle.kts)、[GUI 构建](gui-plugin/build.gradle.kts)、[模块](settings.gradle.kts)、[发布工作流](.github/workflows/build.yml)、[GUI service descriptor](gui-plugin/src/main/resources/META-INF/services/jadx.api.plugins.JadxPlugin)。当前复验命令与验收边界见[测试说明](docs/testing/README.md)。
[^runtime]: [CLI 参数/运行时](app/src/main/kotlin/com/yyang/jadxmcp/bootstrap/MasterRuntime.kt)、[help 入口](app/src/main/kotlin/com/yyang/jadxmcp/bootstrap/MasterMain.kt)、[HTTP 路由/安全](app/src/main/kotlin/com/yyang/jadxmcp/api/MasterApi.kt)、[GUI 路径](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/GuiTransport.kt)、[Worker 传输](app/src/main/kotlin/com/yyang/jadxmcp/worker/WorkerServer.kt)、[Worker 默认值](app/src/main/kotlin/com/yyang/jadxmcp/process/WorkerProcess.kt)。CLI 复验：`java -jar build/release/jadx-mcp.jar --help`。
[^protocol]: [27 操作注册表](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/Operation.kt)、[校验/默认值](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/Contract.kt)、[分析 DTO](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/AnalysisOperations.kt)、[会话 DTO](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/SessionOperations.kt)、[重命名 DTO](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/EditOperations.kt)、[codec](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/ProtocolCodec.kt)、[公开 schema](app/src/main/kotlin/com/yyang/jadxmcp/api/CommandRegistry.kt)。复验可在有意启动的 Master 上查看 MCP `tools/list`；集成测试只启动有界的本地测试服务，不是持久部署。
[^persistence]: [无 store 的 Worker 组装](app/src/main/kotlin/com/yyang/jadxmcp/backend/AnalysisWorkerEndpointFactory.kt)、[内存项目 owner](analysis/src/main/kotlin/com/yyang/jadxmcp/analysis/project/JadxProjectService.kt)、[GUI 本地 snapshot/import](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/project/GuiProjectDataOwner.kt)、[原生项目保存确认](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/project/JadxGuiProjectAccess.kt)、[原生重命名/保存](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/project/JadxNativeRenameFacade.kt)。复验时单独检查 mutation `state`，不能以生成缓存存在代替持久化确认。
[^gui]: [插件生命周期/配置](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/runtime/JadxMcpGuiPlugin.kt)、[JADX 1.5.6 安装器源码](https://github.com/skylot/jadx/blob/v1.5.6/jadx-gui/src/main/java/jadx/gui/settings/ui/plugins/InstallPluginDialog.java)、[GUI 使用](docs/usage/gui-and-cache.md)、[最终隔离宿主证据](docs/testing/validation.md)。仅产物存在不等于交互安装验证通过。
[^cache]: [独占分配/清理](app/src/main/kotlin/com/yyang/jadxmcp/worker/OwnedWorkerCache.kt)、[Worker 启动/临时目录](app/src/main/kotlin/com/yyang/jadxmcp/process/WorkerBackend.kt)、[JADX 插件/输出/别名配置](app/src/main/kotlin/com/yyang/jadxmcp/backend/AnalysisWorkerEndpointFactory.kt)、[文件系统边界测试](app/src/test/kotlin/com/yyang/jadxmcp/worker/OwnedWorkerCacheTest.kt)、[真实子 JVM 隔离测试](app/src/test/kotlin/com/yyang/jadxmcp/backend/AnalysisWorkerIntegrationTest.kt)。原后缀证据：`git show 3f4833d` 包含 `File(parent, "${targetFile.name}_jadx_cache")`。

[^logging]: [等级与安全过滤](app/src/main/kotlin/com/yyang/jadxmcp/bootstrap/Logging.kt)、[内置 Logback](app/src/main/resources/logback.xml)、[MCP 回调](app/src/main/kotlin/com/yyang/jadxmcp/api/McpAdapter.kt)、[会话生命周期](app/src/main/kotlin/com/yyang/jadxmcp/session/SessionService.kt)、[发布包驱动](app/src/test/python/logging_acceptance.py)、[新验收证据](docs/testing/evidence/logging-validation-20261109.json)。

## License

Apache-2.0
