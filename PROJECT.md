# Project overview

[中文](PROJECT_zh.md) · [README](README.md) · [中文 README](README_zh.md)

## Purpose and architecture

jadx-mcp gives AI agents and other trusted clients a common interface to JADX analysis, either in a headless process or in an existing JADX GUI project. It provides session management, metadata, decompilation, resources, search, cross-references and edits, with additional GUI context and debugger reads. Backends have different capabilities and persistence semantics; a common interface does not make them interchangeable.

The four Gradle modules are:

| Module | Responsibility |
| --- | --- |
| [`protocol`](protocol/) | Typed operations, validation, codecs, execution contexts and backend/transport ports. |
| [`analysis`](analysis/) | JADX-backed analysis and project services shared by the headless and GUI adapters; the host supplies JADX. |
| [`app`](app/) | Master HTTP/MCP adapters, session routing and lifecycle, and the headless Worker process and transport. |
| [`gui-plugin`](gui-plugin/) | JADX GUI plugin, outbound bridge, borrowed host/project/UI adapters and debugger integration. |

The Master and Worker ship together; the GUI plugin is a separate artifact. The main entry points are [`MasterMain`](app/src/main/kotlin/com/yyang/jadxmcp/bootstrap/MasterMain.kt), [`WorkerMain`](app/src/main/kotlin/com/yyang/jadxmcp/worker/WorkerMain.kt), and the [GUI ServiceLoader entry](gui-plugin/src/main/resources/META-INF/services/jadx.api.plugins.JadxPlugin).

## Trust boundaries and request flow

**The Master has no general authentication or built-in TLS.** It defaults to loopback (`127.0.0.1:8650`), uses permissive CORS and disables MCP DNS-rebinding protection. Loopback is not authentication. Trust clients and inputs, prefer a protected tunnel, and use explicit firewall/access controls for any LAN exposure; never expose the Master directly to the public Internet. Requests may read analysis data and mutate projects; input paths are resolved on the Master host.

1. A client sends MCP Streamable HTTP to `/mcp` or JSON REST requests to `/api/<operation>`; MCP Stdio is not supported. [`MasterApi`](app/src/main/kotlin/com/yyang/jadxmcp/api/MasterApi.kt) and [`McpAdapter`](app/src/main/kotlin/com/yyang/jadxmcp/api/McpAdapter.kt) feed the shared [`CommandRegistry`](app/src/main/kotlin/com/yyang/jadxmcp/api/CommandRegistry.kt), which validates and dispatches typed requests to [`SessionService`](app/src/main/kotlin/com/yyang/jadxmcp/session/SessionService.kt).
2. For HEADLESS, the Master starts one Worker JVM per session. The Worker loads the input and serves typed RPC on loopback using a random per-process bearer token. This token protects the internal Worker endpoint; **it is not Master authentication** or a substitute for network protection. Process isolation is not a sandbox for untrusted inputs.
3. For GUI, the plugin binds a loaded native project and initiates outbound HTTP to the Master: register, long-poll for commands, complete, heartbeat and unregister. The Master does **not** connect back to the GUI. GUI lease tokens fence the channel after registration; they do not provide general Master authentication. Project epochs fence stale requests and completions.

`GET /status` is a diagnostic snapshot, not proof that MCP, every tool or a backend is ready. See the [README](README.md) and [GUI connection details](docs/usage/gui-and-cache.md).

## Operations

The [operation registry](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/Operation.kt) defines **26 REST operations**, all at `POST /api/<operation>`. The MCP adapter exposes **25 tools**, omitting the GUI-only REST operation `get_main_application_classes_names`:

| Scope | Operations |
| --- | --- |
| Management | `session_create`, `session_list`, `session_close` |
| Analysis on both backends | `meta_summary`, `meta_manifest`, `meta_class`, `decompile`, `resource`, `xref`, `search`, `rename` |
| GUI-only MCP | `fetch_current_class`, `get_selected_text`, `get_method_by_name`, `get_main_application_classes_code`, `get_package_tree`, `get_cache_stats`, `clear_cache`, `rename_package`, `rename_variable`, `add_comment`, `list_comments`, `debug_get_threads`, `debug_get_stack_frames`, `debug_get_variables` |
| GUI-only REST, not MCP | `get_main_application_classes_names` |

