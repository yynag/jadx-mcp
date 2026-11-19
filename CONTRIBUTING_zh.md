# 为 jadx-mcp 做贡献

[English](CONTRIBUTING.md)

## 问题报告与拉取请求

- 请提供 Java、操作系统和 JADX 版本、复现命令、预期与实际行为，以及经过**脱敏且长度受限**的日志片段。不要公开私有 APK、凭据、个人路径或完整的反编译响应。
- 保持 Kotlin 代码模块化和线程安全。注释、KDoc 和日志使用英语；保持 [英文](README.md)和[中文](README_zh.md) README 的说明一致。
- 将实现原理放在相关代码和回归测试旁边，不要放入集中的设计决策文档。公开的 `docs/` 按使用和测试主题组织。示例应使用实际的协议 DTO。
- 提交范围集中的 PR，清楚说明改动、验证情况及限制。绝不要以清理为由丢弃不熟悉的未跟踪源码或测试夹具。

## 验证

使用完整的 JDK 21，一次只运行一个 Gradle 构建：

```bash
./gradlew --no-daemon --no-parallel --max-workers=1 test --rerun-tasks releaseArtifacts
java -jar build/release/jadx-mcp.jar --help
unzip -p build/release/jadx-mcp.jar META-INF/MANIFEST.MF
unzip -p build/release/jadx-mcp-gui-plugin.jar META-INF/services/jadx.api.plugins.JadxPlugin
```

仅当依赖已存在时才使用 `--offline`。在 agent 执行环境中，长时间运行的命令应设置外层超时并在后台运行。不要并发运行最终验收任务。[隔离冒烟测试说明](docs/testing/README.md)涵盖五个冒烟测试支持文件、GUI 设置与重连及结果不确定的操作完成情况的组合测试、APK HTTP 测试，以及确定性的正式方法身份测试。只分发 `build/release/` 下的两个 Shadow 包；普通 JAR 和模块 JAR 仅供内部使用。JADX 宿主 API 固定为 1.5.6。[^build]

记录源码修订版本及工作区是否有未提交更改、JDK、命令、退出状态、各模块测试数量、产物哈希和排除的测试情况。[验证记录](docs/testing/validation.md)分别记录完整测试套件、打包后日志、原生 GUI 和重命名/读回结果。打包后使用 `python3.12 -B app/src/test/python/logging_acceptance.py` 复现日志检查。源码变更后应重新验证，不要沿用记录中的测试数量。

调试器成功路径，以及 26 个 REST 操作和 25 个 MCP 工具的所有参数组合，尚未得到完整覆盖。早期 MCP 包装器无法完整观察原始 `isError` 的限制，与最终直接客户端的错误检查是不同的问题。Actions 仅经过静态检查：未使用 PyYAML 进行完整 YAML 语义解析，也未运行远程 CI。没有执行 commit/push。仅进行文档清理和包/帮助信息检查不能证明运行时正确。

## 需要保持的运行时边界

- INFO 用于生命周期信息，DEBUG 用于请求耗时，WARN 用于可恢复的拒绝，ERROR 用于故障。绝不要记录载荷、令牌、源码、选区或评论内容，也不要记录含私人路径的异常消息。当前依赖日志的 WARN+ 安全过滤是有意设置的：不要声称支持所有库的 DEBUG 日志，也不要启用 SDK TRACE 参数转储。保持 Worker 日志级别继承和各会话文件隔离。MCP 会话关闭并不意味着能即时检测 TCP 连接丢失；SDK 0.14 默认不会让静默会话因空闲而过期。参见[日志约定](docs/usage/gui-and-cache.md#logging-and-connection-semantics)。
- Headless 生成的数据归属于新建的 `<complete-input-name>.jadx_mcp_cache/session-<unique>/` 子目录。受保护的清理操作前先停止 Worker；保留输入文件、原生 `.jadx` 项目、共享根目录及其他会话。不可写或不安全的位置会明确报错。可移植检查不能保证针对恶意的同用户文件系统竞争实现原子安全。[^cache]
- 原生分析进行期间关闭原生 GUI 的情况仍需宿主级压力验证。会话准入和结果屏障不能证明借用操作进行时原生反编译器不会关闭；截止时间也不能可靠地中断原生工作。[^lifecycle]
- `MAIN_ACTIVITY` 使用清单元数据和解析结果；在新输入上应验证资源引用形式的标志、别名和启动入口选择。已有记录表明同一 APK 的 GUI/HEADLESS 启动入口一致，但并非覆盖所有输入组合。[^manifest]
- Headless 重命名仍只在内存中生效（`persisted=false`）。GUI 的 ASK 不强制保存；ALWAYS 要求存在项目路径且原生保存成功。分别验证 `applied` 和 `persisted`。[^persistence]
- 设备调试器成功路径、原生保存后重新打开、异步重命名/编辑器刷新及全面的 APK 覆盖情况必须明确记录，不能从夹具测试或帮助输出推断。

## 证据与清理

保留包含结果、数量、耗时、错误和哈希的最小脱敏 JSON；避免重复的构建日志或含完整源码的网络传输记录。生成的冒烟测试输出位于 `build/smoke/`，不是复现所需的持久前置条件。绝不要删除正在运行的 JAR、打开中的日志或原生 `.jadx` 项目。保留当前验收输出，直到其负责人归档所需证据。

[^build]: [根发布任务](build.gradle.kts)、[应用](app/build.gradle.kts)、[GUI 冒烟测试任务](gui-plugin/build.gradle.kts)、[CI](.github/workflows/build.yml)。
[^cache]: [受管缓存](app/src/main/kotlin/com/yyang/jadxmcp/worker/OwnedWorkerCache.kt)、[边界测试](app/src/test/kotlin/com/yyang/jadxmcp/worker/OwnedWorkerCacheTest.kt)、[真实 Worker 测试](app/src/test/kotlin/com/yyang/jadxmcp/backend/AnalysisWorkerIntegrationTest.kt)。
[^lifecycle]: [加载钩子](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/runtime/JadxGuiLoadHook.kt)、[运行时](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/runtime/GuiProjectRuntime.kt)、[工作区租约](analysis/src/main/kotlin/com/yyang/jadxmcp/analysis/workspace/JadxWorkspace.kt)。
[^manifest]: [方法/源码服务](analysis/src/main/kotlin/com/yyang/jadxmcp/analysis/source/JadxSourceService.kt)、[清单服务](analysis/src/main/kotlin/com/yyang/jadxmcp/analysis/metadata/ManifestService.kt)、[资源标志](analysis/src/main/kotlin/com/yyang/jadxmcp/analysis/resource/NativeBooleanResolver.kt)。
[^persistence]: [操作列表](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/Operation.kt)、[Worker 组装](app/src/main/kotlin/com/yyang/jadxmcp/backend/AnalysisWorkerEndpointFactory.kt)、[项目服务](analysis/src/main/kotlin/com/yyang/jadxmcp/analysis/project/JadxProjectService.kt)、[GUI 所有者](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/project/GuiProjectDataOwner.kt)。

## 许可证

贡献采用 [Apache-2.0](LICENSE) 许可证。
