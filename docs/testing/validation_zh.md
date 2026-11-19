# 本地验收结果与局限

[English](validation.md)

本页记录的是针对当时工作树和构建产物进行的历史本地测试。测试数量、工具目录、路径和哈希均不能证明后续检出版本或发布版本的状态。

## 离线回归（2026-11-10）

当日测试的工作树通过 **320/320 个测试**：protocol 20、app 96、analysis 76、GUI 128；50 个 JUnit XML 文件中的失败、错误、跳过数均为零。离线串行 `test releaseArtifacts --rerun-tasks` 构建使用 JDK 21、一个 worker，禁用工具链下载，并设置 `-Pkotlin.incremental=false`。全部 23 个任务在 1 分 24 秒内成功执行；参见 `build/current-verification.log` 和各模块的 `build/test-results/test/`。真实 HTTP MCP 集成测试检查了当时的 **25 个工具**目录。本轮未测试原生 GUI 冒烟、已连接调试或现有服务，也未替换根目录运行时包及其日志。

```text
6ead657b450200880d3d5b756feba76071f377bdd84dc0d81f10db7a4c09c1f4  build/release/jadx-mcp.jar
c51af4bb8e2f126b793850a79c92e1fe3f628ba566aca566ce6620d216a2699f  build/release/jadx-mcp-gui-plugin.jar
```

## MCP 状态和精简工具输出（2026-11-10）

当时的工作树完成了[复现说明](README.md)中的离线串行命令，**317 个测试通过**（protocol 20、app 93、analysis 76、GUI 128），失败、错误、跳过数均为零；两个发布产物均已重新构建。12 个 `McpOutputTest` 投影测试、两个真实 HTTP `ServerStatusHttpTest` 用例和两个单调时钟 `ServerStatusWindowTest` 用例通过。新打包的 Master 仅在专用回环端口上启动：模拟 GUI 注册、两个 MCP 初始化会话、成功和失败的 tools/call、对两个客户端执行 DELETE 以及历史保留检查均通过。会话 ID 为八位数字，GUI 租约令牌仍为长令牌且保持不变。未使用原生 GUI/JADX 业务冒烟测试或现有服务。

观察到的诊断快照：

```text
server: running
tools/call: 10s=3 30s=3 60s=3
sessions: 1
  10153634 backend=GUI name=fixture.apk state=READY
clients: 2 (initialized)
  10153634 10s=2 30s=2 60s=2
  10153634-1 10s=1 30s=1 60s=1
```

MCP `session_list` 投影后的根数组文本为 80 个 UTF-8 字节，REST 封装为 318 字节（在这一代表性响应中减少 74.8%）。这只是结构和大小的样本，不保证普遍压缩率。错误文本仍是小型错误对象，SDK 的 `isError` 标志仍为 true。HTTP 集成断言验证 REST 仍返回有类型的封装。

打包验证程序回收了其所属进程。本轮的产物哈希和各模块确切数量记录在会话收尾说明中；未新增持久化证据文件。原生 GUI 交互、调试器成功路径以及对所有工具值的穷尽分析不在本轮范围内。

## 限定范围交付（2026-11-10）

完整离线串行构建通过 **291/291 个测试**（protocol 15、analysis 76、app 72、GUI 128），无失败、错误或跳过。两个可安装包位于 `build/release/`。最终发布包组合在两个周期内通过 **39/39** 项真实隔离 sample.jar CLASS/METHOD/FIELD 重命名、立即回读和恢复检查；暂存的修复版 GUI 也通过两个周期的 FIELD 专项测试 **13/13**。两者的原生类加载错误均为零。确定性原生监视器回归测试在修复前失败、修复后通过。

文档收尾独立读取了当时可用的 **46 个 JUnit XML 文件**，并重新计算验证两个发布包的哈希；该过程**没有**运行 Gradle、JADX 或冒烟脚本。记录的构建完成了 `test releaseArtifacts`，结果为 `BUILD SUCCESSFUL in 2m 6s`，且 **23/23 个任务均已执行**。包哈希、模块数量和精简冒烟结果保存在[只读交付快照](evidence/release-delivery-20261110.json)中。

```text
b0cb0c3f2b8dde740392101587d5aa5fe2dd98719ba0bc3a48f5be2552b4e9ec  build/release/jadx-mcp.jar
3130fe27a8e03a5ae9ae82d818c5ba46a96d571adcf0175e396ad98a7d7d7a2f  build/release/jadx-mcp-gui-plugin.jar
```

