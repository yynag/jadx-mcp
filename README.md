# jadx-mcp

`jadx-mcp` provides unified headless JADX and JADX GUI sessions to AI agents. The service exposes a REST management API and **MCP Streamable HTTP** at `/mcp`. MCP Stdio is not supported.

> **Security:** The Master has no general authentication or built-in TLS and defaults to `127.0.0.1`. CORS is permissive and MCP DNS-rebinding protection is disabled. Loopback is not an authentication boundary; use only trusted clients and inputs. Prefer an SSH tunnel. Explicit `--host` LAN exposure requires firewall/access controls; never expose it directly to the public Internet.[^runtime]

## Components

- **Master service** — manages sessions, exposes MCP and REST, and routes requests.
- **Headless Worker** — one isolated JVM per loaded APK/DEX/JAR, reachable only on loopback.
- **JADX GUI plugin** — registers each GUI instance with the Master and communicates through an outbound long-poll channel. The Master does not connect back to the GUI.
- **MCP client** — connects to the Master using Streamable HTTP. There is no Stdio transport.

Every analysis call uses a `session_id`. Headless and GUI are complementary backends behind one MCP interface: supported tools are routed to the selected session type, while backend-specific operations and options are explicitly restricted. Requests in one session are serialized; different sessions may run concurrently.

## Requirements

- Install a **full JDK 21**, including its Java runtime, for building and running **both JADX GUI and the Master/Workers**. Do not use a trimmed JRE/custom runtime. HTTP clients need `java.net.http`, Workers need `jdk.httpserver`, and GUI/XML/compiler features need the other full-JDK modules. `--help` alone does not verify runtime dependencies.
- Use the **JADX 1.5.6 ZIP without an embedded JRE** (`jadx-1.5.6.zip`), not a `with-jre` package. Ensure the actual GUI process and Server use that full JDK 21; merely installing it or adding it to PATH is insufficient. Other JADX versions are not verified. JADX core/GUI and Gson APIs are supplied by the host.[^build]
- Network access from the MCP client to the Master. When using the GUI plugin, the GUI host must be able to make outbound HTTP requests to the Master.

Gradle Wrapper is included; a separate Gradle installation is not required.

## Build

Run from the repository root:

```bash
./gradlew releaseArtifacts
```

Artifacts:

| Component | Artifact |
|---|---|
| Master + Worker full package | `build/release/jadx-mcp.jar` |
| JADX GUI plugin | `build/release/jadx-mcp-gui-plugin.jar` |

Distribute the two Shadow JARs from `build/release/`; the ordinary application `plain` JAR is internal and the GUI ordinary `jar` task is disabled. The release task and workflow collect both packages.[^build]

Run all four modules' tests with:

```bash
./gradlew test --rerun-tasks
```

## Install and run the Master service

1. Run `releaseArtifacts` as shown above, or obtain `jadx-mcp.jar` from a trusted release.
2. On the machine that will analyze APKs, start the service:

   ```bash
   java -jar build/release/jadx-mcp.jar --help
   java -jar build/release/jadx-mcp.jar --port 8650
   ```

   Options are `--host` (default `127.0.0.1`), `--port` (default `8650`), `--worker-heap` (default `2g`), and `--log-level` (default `INFO`). Both `--log-level=DEBUG` and `--log-level DEBUG` work; names are exact uppercase and invalid/empty values fail startup. `--help` / `-h` works as a standalone argument. For example:

   ```bash
   java -jar build/release/jadx-mcp.jar --worker-heap 8g
   ```

   **The default `127.0.0.1` listener accepts connections only from the Server machine. Remote/LAN clients cannot connect to it; changing only `--port` does not enable remote access.** For deliberate access from a trusted LAN, explicitly bind all IPv4 interfaces (example port `9010`, not the default):

   ```bash
   java -jar build/release/jadx-mcp.jar --host=0.0.0.0 --port=9010 --log-level=INFO
   ```

   `0.0.0.0` is a **Server listening address**, not a destination for MCP clients or the GUI plugin. Use the Server's actual reachable IP for remote connections. Allow inbound **TCP 9010** from trusted clients in the host/network firewall. Public access through NAT additionally requires a separate router/public-port forwarding configuration; changing `--host` alone does **not** guarantee LAN or public reachability. There is no general authentication or built-in TLS: use only trusted networks with controlled firewall rules, or a protected tunnel; do not expose the service directly to the public Internet.[^runtime]

