# jadx-mcp

[中文](README_zh.md)

`jadx-mcp` connects AI clients and other tools to JADX through a REST API and MCP. A Master service manages isolated headless analysis sessions and connects to open JADX GUI projects through a plugin.

## Features

- Inspect metadata, manifest and resources; search, find references and decompile code.
- Create headless sessions for APK, DEX, JAR and other JADX-supported inputs, or use a registered GUI session.
- Rename symbols; GUI sessions also provide selection, comments and debugger inspection. Some operations are backend-specific; discover MCP tools and their schemas with `tools/list`.

## Requirements and downloads

- Use a **full JDK 21**, including its runtime, to build and run the Master, its workers and JADX GUI. Ensure the actual processes use that JDK; a trimmed runtime can lack required modules.
- For GUI integration use the **JADX 1.5.6 ZIP without an embedded JRE** (`jadx-1.5.6.zip`) and launch it with that JDK. Other JADX versions are not verified.
- Download both `jadx-mcp.jar` (Master and workers) and `jadx-mcp-gui-plugin.jar` from [GitHub Releases](https://github.com/yynag/jadx-mcp/releases), or build from source. Use the release checksums when provided.

From the repository root, with JDK 21 selected:

```bash
./gradlew releaseArtifacts
```

The Gradle Wrapper builds `build/release/jadx-mcp.jar` and `build/release/jadx-mcp-gui-plugin.jar`; no separate Gradle installation is required. The paths below assume a source build; substitute the downloaded JAR path when using a release.

## Install and run the Master service

Start the Master, then run the status check in another terminal:

```bash
java -jar build/release/jadx-mcp.jar
curl http://127.0.0.1:8650/status
```

The default listener is `127.0.0.1:8650`, accessible only on the server machine. `/status` is a plain-text diagnostic, not a complete readiness check. Run `java -jar build/release/jadx-mcp.jar --help` for options including `--host`, `--port`, `--worker-heap` (default `2g`) and `--log-level`.

### Minimal REST workflow

Replace the path with an existing input on the **Master host**. The process must be able to read it and write beside it.

```bash
curl -X POST http://127.0.0.1:8650/api/session_create \
  -H 'Content-Type: application/json' \
  -d '{"type":"HEADLESS","apk_path":"/absolute/path/app.apk"}'
```

Copy `result.session_id` from the response and substitute it below:

```bash
curl -X POST http://127.0.0.1:8650/api/meta_summary \
  -H 'Content-Type: application/json' -d '{"session_id":"SESSION_ID"}'
curl -X POST http://127.0.0.1:8650/api/session_close \
  -H 'Content-Type: application/json' -d '{"session_id":"SESSION_ID"}'
```

REST operations use `POST /api/<operation>` with a JSON parameter object; successful responses contain a `result` envelope.

### Minimal MCP workflow

Connect an MCP client using **Streamable HTTP** at `http://127.0.0.1:8650/mcp`. MCP Stdio is not supported. Let the client initialize the protocol and list tools, then call:

1. `session_create` with `{"type":"HEADLESS","apk_path":"/absolute/path/app.apk"}`.
2. `meta_summary` with `{"session_id":"SESSION_ID"}`, substituting the ID returned by `session_create`.
3. `session_close` with `{"session_id":"SESSION_ID"}` when finished.

Alternatively, call `session_list` with `{}` and select a registered GUI session ID. MCP tool text is a compact result, not the REST envelope; inspect `isError` on failures. Analysis operations require a `session_id`.

### MCP tools and capabilities

See the [project overview](PROJECT.md#operations) for the operation list and backend restrictions. Use `tools/list` for the current schemas; parameter names use `snake_case` and enum values are uppercase.

## Install the JADX GUI plugin

For example, on Windows PowerShell select your full JDK and launch the no-JRE JADX distribution with it (replace the paths):

```powershell
$env:JAVA_HOME = 'C:\Java\jdk-21'
& "$env:JAVA_HOME\bin\java.exe" -jar 'C:\Tools\jadx-1.5.6\lib\jadx-1.5.6-all.jar'
```

Use the same JDK explicitly for the Master in a separate shell; workers inherit its Java installation.

1. Launch JADX GUI 1.5.6 with the full JDK 21. In **Preferences → Plugins → Install plugin**, choose `jadx-mcp-gui-plugin.jar` using the local JAR chooser, install it and restart/reload the plugin.
2. Start the Master, then open **Plugins → Jadx MCP**. The default **IP** and **Port** are `127.0.0.1` and `8650`. For another server use its reachable numeric IP and port; do not enter a URL or hostname. Enter IPv6 without brackets. Click **Apply**.
3. Open an input or project in JADX. The plugin connects outward and registers after the project is bound. Use `session_list` to find its GUI `session_id`. **Applied!** confirms local settings, not connectivity; check the connection status separately.

## Security, permissions and data

> **The Master has no general authentication or built-in TLS.** CORS is permissive and MCP DNS-rebinding protection is disabled. Loopback is not an authentication boundary. Only use trusted clients, networks and inputs; do not expose the service directly to the public Internet. Prefer an SSH tunnel for remote use, or explicitly configure a reachable `--host` with restrictive firewall/access controls. `0.0.0.0` is a listening address, not a client destination.

- Analyze only inputs you are authorized to access. Clients can read analysis data and invoke edits with the permissions of the server or GUI user. Worker process isolation is not a sandbox. Review AI-generated actions and treat analyzed content as untrusted instructions.
- Headless sessions create `<input-filename>.jadx_mcp_cache/session-<unique>/` beside the resolved input. Each session gets a fresh child; normal close retains it. `session_close` with `clear_cache: true` deletes only that session's guarded child, not the input or native `.jadx` project. Do not manually remove active session data.
- Generated cache is **not** edit persistence: headless renames are in memory and return `persisted=false`. GUI edits follow native JADX project/save behavior; check both `state.applied` and `state.persisted`. GUI response cache is in memory and separate from host project data and disk caches.

## Tests and documentation

Run the tests from the repository root with JDK 21:

```bash
./gradlew --no-daemon --no-parallel --max-workers=1 test --rerun-tasks
```

- [Documentation index](docs/README.md), [GUI connection, cache and persistence](docs/usage/gui-and-cache.md)
- [Test and smoke commands](docs/testing/README.md), [validation scope and limits](docs/testing/validation.md)
- [Project overview and architecture](PROJECT.md)

## License

[Apache-2.0](LICENSE)