Analysis calls select a `session_id`; `session_create` creates HEADLESS sessions only, while GUI sessions register themselves. Capabilities and options can differ by backend. Debugger reads require an attached, suspended debugger and expose materialized UI data, not an atomic VM snapshot.

REST accepts a JSON parameter object and returns a versioned result envelope or an error envelope; MCP returns compact operation-specific text and marks tool errors with `isError`. Parameters use `snake_case` and exact uppercase enums. REST can supply `request_id` and `project_epoch`; MCP schemas hide those controls. See the [protocol contract](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/Contract.kt) and [command schemas](app/src/main/kotlin/com/yyang/jadxmcp/api/CommandSchema.kt) for validation, pagination and search budgets.

## Sessions, concurrency and lifecycle

Each session has a bounded, serial execution lane; different sessions can run concurrently. Management is handled separately from that lane. The default call deadline is 120 seconds and includes queueing; deadlines and cancellation can release the caller without stopping native JADX work. A running call retains its physical lane until it exits, preventing overlap with its successor. A cancelled or timed-out Worker execution becomes terminal and the child is terminated rather than reused. Closing revokes routing immediately; backend disposal can finish later. Explicit cache cleanup waits for disposal, subject to the caller's deadline.

The GUI bridge retries transient network failures and can re-register after a Master restart. Leases expire after disconnection; explicit session close disables the bridge session, not the GUI application, and does not automatically reconnect. Unknown edit completions are not replayed. Project changes invalidate the old binding; epochs and lease replacement reject stale work. The plugin borrows GUI resources and must not close the host decompiler. See [`SessionPolicy`](app/src/main/kotlin/com/yyang/jadxmcp/session/SessionPolicy.kt), [`WorkerBackend`](app/src/main/kotlin/com/yyang/jadxmcp/process/WorkerBackend.kt), and [`GuiProjectRuntime`](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/runtime/GuiProjectRuntime.kt).

## Ownership, edits and caches

- Every headless session receives a fresh marked child under `<input-filename>.jadx_mcp_cache/` beside the canonical input; generated temp, plugin data, output and aliases belong there and are not reused by later sessions. Normal close retains them.
- `session_close(clear_cache=true)` stops the Worker and deletes only its owned, identity-checked child without following symlinks. The input, sibling sessions, shared root and native `.jadx` projects are not owned by this cleanup. Deleting the input does not automatically delete its cache. Portable checks are not an atomic defense against a hostile same-user filesystem race.
- Headless renames are in memory and report `persisted=false`; generated aliases are not user-edit persistence. GUI edits belong to the native project: `ASK` does not force a save, while `ALWAYS` can save only through a valid native project path and successful host save. Inspect `state.applied` and `state.persisted` separately.
- GUI `clear_cache` clears the plugin's in-memory response/source cache, not the native GUI disk cache. GUI projects and host caches remain host-owned. See [cache and persistence usage](docs/usage/gui-and-cache.md#generated-data-and-user-projects) and [`OwnedWorkerCache`](app/src/main/kotlin/com/yyang/jadxmcp/worker/OwnedWorkerCache.kt).

## Build, test and verification limits

Use a full JDK 21 for the Master, Workers and GUI, and the JADX 1.5.6 distribution without an embedded JRE for the plugin; other JADX versions are not verified. The Gradle Wrapper builds both release Shadow JARs and runs tests across the four modules:

```bash
./gradlew test --rerun-tasks
./gradlew releaseArtifacts
```

The artifacts are `build/release/jadx-mcp.jar` and `build/release/jadx-mcp-gui-plugin.jar`. GUI installation, runtime setup and startup are described in the [README](README.md). Ordinary tests do not replace native-GUI acceptance; [testing instructions](docs/testing/README.md) describe optional isolated GUI and authorized-input smoke tests.

[Recorded validation](docs/testing/validation.md) is dated and scoped to specific builds and fixtures, not a guarantee for the current checkout. It does not establish exhaustive APK/resource coverage, every tool/parameter combination, positive attached-device debugging, native save/reopen, or race-free behavior under stress. GUI close during in-flight native analysis and cancellation of non-cooperative native work remain important limits. Record fresh provenance and results when validating a new revision; packaging, `--help` and `/status` alone are not end-to-end verification.