3. Check that the service is reachable (default local startup):

   ```bash
   curl http://127.0.0.1:8650/status
   ```

   For the remote `9010` example, run this from the client machine, replacing `<server-ip>` with the Server's reachable IP:

   ```bash
   curl --connect-timeout 5 --max-time 10 'http://<server-ip>:9010/status'
   ```

   `GET /status` returns a short plain-text snapshot of server state, sessions, initialized MCP client aliases and rolling business `tools/call` counts. It is diagnostic only and does **not** verify every tool or GUI/Worker readiness. `/mcp` is the MCP Streamable HTTP protocol endpoint, not an ordinary web page: verify initialization and `tools/list` with an MCP client.[^runtime]

APK paths passed to a headless session are resolved on the **Master host**, not on the MCP client. The Worker JVM is internal (loopback, per-process bearer token), not a public MCP endpoint.[^runtime]

### Logging

```bash
java -jar build/release/jadx-mcp.jar --log-level=DEBUG
```

| Selected level | Application output (includes higher severities) |
|---|---|
| `TRACE`, `DEBUG` | Request/backend start and completion, IDs, operation and elapsed time; lifecycle |
| `INFO` (default) | Component start/stop, MCP client initialized/closed, HEADLESS open/close, GUI registered/reconnected/disconnected/closed |
| `WARN` | Recoverable rejections, aborted loading and terminal-worker warnings; failures |
| `ERROR` | Failures only |
| `OFF` | No application/dependency log output |

The level takes effect before runtime construction and is inherited by each worker JVM. It replaces explicit Logback logger levels; the bundled console and rolling `logs/server.log` use the selected threshold, while `logs/error.log` additionally requires ERROR. Worker files stay under that session's generated directory (`session-<unique>/logs/`), not the Master's logs. **All third-party loggers, including JADX, MCP SDK, Ktor and Netty, are additionally restricted to WARN+ even at DEBUG/TRACE.** This conservative payload-safety filter means DEBUG is application diagnostics, **not all libraries' DEBUG**. The SDK's TRACE tool-argument dump is never enabled; its direct stdout startup banner is disabled. This setting does not reconfigure an already-running native GUI host.[^logging]

