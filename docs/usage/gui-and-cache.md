# GUI connection and input-cache usage

## GUI connection

Use the **JADX 1.5.6 ZIP without an embedded JRE** and install a separate **full
JDK 21, including its runtime**. Launch both GUI and Server using that JDK's
explicit `bin/java` executable; PATH installation alone does not select the GUI
runtime. See the [Windows shell-local launch example](../../README.md#install-the-jadx-gui-plugin).
Install `build/release/jadx-mcp-gui-plugin.jar` through
**Preferences → Plugins → Install plugin**, then restart/reload the plugin.
Start the Master first; the default origin is `http://127.0.0.1:8650`.

The Server defaults to **loopback only**: changing the port alone does not allow
remote/LAN connections. For a trusted LAN, start it explicitly with:

```bash
java -jar build/release/jadx-mcp.jar --host=0.0.0.0 --port=9010 --log-level=INFO
```

`0.0.0.0` is the IPv4 listening address, not a client destination. For this
example, a GUI on the Server machine uses **IP** `127.0.0.1`, **Port** `9010`;
a GUI on another machine uses the Server's actual reachable IP and `9010`.
The MCP client uses `http://<server-ip>:9010/mcp`. Allow inbound TCP 9010 from
trusted clients through the firewall; NAT/public-port forwarding is a separate
condition, and binding alone does not guarantee public reachability. There is no
general authentication or built-in TLS: use a trusted network with controlled
firewall rules or a protected tunnel, never direct public Internet exposure.
`GET /status` returns a plain-text diagnostic with sessions, initialized client aliases
and rolling 10/30/60-second business tool-call totals; it does not prove MCP/tool/GUI
readiness. `/mcp` is a protocol endpoint, not a web page; use an MCP client to
initialize and list tools. See [startup and status checks](../../README.md#install-and-run-the-master-service).[^runtime]

中文：Server 默认仅监听 `127.0.0.1`，远程不可连，改端口不等于开放远程访问。
可信局域网可使用上述显式 `--host=0.0.0.0 --port=9010 --log-level=INFO` 启动。
`0.0.0.0` 是监听地址；GUI 与 Server 同机填 `127.0.0.1` 和 `9010`，跨机填
服务器实际可达 IP 和 `9010`；MCP 客户端填 `http://<server-ip>:9010/mcp`。
防火墙须允许可信客户端入站 TCP 9010，NAT 公网端口转发是独立条件，改 host
不保证公网可达。无通用认证/TLS，仅在可信网络与受控防火墙或隧道下使用，
不要直接裸露公网。`/status` 返回纯文本诊断，会话与调用计数不代表 MCP/工具/GUI 全部就绪；
`GET /mcp` 不是普通网页检查，应由 MCP 客户端验证初始化和工具列表。[^runtime]

**Plugins → Jadx MCP** opens one non-modal window with connection status,
5/10/30-second request counts, and separate numeric **IP** and **Port** fields.
IPv6 is entered without brackets; ports are 1–65535. Do not enter a scheme,
hostname, path or complete URL. **Apply** saves and reconnects in the background
without closing the window and shows **Applied!** on success.[^gui]

`Applied!` acknowledges local settings persistence and bridge activation, **not**
remote connectivity. A valid offline port still applies successfully and enters
`RECONNECTING`; starting the Master there later recovers automatically. Save
failures identify the stage/exception type and leave the connection unchanged;
activation failure after saving is reported separately. No tokens or raw exception
messages are displayed.

中文：`Applied!` 仅确认本地保存及 bridge 启动，不表示服务已连通。有效端口即使
暂无服务也应应用成功并显示重连，服务上线后自动恢复。保存失败显示保存阶段与
异常类型且不切换原连接；保存成功但启动失败单独提示，不显示原始异常或凭据。
专项证据见
[port-switch validation](../testing/evidence/gui-port-switch-20261110.json)。

### Runtime and offline settings / 运行环境与离线配置

The GUI transport uses JDK `java.net.http.HttpClient` with the full JDK 21 described
above. 中文：使用无内置 JRE 的 JADX 1.5.6 ZIP，单独安装完整 JDK 21（含运行环境），
并明确用同一 JDK 启动 JADX 与 Server。

Settings can be applied **before opening a project**. In `DISABLED: Waiting for
project`, Apply only saves the address; HTTP transport is created after a project
is successfully bound. In a remote Windows GUI,
`127.0.0.1` means that Windows computer, not the Mac running the Master. A wrong
destination affects connectivity, not whether valid local settings can be saved.

中文：尚未打开项目也可先保存 IP/端口，显示 `Applied!` 并保持 Waiting for
project；仅在项目绑定后才创建网络 bridge。远端 Windows 的 `127.0.0.1` 指
Windows 自身；Master 若在 Mac，应填写可达的 Mac 地址，但目标填错不应造成
保存失败。无项目配置验收见
[no-project validation](../testing/evidence/gui-no-project-settings-20261110.json)。

Counts are unique received business command IDs, not successful completions;
registration/poll/heartbeat traffic is excluded. They refresh each second and
reset on connection-generation/project changes. A lost edit completion is not
replayed. Transient network failures back off automatically, and a restarted
Master is recovered through a new registration. A non-retryable protocol error
is shown as a safe stage/reason and leaves the bridge `DISABLED`; select
**Reconnect** in this same window to clear that terminal error and attempt a new
registration for the currently bound project. This is a fresh lease: uncertain
commands or edits are never replayed. The action is disabled when there is no
active project or the bridge is not in a terminal-error state; without a bound
project, status remains `Waiting for project` and no HTTP request is made.
An explicitly closed session does not automatically reappear. Existing
installations do not update merely because new artifacts were built.[^gui]

The explicit recovery path was exercised in an isolated JADX GUI/Master run by
injecting a typed `INVALID_ARGUMENT` response, using **Reconnect**, and verifying
a fresh GUI registration. The same run stopped and restarted its isolated Master
and verified automatic recovery after the network outage. An isolated no-project
MainWindow smoke verified `Waiting for project` and a disabled **Reconnect**
button. Evidence and artifact hashes are in
[GUI reconnect validation](../testing/evidence/gui-reconnect-20261110.json).

中文：暂时网络故障仍自动退避重试，Master 重启后自动重新注册。不可重试的协议错误会
安全显示阶段/原因并停在 `DISABLED`；在同一 **Plugins → Jadx MCP** 窗口点击
**Reconnect**，仅对当前已绑定项目发起一次新的注册，清除旧错误，不会重放结果未知的
命令或编辑。没有活动项目或不是终止错误时按钮不可用；无项目时保持
`Waiting for project` 且不发送 HTTP 请求。显式关闭的会话不会自动恢复。证据见
[GUI reconnect validation](../testing/evidence/gui-reconnect-20261110.json)。

The linked port/no-project/reconnect records identify their tested artifacts.
Full-JDK acceptance checks and package hashes:
[current acceptance](../testing/evidence/httpclient-jdk21-validation-20261110.json).

Master has no general authentication/TLS. Use trusted inputs and clients,
loopback or a protected tunnel; do not expose its port directly to the Internet.
See the [README security notice](../../README.md).

## Logging and connection semantics

Start the Master with `--log-level=INFO` (default) or `--log-level DEBUG`.
Exact uppercase `TRACE/DEBUG/INFO/WARN/ERROR/OFF` values are accepted; empty or
unknown values fail startup. INFO records component/session lifecycle, not every
request, poll or heartbeat. DEBUG/TRACE adds application request/backend IDs,
operations and elapsed time (request time includes queueing). WARN records
recoverable rejections/degradation; ERROR records failures. OFF suppresses logs.

The bundled Logback root/console/file thresholds follow the selection before
runtime construction; explicit logger levels are replaced and workers inherit
the level in their JVM arguments. `logs/server.log` rolls by size/date;
`logs/error.log` additionally requires ERROR. Worker files remain in their owned
`<input>.jadx_mcp_cache/session-<unique>/logs/`, even after normal close.
No running GUI host is reconfigured by this Master option.

**Dependency logs (JADX, MCP SDK, Ktor, Netty and other non-project loggers) are
WARN+ only even at DEBUG/TRACE.** This deliberately trades library DEBUG detail
for payload safety: the SDK's TRACE logs include tool arguments. Application
failures log code/type, not exception messages or stacks, including at DEBUG;
dependency WARN/ERROR may include their own stacks, so review shared excerpts.
Tokens, payloads, source, selections, comment bodies and input paths are not
included in application logs. The kotlin-logging stdout startup banner is
disabled; CLI help/errors and JVM/native stderr diagnostics are outside Logback.

MCP logging follows SDK session initialization/transport close, not HTTP request
counts. `sdkSessionId` is neither authentication nor necessarily the HTTP session
header. Duplicate initialization/close callbacks do not duplicate lifecycle
messages. DELETE ends the session; application shutdown closes remaining sessions.
**A silent client/TCP half-close without DELETE has no default SDK idle timeout;
there is no claim of immediate network-loss detection.** GUI reverse-channel
registration is logged separately as `GUI registered/reconnected/disconnected/closed`.
Its lease timeout/retention is distinct from an MCP client lifetime. Headless
publication logs READY only after loading succeeds; failed/aborted creation is
not logged as an opened session. Close is idempotent; expiry and shutdown use
distinct close reasons.[^logging]

## Generated data and user projects

For `/work/demo.apk`, headless generated data belongs to
`/work/demo.apk.jadx_mcp_cache/session-<unique>/`. Each session gets a fresh child.
JVM temporary files, plugin
configuration/cache/temp, output and generated aliases stay in that child.
Relative paths are normalized and input symlinks resolve to the real target.
Unwritable or unsafe locations fail explicitly.[^cache]

Normal close keeps generated data. `session_close(clear_cache=true)` stops the
worker and checks the marked child's identity before deleting that child only.
It preserves the input, other sessions, shared root and **all native `.jadx`
projects**. Do not manually delete an active session directory. Deleting the
input does not automatically remove sibling generated data.[^cache]

This ownership rule does not cover native GUI host caches/projects. GUI response
caches are in memory. Headless rename is in-memory (`persisted=false`); GUI saves
follow native ASK/ALWAYS ownership. Inspect `state.applied` and `state.persisted`
separately.

Portable guarded path deletion is not atomic protection against a malicious
same-user process racing filesystem changes. Native GUI close during in-flight
analysis and native save/reopen still require dedicated host validation.

[^gui]: [Plugin](../../gui-plugin/src/main/kotlin/com/yyang/jadxmcp/runtime/JadxMcpGuiPlugin.kt), [window](../../gui-plugin/src/main/kotlin/com/yyang/jadxmcp/runtime/GuiBridgeStatusWindow.kt), [settings](../../gui-plugin/src/main/kotlin/com/yyang/jadxmcp/runtime/MasterConnectionSettings.kt), [final evidence and limits](../testing/validation.md).
[^runtime]: [CLI defaults and parsing](../../app/src/main/kotlin/com/yyang/jadxmcp/bootstrap/MasterRuntime.kt), [help entry](../../app/src/main/kotlin/com/yyang/jadxmcp/bootstrap/MasterMain.kt), [HTTP status/MCP routes and security](../../app/src/main/kotlin/com/yyang/jadxmcp/api/MasterApi.kt).
[^cache]: [Owned cache](../../app/src/main/kotlin/com/yyang/jadxmcp/worker/OwnedWorkerCache.kt), [boundary tests](../../app/src/test/kotlin/com/yyang/jadxmcp/worker/OwnedWorkerCacheTest.kt), [real worker tests](../../app/src/test/kotlin/com/yyang/jadxmcp/backend/AnalysisWorkerIntegrationTest.kt).
[^logging]: [Logging policy](../../app/src/main/kotlin/com/yyang/jadxmcp/bootstrap/Logging.kt), [lifecycle](../../app/src/main/kotlin/com/yyang/jadxmcp/session/SessionService.kt), [SDK callbacks](../../app/src/main/kotlin/com/yyang/jadxmcp/api/McpAdapter.kt), [historical packaged acceptance](../testing/validation.md#logging-acceptance-historical).
