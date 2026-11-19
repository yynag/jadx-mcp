# 项目概览

[English](PROJECT.md) · [README](README.md) · [中文 README](README_zh.md)

## 目标与架构

jadx-mcp 为 AI 代理及其他可信客户端提供统一的 JADX 分析接口，可使用无界面进程或已有的 JADX GUI 项目。它提供会话管理、元数据、反编译、资源、搜索、交叉引用和编辑，并提供额外的 GUI 上下文及调试器读取功能。不同后端的能力和持久化语义不同；接口统一并不意味着二者可以完全互换。

项目包含四个 Gradle 模块：

| 模块 | 职责 |
| --- | --- |
| [`protocol`](protocol/) | 类型化操作、校验、编解码、执行上下文及后端/传输端口。 |
| [`analysis`](analysis/) | 无界面与 GUI 适配器共享的 JADX 分析和项目服务；JADX 由宿主提供。 |
| [`app`](app/) | Master HTTP/MCP 适配、会话路由与生命周期，以及无界面 Worker 进程和传输。 |
| [`gui-plugin`](gui-plugin/) | JADX GUI 插件、出站桥接、借用宿主的项目/UI 适配器及调试器集成。 |

Master 与 Worker 打包在一起；GUI 插件单独发布。主要入口为 [`MasterMain`](app/src/main/kotlin/com/yyang/jadxmcp/bootstrap/MasterMain.kt)、[`WorkerMain`](app/src/main/kotlin/com/yyang/jadxmcp/worker/WorkerMain.kt) 和 [GUI ServiceLoader 入口](gui-plugin/src/main/resources/META-INF/services/jadx.api.plugins.JadxPlugin)。

## 信任边界与调用链

**Master 没有通用认证，也没有内置 TLS。** 默认仅监听回环地址（`127.0.0.1:8650`），CORS 策略宽松，且关闭了 MCP DNS 重绑定保护。回环地址不等于认证。应信任客户端和输入，优先使用受保护隧道；如需开放局域网，必须另行设置防火墙和访问控制，绝不可将 Master 直接暴露于公网。请求可能读取分析数据或修改项目；输入路径在 Master 主机上解析。

1. 客户端通过 `/mcp` 使用 MCP Streamable HTTP，或向 `/api/<operation>` 发送 JSON REST 请求；不支持 MCP Stdio。[`MasterApi`](app/src/main/kotlin/com/yyang/jadxmcp/api/MasterApi.kt) 和 [`McpAdapter`](app/src/main/kotlin/com/yyang/jadxmcp/api/McpAdapter.kt) 通过共享的 [`CommandRegistry`](app/src/main/kotlin/com/yyang/jadxmcp/api/CommandRegistry.kt) 校验并分发类型化请求至 [`SessionService`](app/src/main/kotlin/com/yyang/jadxmcp/session/SessionService.kt)。
2. HEADLESS 模式下，Master 为每个会话启动一个 Worker JVM。Worker 加载输入，使用随机的每进程 bearer token 在回环地址上提供类型化 RPC。该 token 保护内部 Worker 端点，**不等同于 Master 认证**，也不能代替网络防护。进程隔离并非针对不可信输入的沙箱。
3. GUI 模式下，插件绑定已加载的原生项目，并主动向 Master 发起出站 HTTP：注册、长轮询获取命令、提交结果、心跳和注销。Master **不会**反向连接 GUI。GUI lease token 在注册后用于隔离通道，不提供 Master 通用认证；项目 epoch 用于隔离过期请求和结果。

`GET /status` 只是诊断快照，不能证明 MCP、全部工具或后端已就绪。参见[运行与安全说明](README_zh.md)及 [GUI 连接说明](docs/usage/gui-and-cache.md)。

## 操作

[操作注册表](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/Operation.kt) 定义了 **26 个 REST 操作**，均使用 `POST /api/<operation>`。MCP 适配器公开 **25 个工具**，不包含仅限 GUI 的 REST 操作 `get_main_application_classes_names`：

| 范围 | 操作 |
| --- | --- |
| 管理 | `session_create`、`session_list`、`session_close` |
| 两种后端均支持的分析 | `meta_summary`、`meta_manifest`、`meta_class`、`decompile`、`resource`、`xref`、`search`、`rename` |
| 仅限 GUI 的 MCP 工具 | `fetch_current_class`、`get_selected_text`、`get_method_by_name`、`get_main_application_classes_code`、`get_package_tree`、`get_cache_stats`、`clear_cache`、`rename_package`、`rename_variable`、`add_comment`、`list_comments`、`debug_get_threads`、`debug_get_stack_frames`、`debug_get_variables` |
| 仅限 GUI 的 REST 操作，不属于 MCP | `get_main_application_classes_names` |

