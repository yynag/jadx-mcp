# GUI connection and input-cache usage

## GUI connection

With JADX GUI 1.5.6, install `build/release/jadx-mcp-gui-plugin.jar` through
**Preferences → Plugins → Install plugin**, then restart/reload the plugin.
Start the Master first; the default origin is `http://127.0.0.1:8650`.

**Plugins → Jadx MCP** opens one non-modal window with connection status,
5/10/30-second request counts, and separate numeric **IP** and **Port** fields.
IPv6 is entered without brackets; ports are 1–65535. Do not enter a scheme,
hostname, path or complete URL. **Apply** saves and reconnects in the background
without closing the window. The current UI change removes **Cancel** and uses
**Applied!** as its success acknowledgement. **Cancel removal, Applied! and the
single-window behavior passed the final isolated GUI acceptance.**[^gui]

Counts are unique received business command IDs, not successful completions;
registration/poll/heartbeat traffic is excluded. They refresh each second and
reset on connection-generation/project changes. A lost edit completion is not
replayed. Transient failures back off; an explicitly closed session does not
automatically reappear. Existing installations do not update merely because
new artifacts were built.[^gui]

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
`/work/demo.apk.jadx_mcp_cache/session-<unique>/`. Each session gets a fresh child;
there is no shared cache-root option or fallback. JVM temporary files, plugin
configuration/cache/temp, output and generated aliases stay in that child.
Relative paths are normalized and input symlinks resolve to the real target.
Unwritable or unsafe locations fail explicitly.[^cache]

Normal close keeps generated data. `session_close(clear_cache=true)` stops the
worker and checks the marked child's identity before deleting that child only.
It preserves the input, other sessions, shared root and **all native `.jadx`
projects**. Do not manually delete an active session directory. Deleting the
input does not automatically remove sibling generated data. Existing caches
under the retired suffix are not read, migrated or deleted.[^cache]

This ownership rule does not cover native GUI host caches/projects. GUI response
caches are in memory. Headless rename is in-memory (`persisted=false`); GUI saves
follow native ASK/ALWAYS ownership. Inspect `state.applied` and `state.persisted`
separately. No public cross-session synchronization operation exists.

Portable guarded path deletion is not atomic protection against a malicious
same-user process racing filesystem changes. Native GUI close during in-flight
analysis and native save/reopen still require dedicated host validation.

[^gui]: [Plugin](../../gui-plugin/src/main/kotlin/com/yyang/jadxmcp/runtime/JadxMcpGuiPlugin.kt), [window](../../gui-plugin/src/main/kotlin/com/yyang/jadxmcp/runtime/GuiBridgeStatusWindow.kt), [settings](../../gui-plugin/src/main/kotlin/com/yyang/jadxmcp/runtime/MasterConnectionSettings.kt), [final evidence and limits](../testing/validation.md).
[^cache]: [Owned cache](../../app/src/main/kotlin/com/yyang/jadxmcp/worker/OwnedWorkerCache.kt), [boundary tests](../../app/src/test/kotlin/com/yyang/jadxmcp/worker/OwnedWorkerCacheTest.kt), [real worker tests](../../app/src/test/kotlin/com/yyang/jadxmcp/backend/AnalysisWorkerIntegrationTest.kt).
[^logging]: [Logging policy](../../app/src/main/kotlin/com/yyang/jadxmcp/bootstrap/Logging.kt), [lifecycle](../../app/src/main/kotlin/com/yyang/jadxmcp/session/SessionService.kt), [SDK callbacks](../../app/src/main/kotlin/com/yyang/jadxmcp/api/McpAdapter.kt), [packaged acceptance](../testing/validation.md#logging-acceptance-latest).
