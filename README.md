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

- A **full JDK 21** to build and run the Master/Workers, not a stripped JRE/custom runtime. Workers use `jdk.httpserver`; HTTP clients and XML processing also need `java.net.http` and `java.xml`. GUI requires desktop modules (`java.desktop`), and Java fixture tests require a compiler. `--help` alone does not verify these analysis/runtime dependencies.
- JADX GUI **1.5.6** for the GUI plugin; other versions are not verified. JADX core/GUI and Gson APIs are supplied by the host, not bundled as replacement host APIs.[^build]
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

   No cache-root configuration is needed or accepted. Options are `--host` (default `127.0.0.1`), `--port` (default `8650`), `--worker-heap` (default `2g`), and `--log-level` (default `INFO`). Both `--log-level=DEBUG` and `--log-level DEBUG` work; names are exact uppercase and invalid/empty values fail startup. `--help` / `-h` works as a standalone argument. For example:

   ```bash
   java -jar build/release/jadx-mcp.jar --worker-heap 8g
   ```

3. Check that the service is reachable:

   ```bash
   curl http://127.0.0.1:8650/health
   ```

Use `--host <trusted-LAN-address>` only if remote access is deliberately required. APK paths passed to a headless session are resolved on the **Master host**, not on the MCP client. The Worker JVM is internal (loopback, per-process bearer token), not a public MCP endpoint.[^runtime]

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