分析调用通过 `session_id` 选择会话；`session_create` 只能创建 HEADLESS 会话，GUI 会话由插件自行注册。后端支持的能力和选项可能不同。调试器读取要求已附加且暂停的调试器，读取的是已物化的 UI 数据，而非原子 VM 快照。

REST 接收 JSON 参数对象，返回带版本的结果封装或错误封装；MCP 返回按操作精简的文本，并用 `isError` 标记工具错误。参数使用 `snake_case`，枚举值必须精确大写。REST 可提供 `request_id` 和 `project_epoch`；MCP schema 隐藏这两个控制项。校验、分页与搜索预算参见[协议契约](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/Contract.kt)和[命令 schema](app/src/main/kotlin/com/yyang/jadxmcp/api/CommandSchema.kt)。

## 会话、并发与生命周期

每个会话使用有界的串行执行通道，不同会话可并发运行；管理操作不经过该分析通道。默认调用期限为 120 秒，包含排队时间；超时或取消可使调用方提前返回，但未必能停止原生 JADX 工作。运行中的调用在实际退出前仍占用物理通道，防止与后继调用重叠。执行中的 Worker 调用被取消或超时后，Worker 进入终止状态并被结束，不再复用。关闭会立即撤销路由，后端释放可能稍后完成；显式清理缓存会等待释放，但受调用方期限约束。

GUI 桥接会对暂时网络故障重试，并可在 Master 重启后重新注册。连接断开后 lease 会过期；显式关闭会话只禁用桥接会话，不关闭 GUI 应用，也不会自动重连。结果未知的编辑不会重放。项目切换会使旧绑定失效；epoch 和 lease 替换用于拒绝过期工作。插件借用 GUI 资源，不得关闭宿主反编译器。参见 [`SessionPolicy`](app/src/main/kotlin/com/yyang/jadxmcp/session/SessionPolicy.kt)、[`WorkerBackend`](app/src/main/kotlin/com/yyang/jadxmcp/process/WorkerBackend.kt) 和 [`GuiProjectRuntime`](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/runtime/GuiProjectRuntime.kt)。

## 所有权、编辑与缓存

- 每个无界面会话在规范化后的真实输入旁的 `<input-filename>.jadx_mcp_cache/` 下分配新的带标记子目录；生成的临时文件、插件数据、输出和别名属于该目录，不供后续会话复用。正常关闭会保留这些数据。
- `session_close(clear_cache=true)` 停止 Worker 后，仅删除经所有权及身份校验的子目录，不跟随符号链接。输入、其他会话、共享根目录和原生 `.jadx` 项目均不属于该清理范围。删除输入不会自动删除缓存。可移植文件系统检查无法原子地防御恶意同用户进程的路径竞争。
- 无界面重命名仅在内存中生效，返回 `persisted=false`；生成的别名不是用户编辑持久化。GUI 编辑由原生项目负责：`ASK` 不强制保存，`ALWAYS` 也只有在原生项目路径有效且宿主保存成功时才能保存。须分别检查 `state.applied` 和 `state.persisted`。
- GUI 的 `clear_cache` 仅清理插件内存中的响应/源码缓存，不清理 GUI 原生磁盘缓存。GUI 项目和宿主缓存仍归宿主管理。参见[缓存与持久化说明](docs/usage/gui-and-cache.md#generated-data-and-user-projects)和 [`OwnedWorkerCache`](app/src/main/kotlin/com/yyang/jadxmcp/worker/OwnedWorkerCache.kt)。

## 构建、测试与验证边界

Master、Worker 和 GUI 均需使用完整 JDK 21；插件使用不含内置 JRE 的 JADX 1.5.6 发行包，其他 JADX 版本尚未验证。Gradle Wrapper 可运行四个模块的测试并构建两个发布用 Shadow JAR：

```bash
./gradlew test --rerun-tasks
./gradlew releaseArtifacts
```

产物为 `build/release/jadx-mcp.jar` 和 `build/release/jadx-mcp-gui-plugin.jar`。GUI 安装、运行环境与启动方法参见[中文 README](README_zh.md)。普通测试不能替代原生 GUI 验收；[测试说明](docs/testing/README.md)介绍了可选的隔离 GUI 和授权输入冒烟测试。

[已有验证记录](docs/testing/validation.md)有明确日期和构建、样本范围，并不保证当前工作区通过。它们不能证明 APK/资源覆盖完整、全部工具和参数组合均可用、已附加设备的调试成功、原生保存后重新打开成功，或压力下不存在竞争。GUI 在原生分析运行中关闭，以及不配合取消的原生工作，仍是重要边界。验证新修订时应重新记录来源和结果；仅有打包、`--help` 或 `/status` 不构成端到端验证。