FIELD 专项 **13/13** 测试使用相同插件，但使用暂存 Master，而非上述最终 Master 哈希。**39/39 最终发布包**测试记录了上述两个哈希，且包含 FIELD 检查。更早的暂存 ALL 测试是独立证据，并非额外的最终发布包通过结果。临时冒烟根目录已移除；其小型归档结果从 `build/field-rename-evidence/` 读取。

原生失效处理遵循 JADX 1.5.6 的锁顺序：**JavaClass → ClassNode → ClassInfo**。`JavaClass.unload()` 不是同步方法；其深度卸载在短暂的 `ClassNode.unload()` 锁之外重新读取 Java 输入数据。该构建中的两个产品卸载路径均保护整个操作，防止与原生后台重新加载冲突，不使用 sleep 或编辑重试。捕获到的故障是并发深度卸载中的 `DataReader.readU2` 越界错误，而非测试程序断言或渲染源码缓存命中。

[记录的哈希、复现方法和局限](evidence/native-field-rename-20261110.json)。最小本地失败/通过记录及实际原生调用栈保留在 `build/field-rename-evidence/`；完整构建日志为 `build/field-rename-release.log`。所属冒烟进程及临时输入、配置、缓存均已清理。用户窗口、Preferences、原始 APK 及根目录运行时 JAR/日志未受影响。更早的双 GUI 身份 **62/62** 和 APK 重命名 **40/40** 结果**未在此组合上重跑**。这是限定范围的回归验收，不是穷尽的 GUI 编辑或调试器验收；未测试已连接且暂停的调试器/设备。

## 较早的完整 JDK HttpClient 验收（2026-11-10）

当时未提交的工作树使用完整 Temurin **JDK 21.0.12.1+1** 和实际 **JADX 1.5.6** 发布 JAR，在 macOS 上离线、串行强制重建。**257 个测试通过**（protocol **14**、analysis **71**、app **61**、GUI **111**），失败、错误、跳过数均为零。`test releaseArtifacts :gui-plugin:guiMenuSmoke --rerun-tasks` 在 900 秒外层超时、一个 worker 和禁用工具链自动下载的条件下执行。**23 个 HTTP 传输测试**覆盖全部五条路由/请求头、有类型错误、取消、上传/TLS/流式传输期限、拒绝重定向，以及声明长度、分块和未知长度响应的大小限制。

真实菜单重置/卸载/重新加载和无项目时的 IP/9010 Apply 检查通过。新建的顺序打包测试通过 **sample.jar 45/45**、**端口切换 10/10**、**保存失败 4/4** 和**同一授权 APK GUI+HEADLESS 58/58**。覆盖单窗口、计数器/过期、Applied!、安全显示终止错误、显式 Reconnect、Master 停止/启动、不确定编辑完成时的恰好一次执行与恢复、显式关闭后保持禁用，以及 APK 启动入口一致性。APK 类/方法测试对象从只读输入副本的实际元数据中发现。原始 APK 的哈希、大小、inode 和 mtime 均未改变。调试器仅检查了 DISCONNECTED 负面路径，没有测试已连接设备的成功路径。下述七种日志设置和原生重命名压力/回读矩阵未因本次变更重跑。

两个受测包均包含 JDK `HttpClient` 引用。记录的 SHA-256：

```text
a0f425c2fce312de6bcba9ec7f5f4a8f32bfd86b53371b6c363b451cc6e14799  build/release/jadx-mcp.jar
dc21d92abdb080ca1deb13755cb9ce95175ae0b34f0cd603c2d937e821465805  build/release/jadx-mcp-gui-plugin.jar
```

[本轮精简证据](evidence/httpclient-jdk21-validation-20261110.json)记录了命令、XML/日志哈希、场景数量和包/源码哈希。证据采集后，所有所属进程均已回收，确切的新生成临时副本、配置、日志和私有描述文件均已移除。现有服务/GUI、用户 Preferences、原生项目和根目录运行时 JAR/日志均未修改。未安装/下载、执行 Windows 测试、运行远程 CI 或提交/推送。

## 更早的验收

更早一轮最终验证由指定验证负责人于 **2026-11-09 针对未提交工作树**完成。文档任务独立读取了最终报告、小型原始证据和已有 JUnit XML，并重新计算了两个包的哈希；文档收尾未重新构建或运行应用。下述后续冒烟汇总则执行了新的检查。

## 日志验收（历史记录）

最终日志工作树于 **2026-11-09** 使用完整 Temurin JDK 21.0.12.1+1 离线、串行重建和测试，使用一个 Gradle worker 与 1200 秒外层超时。**239 个测试通过**，无失败、错误或跳过：protocol **13**、analysis **71**、app **61**、GUI **94**。`test releaseArtifacts --rerun-tasks` 的全部 **23** 个构建任务均已执行；这是新的完整测试套件结果，不是两个筛选测试或下述历史 233 测试结果。

