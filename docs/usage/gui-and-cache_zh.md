# GUI 连接与输入缓存使用指南

中文 | [English](gui-and-cache.md)

## GUI 连接

使用**不含内置 JRE 的 JADX 1.5.6 ZIP**，并另行安装**包含运行环境的完整 JDK 21**。
启动 GUI 和 Server 时均应明确使用该 JDK 的 `bin/java` 可执行文件；仅将其加入 PATH
并不能选定 GUI 运行环境。参见 [Windows shell 局部启动示例](../../README.md#install-the-jadx-gui-plugin)。
通过 **Preferences → Plugins → Install plugin** 安装
`build/release/jadx-mcp-gui-plugin.jar`，然后重启或重新加载插件。
先启动 Master；默认地址为 `http://127.0.0.1:8650`。

Server 默认**仅监听回环地址**：只修改端口并不能允许远程或局域网连接。在可信局域网中，
可明确使用以下命令启动：

```bash
java -jar build/release/jadx-mcp.jar --host=0.0.0.0 --port=9010 --log-level=INFO
```

`0.0.0.0` 是 IPv4 监听地址，不是客户端连接目标。在此示例中，Server 所在机器上的
GUI 填写 **IP** `127.0.0.1`、**Port** `9010`；其他机器上的 GUI 填写 Server 实际
可达的 IP 和端口 `9010`。MCP 客户端使用 `http://<server-ip>:9010/mcp`。防火墙须
允许可信客户端入站 TCP 9010；NAT/公网端口转发是独立条件，仅绑定地址并不保证公网可达。
服务没有通用认证或内置 TLS：应使用受控防火墙规则下的可信网络或受保护的隧道，切勿直接
暴露到公网。`GET /status` 返回包含会话、已初始化客户端别名及滚动 10/30/60 秒业务工具
调用总数的纯文本诊断；它不能证明 MCP、工具和 GUI 均已就绪。`/mcp` 是协议端点而非网页；
请使用 MCP 客户端初始化并列出工具。参见[启动与状态检查](../../README.md#install-and-run-the-master-service)。[^runtime]

**Plugins → Jadx MCP** 打开一个非模态窗口，显示连接状态、5/10/30 秒请求计数，
并提供独立的数字 **IP** 和 **Port** 字段。IPv6 地址不带方括号，端口范围为 1–65535。
不要输入协议前缀、主机名、路径或完整 URL。**Apply** 会保存设置并在后台重新连接，
不关闭窗口，成功时显示 **Applied!**。[^gui]

`Applied!` 确认的是本地设置已保存且 bridge 已启动，**不表示**远程连接已建立。
即使有效端口暂时没有服务，也会应用成功并进入 `RECONNECTING`；之后在该地址启动 Master
即可自动恢复。保存失败会指出阶段和异常类型，并保持原连接不变；保存后启动失败会单独报告。
界面不会显示令牌或原始异常消息。参见[端口切换验证](../testing/evidence/gui-port-switch-20261110.json)。

### 运行环境与离线配置

GUI 传输使用上述完整 JDK 21 中的 `java.net.http.HttpClient`。

可以在**打开项目之前**应用设置。处于 `DISABLED: Waiting for project` 时，Apply 只保存
地址，显示 `Applied!` 并继续等待；项目成功绑定后才创建 HTTP 传输。在远程 Windows GUI 中，
`127.0.0.1` 指的是 Windows 电脑自身，而不是运行 Master 的 Mac。目标地址错误会影响连接，
但不影响保存有效的本地设置。参见[无项目配置验证](../testing/evidence/gui-no-project-settings-20261110.json)。

计数按收到的唯一业务命令 ID 统计，而非成功完成的次数；注册、轮询和心跳流量不计入。
计数每秒刷新，并在连接代次或项目变化时重置。丢失的编辑完成结果不会被重放。暂时性网络故障
会自动退避重试，Master 重启后通过重新注册恢复。不可重试的协议错误会以安全的阶段/原因
显示，并使 bridge 保持 `DISABLED`；在同一窗口中选择 **Reconnect** 可清除该终止错误，
并为当前绑定的项目尝试重新注册。这是新的租约：结果不确定的命令或编辑绝不会重放。
没有活动项目或 bridge 不处于终止错误状态时，该操作不可用；未绑定项目时，状态保持
`Waiting for project`，且不发送 HTTP 请求。显式关闭的会话不会自动重新出现。仅构建新
产物不会更新已有安装。[^gui]

显式恢复路径曾在隔离的 JADX GUI/Master 运行中验证：注入带类型的 `INVALID_ARGUMENT`
响应，使用 **Reconnect**，并确认 GUI 重新注册。同一次运行还停止并重启隔离的 Master，
验证了网络中断后的自动恢复。隔离的无项目 MainWindow 冒烟测试验证了 `Waiting for project`
以及禁用的 **Reconnect** 按钮。证据和产物哈希见
[GUI 重连验证](../testing/evidence/gui-reconnect-20261110.json)。

上述端口、无项目和重连记录均标明了各自测试的产物。2026 年 11 月 10 日的完整 JDK 验收检查
及包哈希见：[历史 JDK 21 验证](../testing/evidence/httpclient-jdk21-validation-20261110.json)。

Master 没有通用认证或 TLS。请使用可信输入和客户端，并使用回环地址或受保护的隧道；
不要将其端口直接暴露到互联网。参见 [README 安全提示](../../README.md)。

## 日志与连接语义

启动 Master 时可指定 `--log-level=INFO`（默认）或 `--log-level DEBUG`。仅接受精确的
大写值 `TRACE/DEBUG/INFO/WARN/ERROR/OFF`；空值或未知值会导致启动失败。INFO 记录组件和
会话生命周期，而非每次请求、轮询或心跳。DEBUG/TRACE 增加应用请求/后端 ID、操作和耗时
（请求耗时包含排队时间）。WARN 记录可恢复的拒绝或降级；ERROR 记录故障。OFF 禁用日志。

内置 Logback 的根、控制台和文件阈值在运行时构造前遵循所选级别；显式配置的日志器级别会被
替换，worker 通过其 JVM 参数继承该级别。`logs/server.log` 按大小/日期滚动；
`logs/error.log` 还要求 ERROR 级别。worker 文件保留在其所属的
`<input>.jadx_mcp_cache/session-<unique>/logs/` 中，即使正常关闭后也不删除。
Master 的此选项不会重新配置正在运行的 GUI 宿主。

**依赖库日志（JADX、MCP SDK、Ktor、Netty 和其他非项目日志器）即使在 DEBUG/TRACE 下
也仅记录 WARN 及以上级别。**这是有意以库的 DEBUG 细节换取载荷安全：SDK 的 TRACE 日志
包含工具参数。应用故障即使在 DEBUG 下也只记录代码/类型，不记录异常消息或堆栈；依赖库的
WARN/ERROR 可能包含其自身的堆栈，因此分享摘录前应进行审查。应用日志不包含令牌、载荷、
源码、选区、注释内容或输入路径。kotlin-logging 的 stdout 启动横幅已禁用；CLI 帮助/错误
和 JVM/原生 stderr 诊断不属于 Logback 的控制范围。

MCP 日志遵循 SDK 会话初始化和传输关闭事件，而不是 HTTP 请求次数。`sdkSessionId` 既不是
认证信息，也不一定是 HTTP 会话头。重复的初始化/关闭回调不会产生重复的生命周期消息。
DELETE 会结束会话；应用关闭时会关闭剩余会话。**没有 DELETE 的静默客户端/TCP 半关闭默认
没有 SDK 空闲超时；不保证立即检测到网络断开。**GUI 反向通道注册单独记录为
`GUI registered/reconnected/disconnected/closed`。其租约超时/保留期不同于 MCP 客户端
生命周期。无头模式仅在加载成功后才记录 READY；创建失败或中止不会记录为已打开的会话。
关闭操作具有幂等性；过期和关闭应用使用不同的关闭原因。[^logging]

## 生成数据与用户项目

对于 `/work/demo.apk`，无头模式生成的数据属于
`/work/demo.apk.jadx_mcp_cache/session-<unique>/`。每个会话都会获得一个新的子目录。
JVM 临时文件、插件配置/缓存/临时文件、输出和生成的别名均保留在该子目录中。相对路径会被
规范化，输入符号链接会解析到真实目标。不可写或不安全的位置会明确报错。[^cache]

正常关闭会保留生成数据。`session_close(clear_cache=true)` 会停止 worker，检查带标记
子目录的身份后，仅删除该子目录。输入文件、其他会话、共享根目录及**所有原生 `.jadx`
项目**都会保留。不要手动删除活动会话目录。删除输入文件不会自动删除相邻的生成数据。[^cache]

此所有权规则不涵盖原生 GUI 宿主的缓存/项目。GUI 响应缓存在内存中。无头模式的重命名仅在
内存中生效（`persisted=false`）；GUI 保存遵循原生 ASK/ALWAYS 所有权规则。请分别检查
`state.applied` 和 `state.persisted`。

可移植的带保护路径删除不能以原子方式防御恶意的同用户进程并发修改文件系统。原生 GUI 在
分析进行中关闭，以及原生保存/重新打开，仍需专门的宿主验证。

[^gui]: [插件](../../gui-plugin/src/main/kotlin/com/yyang/jadxmcp/runtime/JadxMcpGuiPlugin.kt)、[窗口](../../gui-plugin/src/main/kotlin/com/yyang/jadxmcp/runtime/GuiBridgeStatusWindow.kt)、[设置](../../gui-plugin/src/main/kotlin/com/yyang/jadxmcp/runtime/MasterConnectionSettings.kt)、[最终证据与限制](../testing/validation.md)。
[^runtime]: [CLI 默认值与解析](../../app/src/main/kotlin/com/yyang/jadxmcp/bootstrap/MasterRuntime.kt)、[帮助入口](../../app/src/main/kotlin/com/yyang/jadxmcp/bootstrap/MasterMain.kt)、[HTTP 状态/MCP 路由与安全](../../app/src/main/kotlin/com/yyang/jadxmcp/api/MasterApi.kt)。
[^cache]: [所属缓存](../../app/src/main/kotlin/com/yyang/jadxmcp/worker/OwnedWorkerCache.kt)、[边界测试](../../app/src/test/kotlin/com/yyang/jadxmcp/worker/OwnedWorkerCacheTest.kt)、[真实 worker 测试](../../app/src/test/kotlin/com/yyang/jadxmcp/backend/AnalysisWorkerIntegrationTest.kt)。
[^logging]: [日志策略](../../app/src/main/kotlin/com/yyang/jadxmcp/bootstrap/Logging.kt)、[生命周期](../../app/src/main/kotlin/com/yyang/jadxmcp/session/SessionService.kt)、[SDK 回调](../../app/src/main/kotlin/com/yyang/jadxmcp/api/McpAdapter.kt)、[历史打包验收](../testing/validation.md#logging-acceptance-historical)。