Logs omit tokens, payloads, source, selections, comment bodies and input paths. Application failures record codes/types rather than exception messages or stacks (also at DEBUG); dependency WARN/ERROR can include their own stacks, so redact shared excerpts. Help/invalid-CLI and JVM/native diagnostics are not ordinary Logback events. Poll/heartbeat and each HTTP request are not INFO lifecycle events. MCP `sdkSessionId` identifies a protocol session, not an authenticated person or necessarily the HTTP `Mcp-Session-Id`. A client is logged once after SDK initialization and exits once on transport/session close (DELETE or shutdown). **Silent loss without DELETE has no default SDK idle expiry and cannot be reported as instant disconnect.** GUI registration is separately labelled, not counted as an external MCP client. [Usage](docs/usage/gui-and-cache.md#logging-and-connection-semantics); [fresh packaged evidence](docs/testing/validation.md#logging-acceptance-latest).[^logging]

## Input-adjacent generated data

Headless sessions use the fixed suffix `.jadx_mcp_cache`, appended to the **complete input filename**, including its extension. For `/work/demo.apk`, generated data lives in `/work/demo.apk.jadx_mcp_cache/`; JAR, DEX and other supported file inputs follow the same naming rule. The former `_jadx_cache` directories are not read, migrated or deleted. Relative paths are made absolute and normalized; input symlinks resolve to the real target, so its name and parent determine the cache location. An unwritable or unsafe cache location fails explicitly (`PERSISTENCE_FAILED`); there is no central/temp-directory fallback.[^cache]

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

This layout controls headless worker-generated data, **not all JADX GUI data**. Native GUI projects and host caches remain host-owned and are not migrated. The GUI plugin's own rendered-response cache is in memory. Generated aliases do not add missing headless project/edit persistence. Cleanup checks reject marker/path substitution, but portable Java path operations cannot guarantee atomic protection against a malicious same-user process racing filesystem mutations.[^cache]

## Connect an MCP client

Configure the client's remote **Streamable HTTP** transport with this URL (there is no Stdio command or OAuth service):

```text
http://127.0.0.1:8650/mcp
```

The HTTP connection is unauthenticated and unencrypted by default. Use a trusted isolated network, firewall rules, or an SSH tunnel. For example, a client can forward a local port with `ssh -L 8650:127.0.0.1:8650 <server>` and use `http://127.0.0.1:8650/mcp`.

## Install the JADX GUI plugin

1. Build the plugin:

   ```bash
    ./gradlew releaseArtifacts
   ```

2. In JADX-GUI 1.5.6, open **Preferences → Plugins → Install plugin**, use the local JAR chooser and select `build/release/jadx-mcp-gui-plugin.jar`, then install/restart. Use the host installer; do not guess an OS-specific plugin directory.
3. Start the Master first. When Master and GUI run on the same machine, the plugin defaults to `http://127.0.0.1:8650`.
4. Open **Plugins → Jadx MCP**, the single plugin entry. Its non-modal window combines live counters and master configuration. Fill in separate **IP** and **Port** fields, e.g. `192.168.1.100` and `8650` (defaults: `127.0.0.1`, `8650`). Numeric IPv4 and IPv6 are supported; enter IPv6 without brackets, e.g. `::1`. Port must be 1–65535. Do not enter a hostname, scheme, path or URL: the plugin constructs HTTP internally. **Apply** saves to Java Preferences and switches the bridge on a background worker without closing the window. There is no **Cancel** button; successful application shows **Applied!**. Both behaviors passed the final isolated GUI checks. Repeated menu clicks focus the same window. Existing saved HTTP numeric-IP origins are split into fields; saved hostname/HTTPS origins require explicit re-entry. Before the first saved setting, internal-origin JVM properties `jadx.mcp.url` and `jadx-mcp.master` are honored in that order, under the same restrictions.
5. Open an input/project and wait for its runtime binding, then confirm registration with `session_list` and use the GUI `session_id`. The bridge is enabled only after a successful project load/bind; it unregisters on unload and disconnected leases expire. Existing installations require plugin replacement/reload; building does not update running instances.[^gui]

### Backend capabilities

The following analysis tools support both backend types, though output details can differ: `meta_summary`, `meta_manifest`, `meta_class`, `decompile`, `resource`, `xref`, `search`, and class/method/field `rename`.

GUI-only tools (they reject headless sessions): `fetch_current_class`, `get_selected_text`, `get_method_by_name`, `get_main_application_classes_names`, `get_main_application_classes_code`, `get_package_tree`, `get_cache_stats`, `clear_cache`, `rename_package`, `rename_variable`, `add_comment`, `list_comments`, and `debug_get_stack_frames` / `debug_get_threads` / `debug_get_variables`. Debugger tools require an attached, suspended debugger; they read materialized UI data, not an atomic VM snapshot, and do not expand variables or evaluate expressions. Both backends accept `method_signature`, `slim`, and `with_line_numbers` for decompile (presentation options do not apply to `SMALI`). Inspect `session_get` capabilities for input-specific support. GUI `session_close` disables the MCP session, not the GUI application; `clear_cache=true` is headless-only.[^protocol]

### Edits, persistence and synchronization

- `state.applied` means an edit was applied in native memory; `state.persisted` separately acknowledges persistence. Never infer disk durability from a successful edit.
- **Headless rename currently returns `persisted=false`.** The Worker configures no project owner store, ignores user rename mappings and does not auto-save/reload user edits or a private sidecar. Closing/reopening a session does not restore those edits. Generated aliases/cache are not user project persistence.
- GUI edits use native JADX project ownership. `ASK` does not force a save or Save As; applied edits can remain unsaved. `ALWAYS` can save through the native host only when a project path exists and saving succeeds. A save failure may leave the in-memory edit applied with `persisted=false`; inspect both fields.
- There is **no public `sync_project_data` operation**, automatic cross-session SHA synchronization, or configured headless sidecar persistence. Local `ProjectDataPort.snapshot/importData` primitives validate SHA-256 input identities and revisions; their existence is not a public MCP/REST synchronization feature.
- GUI `clear_cache` clears the plugin runtime's response/source cache, not the host's disk cache. Headless generated-cache deletion is requested with `session_close(clear_cache=true)`, not the GUI-only `clear_cache` operation.[^persistence]

The same **Plugins → Jadx MCP** window shows connection state and **5/10/30-second business-request counts**, refreshed every second. Counts are unique received command IDs, not successful completions; poll/heartbeat/register traffic is excluded. Windows reset on connection-generation/project changes and expire to zero while idle. No request/operation details are collected by the counters. Network failures retry from 250 ms up to a 30 s backoff cap; established leases forgotten by a restarted Master re-register automatically. Explicit `session_close`/CLOSED does not auto-reconnect; authentication/lease conflicts remain terminal. Unknown edit completions are never replayed. [Usage](docs/usage/gui-and-cache.md).

Each GUI instance receives its own session ID; select the intended session for GUI-only operations. Latest logging acceptance on 2026-11-09 passed **239 tests** (protocol 13, analysis 71, app 61, GUI 94; zero failures/errors/skips), both rebuilt release packages and **7 isolated packaged logging configurations**. Earlier native-GUI acceptance separately passed the unique real GUI menu, **44/44 sample.jar** and **54/54 same-APK GUI+HEADLESS** checks; these scenarios were not rerun for the logging change. The historical APK run covered launcher agreement, edit restoration and reconnect without replay. Historical immediate native rename/readback passed **96/96 sample.jar** and **40/40 APK** checks without mutation-to-read sleeps/retries: instability was not reproduced, **not proved impossible**. All owned isolated processes were reaped. Building did not replace/restart existing Master/GUI instances or root runtime JARs. [Evidence, hashes and limits](docs/testing/validation.md); [reproduction commands](docs/testing/README.md).

Limits: no paused-device debugger success path or exhaustive combinations of all 27 tools; native save/reopen and broader race/stress behavior are not proved. The earlier MCP tool wrapper did not expose raw `isError`; final direct HTTP clients checked errors, which does not remove that wrapper limitation. GitHub Actions received static checks only: no PyYAML/full YAML parsing and no remote CI run. No commit or push was performed.

### Real APK headless smoke evidence

On 2026-11-09 a copied, authorized APK passed a bounded packaged-HTTP smoke: **60 PASS, 4 NO_OBJECT, 0 FAIL**, covering metadata/manifest, search/xref, Java/method/Smali, resource reads, in-memory rename, two-worker isolation, deadlines and cleanup. A Smali resource ID resolved to a name, not a string value. This historical HTTP-only run did not execute the APK or exercise GUI/MCP/device debugging and is not exhaustive validation. [Evidence and limitations](docs/testing/validation.md).

## Session workflow

For headless analysis, call `session_create` with `type: "HEADLESS"` and an `apk_path`; save the returned `result.session_id` and pass it to each analysis tool. Use `session_list` to find registered GUI sessions, `session_get` to inspect a session, and `session_close` to close it. GUI sessions are created by plugin registration and cannot be created with `session_create`.

Recommended analysis sequence: `meta_summary` → targeted `search` → `xref` → `decompile` (prefer `target=METHOD`). Avoid enumerating every class in a large APK. Use `timeout_ms` and nested `budget.max_scan` / `budget.max_decompile` for expensive searches.

## MCP tools and capabilities

- Session management: `session_create`, `session_list`, `session_get`, `session_close`.
- Analysis: `meta_summary`, `meta_manifest`, `meta_class`, `decompile`, `resource`, `xref`, `search`, `rename`.
- Together with the 15 GUI-only operations above, this is **27 operations** in the shared REST/MCP registry; there is no legacy compatibility entry.
- All analysis tools require `session_id`. Errors are returned with `isError=true`; decompile output over 80,000 characters is marked as truncated.
- Enum values are exact **UPPERCASE**: search scopes `CLASS`, `STRING`, `METHOD_NAME`, `FIELD`, `CODE`, `COMMENT`; decompile targets `JAVA`, `SMALI`, `METHOD`, `MAIN_ACTIVITY`; symbol/component/backend enums also use uppercase.
- Class names accept JADX aliases such as `p000.` and `defpackage.`.
- Parameters/results use `snake_case`. Unknown fields, explicit JSON nulls, old `apk_id` aliases, lowercase enums and `count=0` are rejected. Pagination is nested `page: {offset: 0, count: 50}`, with offset ≥ 0 and count **1..10000**.
- Whole-call `timeout_ms` includes queueing (default 120000, range 1..600000). Optional `project_epoch` is an optimistic fence, not silently replaced when supplied.
- `STRING` defaults to `string_mode=DEX_CONSTANTS` (DEX inputs only); request `SOURCE_TEXT` explicitly for source reads/non-DEX inputs. `budget.max_decompile` and `stats.decompiled` count unique source-access attempts including cache hits, not new native decompilations; zero disables all source reads, including cached source.[^protocol]

REST uses `GET /health` and **`POST /api/<operation>` for all 27 operations**, including `session_list` (body `{}`). Send `Content-Type: application/json` and a plain parameter object, not a protocol frame. Success is `{version: 1, operation: "…", result: …}`; REST errors use `{error: {code, message, retryable}}`. GUI transport POST paths are `/gui/register`, `/gui/poll`, `/gui/complete`, `/gui/heartbeat`, `/gui/unregister`; these are not MCP tools. Old `/session/*` routes are gone.[^runtime]

Example REST calls (replace paths, session ID and symbols with real values):

```bash
curl -H 'Content-Type: application/json' http://127.0.0.1:8650/api/session_create -d '{"type":"HEADLESS","apk_path":"/absolute/path/app.apk","max_heap":"4g"}'
curl -H 'Content-Type: application/json' http://127.0.0.1:8650/api/search -d '{"session_id":"SESSION_ID","scope":"STRING","term":"https://","string_mode":"DEX_CONSTANTS","page":{"offset":0,"count":50},"budget":{"max_scan":500,"max_decompile":0},"timeout_ms":120000}'
curl -H 'Content-Type: application/json' http://127.0.0.1:8650/api/decompile -d '{"session_id":"SESSION_ID","target":"METHOD","class_name":"com.example.Main","method_name":"run","method_signature":"()V"}'
curl -H 'Content-Type: application/json' http://127.0.0.1:8650/api/rename -d '{"session_id":"SESSION_ID","target_type":"CLASS","target":{"class_name":"com.example.Main"},"new_name":"EntryPoint"}'
```

The old `JADX_WORKER_XMX`, `JADX_WORKER_TIMEOUT_SEC` and `JADX_DECOMPILE_TIMEOUT` deployment variables are not read by the new Master. Use `--worker-heap`, session `max_heap`, and request `timeout_ms` as applicable.[^runtime]

## Development

```bash
./gradlew test --rerun-tasks
./gradlew releaseArtifacts
```

Code comments, KDoc, and log messages must be written in English. The README is maintained in English and Chinese: [README_zh.md](README_zh.md).

See the [documentation index](docs/README.md) for usage and testing topics.

The only runtime entries are `com.yyang.jadxmcp.bootstrap.MasterMain`, `com.yyang.jadxmcp.worker.WorkerMain`, and GUI ServiceLoader entry `com.yyang.jadxmcp.runtime.JadxMcpGuiPlugin`. Modules are `protocol` (DTO/codec/ports), `analysis` (native analysis), `app` (Master/Worker/transports), and `gui-plugin` (borrowed host adapters). The old `com.yyang.jadx_server` implementation, `runLegacy`, and compatibility paths were removed; do not use them. The development `:app:runRewrite` task invokes the same Master.[^build]

[^build]: [Release task](build.gradle.kts), [application build](app/build.gradle.kts), [GUI build](gui-plugin/build.gradle.kts), [modules](settings.gradle.kts), [release workflow](.github/workflows/build.yml), [GUI service descriptor](gui-plugin/src/main/resources/META-INF/services/jadx.api.plugins.JadxPlugin). Current reproduction commands and acceptance boundaries: [testing](docs/testing/README.md).
[^runtime]: [CLI options/runtime](app/src/main/kotlin/com/yyang/jadxmcp/bootstrap/MasterRuntime.kt), [help entry](app/src/main/kotlin/com/yyang/jadxmcp/bootstrap/MasterMain.kt), [HTTP routes/security](app/src/main/kotlin/com/yyang/jadxmcp/api/MasterApi.kt), [GUI paths](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/GuiTransport.kt), [Worker transport](app/src/main/kotlin/com/yyang/jadxmcp/worker/WorkerServer.kt), [Worker defaults](app/src/main/kotlin/com/yyang/jadxmcp/process/WorkerProcess.kt). Recheck the CLI with `java -jar build/release/jadx-mcp.jar --help`.
[^protocol]: [27-operation registry](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/Operation.kt), [validation/defaults](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/Contract.kt), [analysis DTOs](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/AnalysisOperations.kt), [session DTOs](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/SessionOperations.kt), [rename DTOs](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/EditOperations.kt), [codec](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/ProtocolCodec.kt), [public schemas](app/src/main/kotlin/com/yyang/jadxmcp/api/CommandRegistry.kt). Recheck via MCP `tools/list` against a deliberately started Master; integration tests start bounded local test services, not a persistent deployment.
[^persistence]: [Worker assembly without store](app/src/main/kotlin/com/yyang/jadxmcp/backend/AnalysisWorkerEndpointFactory.kt), [in-memory project owner](analysis/src/main/kotlin/com/yyang/jadxmcp/analysis/project/JadxProjectService.kt), [GUI local snapshot/import](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/project/GuiProjectDataOwner.kt), [native project save acknowledgement](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/project/JadxGuiProjectAccess.kt), [native rename/save](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/project/JadxNativeRenameFacade.kt). Check mutation `state` separately from generated-cache existence.
[^gui]: [Plugin lifecycle/configuration](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/runtime/JadxMcpGuiPlugin.kt), [JADX 1.5.6 installer source](https://github.com/skylot/jadx/blob/v1.5.6/jadx-gui/src/main/java/jadx/gui/settings/ui/plugins/InstallPluginDialog.java), [GUI usage](docs/usage/gui-and-cache.md), [final isolated-host evidence](docs/testing/validation.md). Artifact existence alone is not interactive-install verification.
[^cache]: [Owned allocation/cleanup](app/src/main/kotlin/com/yyang/jadxmcp/worker/OwnedWorkerCache.kt), [worker launch/temp directory](app/src/main/kotlin/com/yyang/jadxmcp/process/WorkerBackend.kt), [JADX plugin/output/alias configuration](app/src/main/kotlin/com/yyang/jadxmcp/backend/AnalysisWorkerEndpointFactory.kt), [filesystem boundary tests](app/src/test/kotlin/com/yyang/jadxmcp/worker/OwnedWorkerCacheTest.kt), [real child-JVM isolation tests](app/src/test/kotlin/com/yyang/jadxmcp/backend/AnalysisWorkerIntegrationTest.kt). Historical suffix evidence: `git show 3f4833d` contains `File(parent, "${targetFile.name}_jadx_cache")`.

[^logging]: [Level and safety filter](app/src/main/kotlin/com/yyang/jadxmcp/bootstrap/Logging.kt), [bundled Logback](app/src/main/resources/logback.xml), [MCP callbacks](app/src/main/kotlin/com/yyang/jadxmcp/api/McpAdapter.kt), [session lifecycle](app/src/main/kotlin/com/yyang/jadxmcp/session/SessionService.kt), [packaged driver](app/src/test/python/logging_acceptance.py), [fresh evidence](docs/testing/evidence/logging-validation-20261109.json).

## License

Apache-2.0