Logs omit tokens, payloads, source, selections, comment bodies and input paths. Application failures record codes/types rather than exception messages or stacks (also at DEBUG); dependency WARN/ERROR can include their own stacks, so redact shared excerpts. Help/invalid-CLI and JVM/native diagnostics are not ordinary Logback events. Poll/heartbeat and each HTTP request are not INFO lifecycle events. MCP SDK session IDs remain private; `/status` shows local aliases only. A client is listed after SDK initialization and removed on transport/session close (DELETE or shutdown). **Silent loss without DELETE has no default SDK idle expiry and cannot be reported as instant disconnect.** GUI registrations appear as sessions, not initialized MCP clients. [Usage](docs/usage/gui-and-cache.md#logging-and-connection-semantics); [packaged logging evidence](docs/testing/validation.md#logging-acceptance-historical).[^logging]

## Input-adjacent generated data

Headless generated data lives in `<input-filename>.jadx_mcp_cache/` beside the input, using the **complete filename**, including its extension. For `/work/demo.apk`, this is `/work/demo.apk.jadx_mcp_cache/`; JAR, DEX and other supported file inputs follow the same naming rule. Relative paths are made absolute and normalized; input symlinks resolve to the real target, so its name and parent determine the cache location. An unwritable or unsafe cache location fails explicitly (`PERSISTENCE_FAILED`).[^cache]

```text
/work/
├── app.apk
├── app.apk.jadx                  # Native user project, never owned/deleted here
└── app.apk.jadx_mcp_cache/
    ├── session-<unique-A>/
    │   ├── .jadx-mcp-owner
    │   ├── jvm-temp/
    │   ├── plugin-config/
    │   ├── plugin-cache/
    │   ├── temp/
    │   ├── output/               # Native output target; created when needed
    │   └── generated-aliases.jobf # Generated when needed, not user edits
    └── session-<unique-B>/       # Independent concurrent session
```

Every new session gets a fresh child directory and **does not reuse previous generated caches/aliases**. Normal close retains its data. `session_close(clear_cache=true)` stops that worker, then deletes only its marked, identity-checked child, without following symlinks or touching another session, the input, native `.jadx` projects or pre-existing root contents. The shared root remains, even when empty. Do not manually remove it while any contained session is active. Deleting an APK normally does **not** delete the sibling directory: there is no watcher or automatic linked deletion; users can remove the input and its cache directory together after closing sessions.

This layout controls headless worker-generated data. Native GUI projects and host caches are host-owned; the GUI plugin's rendered-response cache is in memory. Headless edits are in-memory (`persisted=false`), independently of generated aliases. Cleanup checks reject marker/path substitution, but portable Java path operations cannot guarantee atomic protection against a malicious same-user process racing filesystem mutations.[^cache]

## Connect an MCP client

Configure the client's **Streamable HTTP** transport with this URL when it runs on the Server machine using the default port (there is no Stdio command or OAuth service):

```text
http://127.0.0.1:8650/mcp
```

For the remote/LAN startup on port `9010` above, use `http://<server-ip>:9010/mcp`, replacing `<server-ip>` with the Server's actual reachable IP, **not `0.0.0.0`**. On a remote client, `127.0.0.1` refers to that client itself, not the Server.

The HTTP connection is unauthenticated and unencrypted by default. Use a trusted isolated network, firewall rules, or an SSH tunnel. For example, a client can forward a local port with `ssh -L 8650:127.0.0.1:8650 <server>` and use `http://127.0.0.1:8650/mcp`.

### System prompt for AI

> When using JADX MCP, if you encounter tool usage issues or have optimization suggestions, append reproduction steps, expected and actual results, and suggestions to `jadx_feedback.md` in the current workspace; do not include source code, tokens, or other sensitive information.

## Install the JADX GUI plugin

Extract the no-JRE ZIP and launch with your installed full JDK 21. For Windows
PowerShell, these settings affect only this shell and its children, not global
environment variables. Replace the example paths; launch the GUI JAR directly
to avoid another launcher silently choosing an embedded runtime:

```powershell
$env:JAVA_HOME = 'C:\Java\jdk-21'
& "$env:JAVA_HOME\bin\java.exe" -XshowSettings:properties -version
& "$env:JAVA_HOME\bin\java.exe" --list-modules
& "$env:JAVA_HOME\bin\java.exe" -jar 'C:\Tools\jadx-1.5.6\lib\jadx-1.5.6-all.jar'
# In a separate PowerShell, set JAVA_HOME there too before starting the Server:
$env:JAVA_HOME = 'C:\Java\jdk-21'
& "$env:JAVA_HOME\bin\java.exe" -jar '.\build\release\jadx-mcp.jar'
```

Check `java.home`/version and modules using this exact executable; use the same
explicit JDK executable for both launches. Workers inherit the Server's Java
installation. On macOS/Linux, likewise use `"$JAVA_HOME/bin/java"` explicitly.

1. Build the plugin:

   ```bash
    ./gradlew releaseArtifacts
   ```

2. In JADX-GUI 1.5.6, open **Preferences → Plugins → Install plugin**, use the local JAR chooser and select `build/release/jadx-mcp-gui-plugin.jar`, then install/restart. Use the host installer; do not guess an OS-specific plugin directory.
3. Start the Master first. When Master and GUI run on the same machine, the plugin defaults to `http://127.0.0.1:8650`.
   For the `9010` startup above, set the plugin's **IP** to `127.0.0.1` and **Port** to `9010` when GUI and Server share a machine; for a GUI on another machine, use the Server's actual reachable IP and `9010`, never `0.0.0.0`. The Server must also listen on a reachable interface and allow that connection through its firewall.
4. Open **Plugins → Jadx MCP**, the single plugin entry. Its non-modal window combines live counters and master configuration. Fill in separate **IP** and **Port** fields, e.g. `192.168.1.100` and `8650` (defaults: `127.0.0.1`, `8650`). Numeric IPv4 and IPv6 are supported; enter IPv6 without brackets, e.g. `::1`. Port must be 1–65535. Do not enter a hostname, scheme, path or URL: the plugin constructs HTTP internally. **Apply** saves to Java Preferences and switches the bridge on a background worker without closing the window; successful application shows **Applied!**. Repeated menu clicks focus the same window. Before the first saved setting, internal-origin JVM properties `jadx.mcp.url` and `jadx-mcp.master` are honored in that order, under the same restrictions.
5. Open an input/project and wait for its runtime binding, then confirm registration with `session_list` and use the GUI `session_id`. The bridge is enabled only after a successful project load/bind; it unregisters on unload and disconnected leases expire. Existing installations require plugin replacement/reload; building does not update running instances.[^gui]

### Backend capabilities

The following analysis tools support both backend types, though output details can differ: `meta_summary`, `meta_manifest`, `meta_class`, `decompile`, `resource`, `xref`, `search`, and class/method/field `rename`.

GUI-only MCP tools (they reject headless sessions): `fetch_current_class`, `get_selected_text`, `get_method_by_name`, `get_main_application_classes_code`, `get_package_tree`, `get_cache_stats`, `clear_cache`, `rename_package`, `rename_variable`, `add_comment`, `list_comments`, and `debug_get_stack_frames` / `debug_get_threads` / `debug_get_variables`. Debugger tools require an attached, suspended debugger; they read materialized UI data, not an atomic VM snapshot, and do not expand variables or evaluate expressions. Both backends accept `method_signature`, `slim`, and `with_line_numbers` for decompile (presentation options do not apply to `SMALI`). MCP `session_list` omits the internal `project_epoch` fence. The REST route `/api/get_main_application_classes_names` remains available; it is not an MCP tool. GUI `session_close` disables the MCP session, not the GUI application; `clear_cache=true` is headless-only.[^protocol]

### Edits, persistence and synchronization

- `state.applied` means an edit was applied in native memory; `state.persisted` separately acknowledges persistence. Never infer disk durability from a successful edit.
- **Headless rename currently returns `persisted=false`.** The Worker configures no project owner store, ignores user rename mappings and does not auto-save/reload user edits or a private sidecar. Closing/reopening a session does not restore those edits. Generated aliases/cache are not user project persistence.
- GUI edits use native JADX project ownership. `ASK` does not force a save or Save As; applied edits can remain unsaved. `ALWAYS` can save through the native host only when a project path exists and saving succeeds. A save failure may leave the in-memory edit applied with `persisted=false`; inspect both fields.
- GUI `clear_cache` clears the plugin runtime's response/source cache, not the host's disk cache. Headless generated-cache deletion is requested with `session_close(clear_cache=true)`, not the GUI-only `clear_cache` operation.[^persistence]

The same **Plugins → Jadx MCP** window shows connection state and **5/10/30-second business-request counts**, refreshed every second. Counts are unique received command IDs, not successful completions; poll/heartbeat/register traffic is excluded. Windows reset on connection-generation/project changes and expire to zero while idle. No request/operation details are collected by the counters. Network failures retry from 250 ms up to a 30 s backoff cap; established leases forgotten by a restarted Master re-register automatically. Non-retryable protocol errors remain disabled until the user selects **Reconnect**, which makes one fresh registration attempt for the active project; with no project, the action is unavailable and the bridge makes no HTTP request. Explicit `session_close`/CLOSED does not auto-reconnect. Unknown edit completions are never replayed. [Usage](docs/usage/gui-and-cache.md).

Each GUI instance receives its own session ID; select the intended session. See [validation results and limits](docs/testing/validation.md) and [reproduction commands](docs/testing/README.md). Attached-device debugger success, native save/reopen and exhaustive race/stress behavior are not verified.

## Session workflow

For headless analysis, call `session_create` with `type: "HEADLESS"` and an `apk_path`; save the returned `session_id` and pass it to each analysis tool. Use `session_list` to find registered sessions and `session_close` to close one. GUI sessions are created by plugin registration and cannot be created with `session_create`.

Recommended analysis sequence: `meta_summary` → targeted `search` → `xref` → `decompile` (prefer `target=METHOD`). Avoid enumerating every class in a large APK. Use `timeout_ms` and nested `budget.max_scan` / `budget.max_decompile` for expensive searches.

## MCP tools and capabilities

- Session management: `session_create`, `session_list`, `session_close`.
- Analysis: `meta_summary`, `meta_manifest`, `meta_class`, `decompile`, `resource`, `xref`, `search`, `rename`.
- GUI-only: `fetch_current_class`, `get_selected_text`, `get_method_by_name`, `get_main_application_classes_code`, `get_package_tree`, `get_cache_stats`, `clear_cache`, `rename_package`, `rename_variable`, `add_comment`, `list_comments`, `debug_get_threads`, `debug_get_stack_frames`, `debug_get_variables`.
- There are **25 MCP tools**. `get_main_application_classes_names` is not exposed through MCP; its REST operation remains available.
- All analysis tools require `session_id`. Errors are returned with `isError=true`; decompile output over 80,000 characters is marked as truncated.
- MCP tool text is a compact per-operation projection (for example, `session_list` is a root array), not the REST envelope; `session_create` returns the session summary directly.
- Enum values are exact **UPPERCASE**: search scopes `CLASS`, `STRING`, `METHOD_NAME`, `FIELD`, `CODE`, `COMMENT`; decompile targets `JAVA`, `SMALI`, `METHOD`, `MAIN_ACTIVITY`; symbol/component/backend enums also use uppercase.
- Class names accept JADX aliases such as `p000.` and `defpackage.`.
- Parameters/results use `snake_case`. Unknown fields, explicit JSON nulls, lowercase enums and `count=0` are rejected. Pagination is nested `page: {offset: 0, count: 50}`, with offset ≥ 0 and count **1..10000**.
- Whole-call `timeout_ms` includes queueing (default 120000, range 1..600000). MCP schemas hide REST caller controls `project_epoch` and `request_id`; REST accepts `project_epoch` as an optimistic fence and `request_id` as a non-empty explicit trace ID.
- `STRING` defaults to `string_mode=DEX_CONSTANTS` (DEX inputs only); request `SOURCE_TEXT` explicitly for source reads/non-DEX inputs. `budget.max_decompile` and `stats.decompiled` count unique source-access attempts including cache hits, not new native decompilations; zero disables all source reads, including cached source.[^protocol]

`GET /status` returns plain text with service state, session summaries, client aliases and rolling 10/30/60-second business-tool counts; it omits SDK IDs, GUI tokens and absolute session paths. REST uses **`POST /api/<operation>` for all 26 operations**, including `session_list` and the REST-only `/api/get_main_application_classes_names` (body `{}`). Send `Content-Type: application/json` and a plain parameter object, not a protocol frame. REST success remains `{version: 1, operation: "…", result: …}`; REST errors use `{error: {code, message, retryable}}`. MCP tool text is a compact operation-specific value, not the REST envelope; errors retain `isError=true`. GUI transport POST paths are `/gui/register`, `/gui/poll`, `/gui/complete`, `/gui/heartbeat`, `/gui/unregister`; these are not MCP tools.[^runtime]

Example REST calls (replace paths, session ID and symbols with real values):

```bash
curl -H 'Content-Type: application/json' http://127.0.0.1:8650/api/session_create -d '{"type":"HEADLESS","apk_path":"/absolute/path/app.apk","max_heap":"4g"}'
curl -H 'Content-Type: application/json' http://127.0.0.1:8650/api/search -d '{"session_id":"SESSION_ID","scope":"STRING","term":"https://","string_mode":"DEX_CONSTANTS","page":{"offset":0,"count":50},"budget":{"max_scan":500,"max_decompile":0},"timeout_ms":120000}'
curl -H 'Content-Type: application/json' http://127.0.0.1:8650/api/decompile -d '{"session_id":"SESSION_ID","target":"METHOD","class_name":"com.example.Main","method_name":"run","method_signature":"()V"}'
curl -H 'Content-Type: application/json' http://127.0.0.1:8650/api/rename -d '{"session_id":"SESSION_ID","target_type":"CLASS","target":{"class_name":"com.example.Main"},"new_name":"EntryPoint"}'
```

Configure worker heap with `--worker-heap` or session `max_heap`, and call deadlines with request `timeout_ms`.[^runtime]

## Development

```bash
./gradlew test --rerun-tasks
./gradlew releaseArtifacts
```

Code comments, KDoc, and log messages must be written in English. The README is maintained in English and Chinese: [README_zh.md](README_zh.md).

See the [documentation index](docs/README.md) for usage and testing topics.

Runtime entries are `com.yyang.jadxmcp.bootstrap.MasterMain`, `com.yyang.jadxmcp.worker.WorkerMain`, and GUI ServiceLoader entry `com.yyang.jadxmcp.runtime.JadxMcpGuiPlugin`. Modules are `protocol` (DTO/codec/ports), `analysis` (native analysis), `app` (Master/Worker/transports), and `gui-plugin` (borrowed host adapters).[^build]

[^build]: [Release task](build.gradle.kts), [application build](app/build.gradle.kts), [GUI build](gui-plugin/build.gradle.kts), [modules](settings.gradle.kts), [release workflow](.github/workflows/build.yml), [GUI service descriptor](gui-plugin/src/main/resources/META-INF/services/jadx.api.plugins.JadxPlugin). Current reproduction commands and acceptance boundaries: [testing](docs/testing/README.md).
[^runtime]: [CLI options/runtime](app/src/main/kotlin/com/yyang/jadxmcp/bootstrap/MasterRuntime.kt), [help entry](app/src/main/kotlin/com/yyang/jadxmcp/bootstrap/MasterMain.kt), [HTTP routes/security](app/src/main/kotlin/com/yyang/jadxmcp/api/MasterApi.kt), [GUI paths](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/GuiTransport.kt), [Worker transport](app/src/main/kotlin/com/yyang/jadxmcp/worker/WorkerServer.kt), [Worker defaults](app/src/main/kotlin/com/yyang/jadxmcp/process/WorkerProcess.kt). Recheck the CLI with `java -jar build/release/jadx-mcp.jar --help`.
[^protocol]: [26-operation registry](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/Operation.kt), [validation/defaults](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/Contract.kt), [analysis DTOs](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/AnalysisOperations.kt), [session DTOs](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/SessionOperations.kt), [rename DTOs](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/EditOperations.kt), [codec](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/ProtocolCodec.kt), [public schemas](app/src/main/kotlin/com/yyang/jadxmcp/api/CommandRegistry.kt). Recheck via MCP `tools/list` against a deliberately started Master; integration tests start bounded local test services, not a persistent deployment.
[^persistence]: [Worker assembly without store](app/src/main/kotlin/com/yyang/jadxmcp/backend/AnalysisWorkerEndpointFactory.kt), [in-memory project owner](analysis/src/main/kotlin/com/yyang/jadxmcp/analysis/project/JadxProjectService.kt), [GUI local snapshot/import](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/project/GuiProjectDataOwner.kt), [native project save acknowledgement](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/project/JadxGuiProjectAccess.kt), [native rename/save](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/project/JadxNativeRenameFacade.kt). Check mutation `state` separately from generated-cache existence.
[^gui]: [Plugin lifecycle/configuration](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/runtime/JadxMcpGuiPlugin.kt), [JADX 1.5.6 installer source](https://github.com/skylot/jadx/blob/v1.5.6/jadx-gui/src/main/java/jadx/gui/settings/ui/plugins/InstallPluginDialog.java), [GUI usage](docs/usage/gui-and-cache.md), [final isolated-host evidence](docs/testing/validation.md). Artifact existence alone is not interactive-install verification.
[^cache]: [Owned allocation/cleanup](app/src/main/kotlin/com/yyang/jadxmcp/worker/OwnedWorkerCache.kt), [worker launch/temp directory](app/src/main/kotlin/com/yyang/jadxmcp/process/WorkerBackend.kt), [JADX plugin/output/alias configuration](app/src/main/kotlin/com/yyang/jadxmcp/backend/AnalysisWorkerEndpointFactory.kt), [filesystem boundary tests](app/src/test/kotlin/com/yyang/jadxmcp/worker/OwnedWorkerCacheTest.kt), [real child-JVM isolation tests](app/src/test/kotlin/com/yyang/jadxmcp/backend/AnalysisWorkerIntegrationTest.kt).

[^logging]: [Level and safety filter](app/src/main/kotlin/com/yyang/jadxmcp/bootstrap/Logging.kt), [bundled Logback](app/src/main/resources/logback.xml), [MCP callbacks](app/src/main/kotlin/com/yyang/jadxmcp/api/McpAdapter.kt), [session lifecycle](app/src/main/kotlin/com/yyang/jadxmcp/session/SessionService.kt), [packaged driver](app/src/test/python/logging_acceptance.py), [fresh evidence](docs/testing/evidence/logging-validation-20261109.json).

## License

Apache-2.0
