# jadx-mcp

[English](README.md)

`jadx-mcp` 通过 REST API 和 MCP 将 AI 客户端及其他工具连接到 JADX。Master 服务管理隔离的无头分析会话，并通过插件连接已打开的 JADX GUI 项目。

## 主要功能

- 查看元数据、清单和资源；搜索、查找引用并反编译代码。
- 为 APK、DEX、JAR 等 JADX 支持的输入创建无头会话，或使用已注册的 GUI 会话。
- 重命名符号；GUI 会话还提供选中文本、注释和调试器查看功能。部分操作仅适用于特定后端；可通过 MCP `tools/list` 查看工具及其参数。

## 环境要求与下载

- 构建及运行 Master、Worker 和 JADX GUI 均需使用**完整 JDK 21**（包含运行环境）。确保实际进程使用该 JDK；裁剪的运行环境可能缺少必要模块。
- GUI 集成使用**不含内置 JRE 的 JADX 1.5.6 ZIP**（`jadx-1.5.6.zip`），并用上述 JDK 启动。其他 JADX 版本未经验证。
- 从 [GitHub Releases](https://github.com/yynag/jadx-mcp/releases) 下载 `jadx-mcp.jar`（Master 和 Worker）与 `jadx-mcp-gui-plugin.jar` 两个文件，或从源码构建。若发布页提供校验和，请核验。

在仓库根目录选择 JDK 21 后运行：

```bash
./gradlew releaseArtifacts
```

Gradle Wrapper 生成 `build/release/jadx-mcp.jar` 和 `build/release/jadx-mcp-gui-plugin.jar`，无需单独安装 Gradle。以下路径以源码构建为例；使用 Release 时请替换为下载的 JAR 路径。

## 安装并启动 Master 服务

启动 Master 后，在另一个终端中检查状态：

```bash
java -jar build/release/jadx-mcp.jar
curl http://127.0.0.1:8650/status
```

默认监听 `127.0.0.1:8650`，仅服务器本机可访问。`/status` 是纯文本诊断信息，不代表所有功能均已就绪。运行 `java -jar build/release/jadx-mcp.jar --help` 查看 `--host`、`--port`、`--worker-heap`（默认 `2g`）和 `--log-level` 等选项。

### 最小 REST 工作流

将路径替换为 **Master 所在主机**上已有的输入文件。进程必须能读取该文件并在其旁边写入数据。

```bash
curl -X POST http://127.0.0.1:8650/api/session_create \
  -H 'Content-Type: application/json' \
  -d '{"type":"HEADLESS","apk_path":"/absolute/path/app.apk"}'
```

从响应中复制 `result.session_id`，替换下面的占位符：

```bash
curl -X POST http://127.0.0.1:8650/api/meta_summary \
  -H 'Content-Type: application/json' -d '{"session_id":"SESSION_ID"}'
curl -X POST http://127.0.0.1:8650/api/session_close \
  -H 'Content-Type: application/json' -d '{"session_id":"SESSION_ID"}'
```

REST 操作使用 `POST /api/<operation>` 和 JSON 参数对象；成功响应包含 `result` 封装。

### 最小 MCP 工作流

使用 MCP 客户端的 **Streamable HTTP** 连接 `http://127.0.0.1:8650/mcp`，不支持 MCP Stdio。由客户端完成协议初始化并列出工具，然后调用：

1. `session_create`，参数为 `{"type":"HEADLESS","apk_path":"/absolute/path/app.apk"}`。
2. `meta_summary`，参数为 `{"session_id":"SESSION_ID"}`，替换为 `session_create` 返回的 ID。
3. 完成后调用 `session_close`，参数为 `{"session_id":"SESSION_ID"}`。

也可调用 `session_list`，参数为 `{}`，选择已注册的 GUI 会话 ID。MCP 工具文本是精简结果，而非 REST 封装；失败时检查 `isError`。分析操作需要 `session_id`。

### MCP 工具与能力

操作列表及后端限制参见[项目概览](PROJECT_zh.md#操作)。使用 `tools/list` 查看当前参数定义；参数名使用 `snake_case`，枚举值使用大写。

## 安装 JADX GUI 插件

例如，在 Windows PowerShell 中选择完整 JDK 并用它启动不含 JRE 的 JADX 发行包（请替换路径）：

```powershell
$env:JAVA_HOME = 'C:\Java\jdk-21'
& "$env:JAVA_HOME\bin\java.exe" -jar 'C:\Tools\jadx-1.5.6\lib\jadx-1.5.6-all.jar'
```

在另一个 shell 中也显式使用同一 JDK 启动 Master；Worker 继承其 Java 安装。

1. 使用完整 JDK 21 启动 JADX GUI 1.5.6。在 **Preferences → Plugins → Install plugin** 中通过本地 JAR 选择器选择 `jadx-mcp-gui-plugin.jar`，安装并重启或重新加载插件。
2. 启动 Master，然后打开 **Plugins → Jadx MCP**。默认 **IP** 和 **Port** 分别为 `127.0.0.1` 和 `8650`。连接其他服务器时填写其可达的数字 IP 和端口，不要填写 URL 或主机名；IPv6 不带方括号。点击 **Apply**。
3. 在 JADX 中打开输入文件或项目。项目绑定后插件主动连接并注册；调用 `session_list` 查找 GUI `session_id`。**Applied!** 仅确认本地配置，不能证明已连通；请另行检查连接状态。

## 安全、权限与数据

> **Master 没有通用身份认证或内置 TLS。** CORS 宽松且 MCP DNS rebinding 防护关闭。回环地址不是身份认证边界。仅使用可信客户端、网络和输入；不要将服务直接暴露到公网。远程使用优先选择 SSH 隧道；否则须显式配置可达的 `--host` 和严格的防火墙/访问控制。`0.0.0.0` 是监听地址，不是客户端目标地址。

- 仅分析有权访问的输入。客户端可使用服务器或 GUI 用户的权限读取分析数据并执行编辑。Worker 进程隔离不是沙箱。请审核 AI 发起的操作，并将被分析内容视为不可信指令。
- 无头会话在解析后的输入文件旁创建 `<input-filename>.jadx_mcp_cache/session-<unique>/`。每个会话使用新的子目录；正常关闭会保留数据。`session_close` 设置 `clear_cache: true` 时仅删除该会话受保护的子目录，不删除输入或原生 `.jadx` 项目。不要手动删除活动会话数据。
- 生成缓存**不等于**编辑持久化：无头重命名仅在内存中生效，返回 `persisted=false`。GUI 编辑遵循 JADX 原生项目与保存行为；应分别检查 `state.applied` 和 `state.persisted`。GUI 响应缓存位于内存，与宿主项目数据及磁盘缓存分离。

## 测试与文档

在仓库根目录使用 JDK 21 运行测试：

```bash
./gradlew --no-daemon --no-parallel --max-workers=1 test --rerun-tasks
```

- [文档索引](docs/README.md)、[GUI 连接、缓存与持久化](docs/usage/gui-and-cache.md)
- [测试与冒烟测试命令](docs/testing/README.md)、[验证范围和限制](docs/testing/validation.md)
- [项目概览与架构](PROJECT_zh.md)

## 许可证

[Apache-2.0](LICENSE)