- [构建命令、版本、模块/XML 数量和哈希](evidence/logging-build-validation-20261109.json)
- [打包控制台/运行时矩阵和脱敏消息](evidence/logging-validation-20261109.json)
- [复现命令](README.md#packaged-logging-acceptance)

打包驱动通过 **7/7 种配置**（默认 INFO、TRACE、DEBUG、INFO、WARN、ERROR、OFF）和 **5/5 个 CLI 用例**（两种 help 形式；无效等号形式、无效空格形式、空等号形式）。DEBUG 在实际 JVM 中使用空格形式；其余显式级别使用等号形式。每种配置均使用全新的项目本地目录、sample.jar 副本、随机回环端口和真实子分析 JVM。原始控制台行数依次为 **14/44/44/14/3/1/0**。INFO 包含生命周期但没有请求 DEBUG；WARN/ERROR 过滤更低级别；OFF 确实为空。

每种可见 INFO 的配置均恰好记录两次 MCP 初始化会话和两次退出：第一个通过真实 DELETE 关闭，第二个保持注册直至应用关闭。检查了 init/list/call、源码反编译、HEADLESS READY/create/close（重复 close）、GUI 通道注册/心跳/poll/选中文本完成/unregister（重复 unregister）、HTTP 启停、WARN 可恢复拒绝及 ERROR 创建失败。实际 worker argv 继承所选级别；其文件日志位于所属会话目录内，并遵守 INFO 可见性。所有采集的控制台/文件日志中均未出现令牌、临时绝对路径、载荷、源码摘录、注释及选中文本的哨兵内容。这是有限测试对象的覆盖，不是对所有依赖/错误/输入的信息流普遍性证明。

示例（ID 已脱敏）：

```text
INFO  MCP client connected sdkSessionId=<id> semantics=initialized-session
INFO  Session opened sessionId=<id> backend=HEADLESS state=READY
INFO  GUI registered sessionId=<id> backend=GUI state=READY
DEBUG Request completed requestId=<id> operation=decompile elapsedMs=<ms>
INFO  MCP client exited sdkSessionId=<id> semantics=transport-session-closed
INFO  HTTP server stopped
```

初始矩阵的 OFF 失败，因为 kotlin-logging 将默认工厂横幅直接打印到 stdout。隔离诊断重现了该问题；启动配置随后在加载 SDK 前禁用了该横幅，**整个强制测试套件/构建和完整矩阵均重新运行并通过**。较早尝试有记录，但不计入最终通过总数。现有 Kotlin 警告和 GUI 测试对象的 macOS 输入法诊断仍然存在；它们不是 Master Logback 事件。

历史发布 SHA-256（在该轮收尾时验证）：

```text
a0f425c2fce312de6bcba9ec7f5f4a8f32bfd86b53371b6c363b451cc6e14799  build/release/jadx-mcp.jar
3fa6f5c69bdb4ef103f46394bcd4fd8a1ead9a16ca5d43b312d9f56a31106272  build/release/jadx-mcp-gui-plugin.jar
```

局限：依赖日志**即使在 TRACE/DEBUG 下仍为 WARN+**，以抑制 SDK/库载荷诊断；DEBUG 公开应用请求/后端耗时，不包括每个库的 DEBUG。应用失败仅输出类型/代码，不输出异常消息或调用栈；依赖的 WARN/ERROR 可能包含其自身调用栈，分享前需要审查。SDK 0.14 **默认不会使静默 MCP 会话因空闲而过期**：可观察传输/会话关闭，但不保证即时发现 TCP 或半开连接丢失。GUI 租约超时/保留是独立的生命周期。打包 GUI 通道使用隔离 HTTP 测试对象，而非原生 GUI 窗口。之前的实时 GUI/APK/重命名/调试器矩阵**未针对日志变更重跑**。所有所属 Master/worker 进程组均已回收，确切生成的临时副本/日志/配置已移除；根目录运行时 JAR 哈希保持不变。未替换、重启或清理现有 Master/GUI 服务及其打开的根目录日志。未安装依赖、运行远程 CI、提交或推送。

## 五文件冒烟重新验证

2026-11-09 的冒烟验证使用具有下列 SHA-256 的现有发布包、现有 GUI 宿主类、JDK 21 和 JADX 1.5.6 执行了五个支持文件；没有重建发布包。同时发生的后续 app 日志源码变更**不包含在那些较早的打包运行时结论中**；新的日志包已在上文单独验证。

| 新执行的范围 | 结果 |
|---|---|
| 独立 `gui_live_status.py`，sample.jar 副本 | **42/42** |
| `gui_acceptance.py`，sample.jar 副本 | **45/45** |
| `gui_acceptance.py`，授权 APK 副本 | **55/55**，包括 GUI/HEADLESS 启动入口一致性 |
| `apk_http_smoke.py` prepare/run/publish | **60 PASS、4 NO_OBJECT、0 FAIL**；相对路径发布成功 |
| 两个现有正式过期位置测试 | **2 个测试**，失败/错误/跳过均为零；仅执行两个测试任务，所需编译为 UP-TO-DATE |

[新增最小 JSON](evidence/smoke-five-file-validation-20261109.json)记录了命令、哈希、检查、先前失败尝试及清理。sample 验收通过发生在该轮最终前置条件/清理保护之前；独立 sample 和 APK 验收通过使用了该轮最终驱动。较早一次 GUI 尝试失败，因为其读取器在卸载后复用了旧的可见窗口输出；修正后的读取器只使用当次命令的输出。第一次 HTTP 发布尝试暴露了相对路径打印错误，修复后重跑成功。这些是驱动问题，不是产品故障，失败尝试未计入通过数。

新 GUI 运行覆盖了 IP/Port Apply 验证、没有 Cancel、精确的 Applied!、单窗口/计时器归属、计数器、实际 Master 重启、完成 ACK 丢失而不重放、注释恢复、显式关闭及 GUI/HEADLESS 链路。所有所属隔离进程均已回收。APK 保持只读；仅分析输入副本。四项 HTTP NO_OBJECT 检查表示不可用的资源表示，不是资源正向通过；观察到的 Smali ID 解析为名称，而非字符串值。

正式的 analysis 和 GUI 方法身份测试确定性地注入过期原生位置。本轮未重复私有 Android 构造器/生命周期修复前后矩阵。历史实时状态断言中有关多个菜单观察者的标签并不能证明两个监听器均已移除；共享驱动检查的是单个所属观察者。

下述历史完整套件/重命名数量没有在本轮重跑或叠加。本轮不声称覆盖调试器正向路径/设备、原生保存/重新打开、压力/竞态、穷尽输入、日志源码验收或远程 CI。

## 较早的原生 GUI 最终验收结果

| 范围 | 最终结果 |
|---|---|
| 全模块测试 | **233** 个，失败/错误/跳过均为零：protocol **13**、analysis **71**、app **55**、GUI **94** |
| 打包/help/入口描述符 | 两个 `build/release/` 包及 CLI help 均通过 |
| 真实菜单重置/加载/重新加载 | 一个 **Jadx MCP** 条目，无重复 |
| sample.jar GUI/MCP/HEADLESS | **44/44** 项断言 |
| 同一 APK GUI+HEADLESS | **54/54** 项断言：启动入口/manifest 一致、编辑恢复、不重放重连 |
| 立即原生重命名/回读 | sample.jar **96/96**、APK **40/40**，包括恢复和指定方法回读 |
| GUI 控件 | **没有 Cancel**、精确的 **Applied!** 成功文本、一个活动窗口 |
| 所属隔离进程 | 全部回收，包括被替代的尝试 |

重命名检查在修改和立即读取之间没有 sleep 或重试。有限次数的运行**没有重现先前的不稳定性；不能证明不存在竞态**。等待就绪不是重命名回读重试。

本轮的本地支持报告、JUnit 数量、结果记录、构建标记及工作流检查不公开。可分享的结果和局限已在此概述；不提供本地材料链接。

该较早原生 GUI 轮次的历史发布 SHA-256（Master 已被替代）：

```text
cb1e8106b91165a7ae0a65dd47cbbba01db9400ea204b7becf87dbe2c40b99c8  build/release/jadx-mcp.jar
3fa6f5c69bdb4ef103f46394bcd4fd8a1ead9a16ca5d43b312d9f56a31106272  build/release/jadx-mcp-gui-plugin.jar
```

GitHub Actions **仅接受了静态文本检查**。没有可用/已安装的 PyYAML 或其他完整 YAML 解析器，不声称完成完整 YAML 语义验证，且**未运行远程 GitHub Actions**。**未提交或推送。**

## 更早记录的轮次

这些数量属于更早的工作树，不是额外的最终测试运行。

| 范围 | 记录结果 | 证据 / 局限 |
|---|---|---|
| 缓存后缀更新前的四模块重建 | 203 个测试，0 失败/错误/跳过（12/67/51/73） | [历史缓存记录](evidence/cache-layout-validation-20261109.json)，并非后续测试套件。受保护清理修正前，初始增量尝试有 29 个 app 失败。 |
| 缓存后缀和 worker 日志限制 | 52 个 app 测试及 app 打包/help | 该轮未重跑其他模块。记录的命名为完整文件名 + `.jadx_mcp_cache`。 |
| 打包 APK HTTP | 60 PASS、4 NO_OBJECT、0 FAIL | [HTTP 摘要](evidence/apk-headless-http-smoke-20261109.json)；真实 Smali ID→名称，并非字符串 ID→值；仅 HTTP、一个 APK，无执行/设备调试。 |
| 现有 GUI/MCP 只读测试 | 33 次尝试：21 次成功、7 次预期拒绝、5 次环境未就绪 | [GUI 摘要](evidence/mcp-gui-smoke-20261109.json)；一次拒绝发生在客户端，32 次到达服务端；该包装层无法看到原始 isError。 |
| 系统矩阵 | 按 HEADLESS/GUI 分类的 26 项操作；生命周期/编辑/超时路径 | [矩阵](evidence/system-test-mcp-20261109.json)；覆盖范围不是每个后端 26 项正向端到端通过。 |
| 精确方法源码身份 | 212 个模块测试；真实缺陷修复前后复现 | [方法摘要](evidence/method-source-identity-fix-20261109.json)；精确重载/构造器/别名查询；原生 GUI 重命名/编辑器刷新仍为异步。 |
| 实时状态与重连 | 221 个测试（12/68/52/89），34 项真实集成断言 | [实时状态摘要](evidence/gui-live-status-validation-20261109.json)；实际 Master 重启、完成 ACK 丢失而不重放编辑、计数器及卸载清理。 |
| 统一菜单 | 91 个 GUI 测试，13 项隔离检查 | [菜单摘要](evidence/gui-unified-menu-20261109.json)；不是新一轮四模块通过。 |
| IP/端口字段 | 94 个 GUI 测试，16 项隔离检查 | [设置摘要](evidence/gui-ip-port-20261109.json)；早于移除 Cancel 和采用 Applied! 文案。 |

有限的[已退役原始运行摘要](evidence/retired-raw-evidence-20261109.json)保留了 17 个原始记录哈希、断言结果及调用次数，包括失败尝试。[构建日志元数据](evidence/build-log-metadata-20261109.json)保留了原始日志哈希和完成标记，不包含完整日志内容。

## 各轮已验证的变更和范围

- 单一设置窗口及 **Applied!** 文案通过该轮最终隔离检查；不要用较早的 94 测试/16 检查记录替代该证据。
- `MAIN_ACTIVITY` 在那些构建中使用 manifest 模型/解析器；记录的测试和同一 APK GUI/HEADLESS 启动入口检查通过。这不覆盖每种 manifest、别名或资源值标志组合。
- `build/release/` 是那些轮次中根发布任务和 CI 使用的分发目录。运行中的服务使用 JAR 时不要移动它们。
- 上述场景和正式重命名测试结果是有限的运行时证据。不声称发布上传、远程 CI、提交或推送。

## 完整性声明仍不涵盖的内容

- 大型 APK/类/资源的穷尽验证及 APK/设备执行。
- 已连接且暂停的设备上的调试器正向路径。
- 当时记录的全部 25 个 MCP 工具的每一种参数组合。直接 HTTP 集成检查覆盖实际 MCP `tools/list`、原始 `isError`，以及 SDK 对隐藏/未知名称返回的 `Tool <name> not found`；较早基于包装层的历史冒烟记录仍有其已说明的可见性限制。
- 原生分析进行中关闭原生 GUI；期限不能可靠地取消所有原生 JADX 工作。
- 原生 ASK/ALWAYS 保存/重新打开，以及压力下的异步重命名/编辑器刷新。应用编辑和持久化保存是两个独立结果。
- 超出可移植身份/不跟随链接检查范围的恶意同用户文件系统修改竞态防护。

## 不依赖私有临时目录的复现方法

使用[现行命令和前置条件](README.md)。历史完整套件命令是强制串行 Gradle 测试加两个 Shadow 任务，在 macOS 上使用 Temurin JDK 21.0.12.1+1-LTS。打包时使用现行 `releaseArtifacts` 任务。后续源码变化应重新记录当时的来源和结果，而不是沿用这些数量。

历史证据经过精简；最终小型结果记录及其哈希得以保留。不公开包含源码的完整响应或私有路径。输入不可变性/清理断言不授权删除任何用户的原始 APK 或原生 `.jadx` 项目。生成的隔离副本只在归档和检查打开文件/进程引用后才被移除。
