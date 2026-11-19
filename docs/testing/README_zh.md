# 测试与隔离冒烟运行

[English](README.md)

在仓库根目录使用完整 JDK 21 运行。每次只运行一个构建；在 agent 环境中，应为长时间运行的命令设置外层超时并放到后台执行。使用不含 JRE 的 JADX 1.5.6 ZIP，并明确使用该 JDK 启动 GUI 和 Server（参见 [Windows 示例](../../README_zh.md#安装-jadx-gui-插件)）。完整 JDK 下的 HttpClient 验收与注明日期的结果在[验证记录](validation.md)中分别说明。

```bash
./gradlew --no-daemon --no-parallel --max-workers=1 test --rerun-tasks releaseArtifacts
./gradlew --offline --no-daemon --no-parallel --max-workers=1 \
  :protocol:test :app:test :analysis:test :gui-plugin:test releaseArtifacts
./gradlew --offline --no-daemon --no-parallel --max-workers=1 \
  -Dorg.gradle.java.installations.auto-download=false -Pkotlin.incremental=false \
  test releaseArtifacts --rerun-tasks
java -jar build/release/jadx-mcp.jar --help
unzip -p build/release/jadx-mcp.jar META-INF/MANIFEST.MF
unzip -p build/release/jadx-mcp-gui-plugin.jar META-INF/services/jadx.api.plugins.JadxPlugin
```

仅在所有依赖均已存在时使用 `--offline`。记录修订版本、工作树状态、JDK、命令、退出状态、各模块测试数量、产物哈希及明确排除的项目。`--help`、打包成功和历史通过记录不能证明当前运行时正确。不要自动安装前置依赖。

## 已打包程序的日志验收

完成上述串行构建后（依赖已缓存时使用 `--offline`），在 macOS/Linux 上使用完整 JDK 21 和已安装的 Python 3.12 运行（需有 `ps`/`pgrep`）：

```bash
python3.12 -B app/src/test/python/logging_acceptance.py
```

设置 600 秒外层超时并在后台运行。驱动会创建项目内唯一的 `build/logging-smoke-<unique>/`，使用随机环回端口和复制的 sample.jar，隔离 JVM 主目录、临时目录和 preferences；清理时只回收自身所属的进程组，然后删除该临时目录。默认将经过脱敏的精简 JSON 摘要写入新的 `build/smoke/logging-<UUID>.json`。可用 `--output` 指定仓库内另一个新文件；不会覆盖已有文件。另有一份[历史摘要](evidence/logging-validation-20261109.json)。驱动绝不会连接已有的 Master/GUI，也不会替换仓库根目录下的运行时包。

七种配置分别是无选项（默认 INFO）、TRACE、DEBUG（空格形式）、INFO/WARN/ERROR/OFF（等号形式），另有五个帮助/无效 CLI 用例。对实际打包程序的检查涵盖 MCP 初始化/列表/调用/DELETE 和关闭时结束的第二个会话、无头创建/摘要/反编译/幂等关闭、隔离 GUI 通道的注册/心跳/轮询/选中文本完成/幂等注销、WARN/ERROR 可见性、Worker JVM 参数/隔离日志，以及确认不包含令牌、路径、载荷、源码、选中内容和注释内容。INFO 使用真实控制台；OFF 必须为空。该 GUI 通道测试夹具**不是原生 GUI 窗口验收运行**；按照文档说明的 WARN+ 安全策略，有意抑制依赖的 DEBUG 日志。不会人为设定静默断连超时。[历史日志验收结果](validation.md#logging-acceptance-historical)。

## 基于测试夹具的 GUI/MCP

将 `JAVA_HOME` 设为完整 JDK 21，将 `JADX_GUI_DISTRIBUTION` 设为已有的 JADX 1.5.6 `jadx-1.5.6-all.jar`。可选的宿主冒烟源集与普通无头测试分开：[^build]

```bash
./gradlew --offline --no-daemon --no-parallel --max-workers=1 \
  -PjadxGuiDistribution="$JADX_GUI_DISTRIBUTION" :gui-plugin:guiMenuSmoke
python3.12 -B scripts/smoke/gui_live_status.py
python3.12 -B scripts/smoke/gui_acceptance.py
python3.12 -B gui-plugin/src/guiSmoke/python/settings_port_switch.py
python3.12 -B gui-plugin/src/guiSmoke/python/settings_port_switch.py --fail-preferences
```

`guiMenuSmoke` 编译宿主源集，使用内存中的 Preferences 工厂运行真实菜单/重置/卸载以及无项目时 Apply 的检查。为该任务将 `JADX_CONFIG_DIR` 和 `JADX_CACHE_DIR` 设为工作区内新建的目录。端口切换驱动使用 `build/smoke/` 下新建的子目录，检查离线 Apply/恢复、重复替换端点，或注入保存失败且原桥接连接必须保持的情况。每个 Python 场景设置 330 秒外层超时；按顺序运行，不要与构建同时运行。普通 HTTP 单元测试套件使用 JDK `HttpClient` 检查全部五条路由的 JSON/Accept/Content-Length、类型化错误、取消、上传/TLS/流式处理期限、重定向和响应大小限制。

Master 通过 `GET /status` 提供有界的纯文本诊断信息；`/health` 不是路由。`McpOutputTest` 在不启动 JADX 的情况下测试每个工具的输出投影；`ServerStatusHttpTest` 驱动真实 HTTP MCP 初始化、业务工具调用和失败工具调用、两个客户端会话及 DELETE，检查归属和全局历史；`ServerStatusWindowTest` 检查基于单调时钟的 10/30/60 秒过期行为。MCP 文本有意区别于未改变的 REST/传输信封。冒烟测试的 MCP 辅助程序使用投影后的工具值；REST 辅助程序继续使用带版本的 REST 信封。

`scripts/smoke/` 包含以下辅助文件：

| 文件 | 职责 |
|---|---|
| `apk_http_smoke.py` | 准备 APK 副本，运行已打包程序的 HTTP 检查，发布脱敏摘要 |
| `apk-e2e-logback.xml` | 为该隔离 HTTP Master 提供仅输出到控制台的日志配置 |
| `mcp_helper.py` | 共享的有界 MCP 客户端、新建工作区、所属子进程清理 |
| `gui_live_status.py` | IP/Port Apply、无 Cancel、Applied!、单窗口、计数器、重启/不重放、编辑恢复以及 GUI/HEADLESS 核心链路 |
| `gui_acceptance.py` | 调用共享驱动，包含调试器否定检查及可选 APK 启动入口检查 |

使用已有的发布 JAR 和已编译宿主类；这些 Python 驱动绝不下载依赖。GUI 驱动复制仓库中的 `sample.jar`，隔离 preferences/config/cache，只管理其子 JVM 和环回代理，绝不管理已有服务或窗口。场景期限为 240 秒；为有界清理预留时间，设置 330 秒外层超时。输出新建于 `build/smoke/` 下，不使用旧的临时前置文件。驱动使用 `build/release/` 中的两个包。`--help` 可在不启动 JVM 的情况下说明启动要求。独立的实时状态入口和完整验收入口均已在[五文件重新验证](validation.md#five-file-smoke-revalidation)中实际重新运行。

对于同一 APK 的 GUI+HEADLESS 验收场景，将 `JADX_SMOKE_INPUT` 设为已授权的 APK，将 `JADX_GUI_FIXTURE` 设为本地 JSON 描述文件，其中包含 `class_name`、`method_name`、`method_signature`（JVM 描述符）。运行 `gui_acceptance.py`；它会复制输入，并额外检查 manifest、summary、MAIN_ACTIVITY 和两个后端的启动入口是否一致。不设置这两个变量则使用仓库中的 sample.jar 场景。私有夹具描述文件不随项目分发。

如果已授权 APK 尚无描述文件，先将其复制到新工作区，通过公开的 API/MCP 创建隔离的 HEADLESS 会话，并获取该类的 `meta_summary.main_activity` 和 `meta_class.methods`。选择一个非构造方法；将其 `name` 和精确的 `descriptor` 分别作为 `method_name`/`method_signature`、类名作为 `class_name` 写入工作区内的临时 JSON。关闭该发现会话，对副本运行验收，记录精简结果后移除私有描述文件和副本。不要发布私有符号，也不要让发现过程指向原始 APK/原生项目。最近一次包含 58 项检查的同 APK 验收使用了此元数据驱动流程。

## 原生重命名与即时回读

正式的[重命名驱动](../../gui-plugin/src/guiSmoke/python/rename_readback.py)使用原生 GUI 编辑事件并立即检查源码，包括按名称查询方法和恢复操作。修改与读取之间不插入 sleep/重试；有界的就绪等待另行处理。

```bash
python3.12 -B gui-plugin/src/guiSmoke/python/rename_readback.py \
  --java-home "$JAVA_HOME" --distribution "$JADX_GUI_DISTRIBUTION"
```

默认夹具是 sample.jar。对于已授权的 APK 副本，使用自己的夹具提供 `--input`、`--class-name`、`--method-name`、`--method-descriptor` 和相应字段参数；空的 `--field-name` 会跳过该场景。不要在公开示例中嵌入私有 APK 路径或符号。最终结果为 sample.jar 96/96、有界 APK 配置 40/40，**并不能证明不存在竞态**。

## 已授权 APK 的 HTTP 冒烟测试

将 `JADX_SMOKE_INPUT` 设为明确授权的输入 APK。驱动会计算输入的哈希并复制它；不会执行/安装 APK，也不会更改其原始原生项目。所有分析均在生成的副本上运行：

```bash
python3.12 -B scripts/smoke/apk_http_smoke.py prepare
# 将 RUN 替换为 prepare 输出的相对于工作区的目录。
python3.12 -B scripts/smoke/apk_http_smoke.py run build/smoke/RUN
python3.12 -B scripts/smoke/apk_http_smoke.py publish build/smoke/RUN
```

运行期限为 540 秒；外层超时至少设置为 600 秒。`publish` 将有大小限制的 `summary.json` 写入该次运行目录，而不是历史证据目录。发布前应审核并脱敏。原始本地响应可能包含源码，不得提交。此场景依赖 APK；不可用的资源表示不能算作通过的正向测试。

## 确定性方法身份回归测试

不需要私有 APK 或 JSON 描述文件。现有正式测试直接注入过时的原生方法位置：analysis 检查精确的重载和带行号源码；GUI 检查借用的原生工作区、重载/内部类身份、歧义、别名及缓存命中：

```bash
./gradlew --offline --no-daemon --no-parallel --max-workers=1 \
  :analysis:test --rerun --tests 'com.yyang.jadxmcp.analysis.source.JadxSourceServiceTest.methodSliceUsesCurrentCodeMetadataNotMutableNativeDefinitionPosition' \
  :gui-plugin:test --rerun --tests 'com.yyang.jadxmcp.ui.GuiQueryAdapterTest.exactMethodIdentitySurvivesStalePositionsAliasesInnerClassesAndCacheHits'
```

审核/归档某次运行的少量证据后，待其所属的所有 JVM 和代理停止，仅移除该次运行所打印目录中的生成副本/config/cache/日志。绝不清理其他运行、原始输入/原生项目、用户 preferences、已有服务、仓库根目录运行时 JAR 或正在打开的日志。正式驱动不依赖 `.agent/tmp/`。

## 证据策略

[最终和历史结果](validation.md)链接至经过脱敏的精简 JSON：检查项、数量、耗时、错误码和哈希。完整反编译响应、凭据、私有绝对路径、APK 副本及重复的构建日志均不是公开证据。摘要哈希用于识别记录的输入和结果，不能重建原始记录。这**不是完整的传输数据归档**。

[^build]: [GUI 冒烟任务](../../gui-plugin/build.gradle.kts)、[发布任务](../../build.gradle.kts)、[CI](../../.github/workflows/build.yml)。
