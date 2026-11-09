# Contributing to jadx-mcp

## Reports and pull requests

- Include Java/OS/JADX versions, reproduction commands, expected/actual behavior
  and a **redacted, bounded** log excerpt. Do not publish private APKs, credentials,
  personal paths or full decompiled responses.
- Keep Kotlin modular and thread-safe. Use English for comments, KDoc and logs;
  keep [English](README.md) and [Chinese](README_zh.md) README instructions aligned.
- Put implementation rationale beside the relevant code and regression tests,
  not in a centralized design-decision document. Public `docs/` is organized by
  usage/testing topic. Use the actual protocol DTOs for examples.
- Submit a focused PR with clear changes and verification/limitations. Never
  discard unfamiliar untracked source or fixtures as cleanup.

## Verification

Use a full JDK 21 and one Gradle build at a time:

```bash
./gradlew --no-daemon --no-parallel --max-workers=1 test --rerun-tasks releaseArtifacts
java -jar build/release/jadx-mcp.jar --help
unzip -p build/release/jadx-mcp.jar META-INF/MANIFEST.MF
unzip -p build/release/jadx-mcp-gui-plugin.jar META-INF/services/jadx.api.plugins.JadxPlugin
```

Use `--offline` only if dependencies already exist. Long-running commands should
have an outer timeout and run in the background in an agent harness. Do not run
concurrent final acceptance jobs. [Isolated smoke instructions](docs/testing/README.md)
cover the five smoke support files, combined GUI settings/reconnect/uncertain
completion, APK HTTP and deterministic formal method-identity tests.
Distribute only the two Shadow packages under `build/release/`; plain/module JARs
are internal. JADX host APIs are pinned to 1.5.6.[^build]

Record source revision/dirty state, JDK, command, exit status, module test counts,
artifact hashes and excluded cases. [Validation records](docs/testing/validation.md)
separate full-suite, packaged logging, native-GUI and rename/readback results.
Reproduce logging checks with
`python3.12 -B app/src/test/python/logging_acceptance.py` after packaging.
Revalidate source changes rather than carrying recorded counts forward.

Debugger success paths and all 26 tools' parameter combinations are not fully
covered. The earlier MCP wrapper's raw `isError` visibility limit remains distinct
from final direct-client error checks. Actions was checked statically only:
no PyYAML/full YAML semantic parse, no remote CI. No commit/push was performed.
Documentation cleanup and package/help inspection alone are not runtime proof.

## Runtime boundaries to preserve

- Keep INFO to lifecycle, request timing at DEBUG, recoverable rejection at WARN
  and failure at ERROR. Never log payload/token/source/selection/comment content
  or exception messages with private paths. The current dependency WARN+ safety
  filter is intentional: do not advertise all-library DEBUG or enable SDK TRACE
  argument dumps. Preserve worker-level inheritance and per-session file isolation.
  MCP session close is not instant TCP-loss detection; SDK 0.14 has no default
  silent-session idle expiry. See the [logging contract](docs/usage/gui-and-cache.md#logging-and-connection-semantics).
- Headless generated data belongs to a fresh
  `<complete-input-name>.jadx_mcp_cache/session-<unique>/` child. Stop the worker
  before guarded cleanup; preserve the input, native `.jadx`, shared root and
   other sessions. Unwritable or unsafe locations fail explicitly. Portable checks do not guarantee atomic safety
  against malicious same-user filesystem races.[^cache]
- Native GUI close during in-flight native analysis still requires host-level
  stress verification. Session admission/result fences do not prove the native
  decompiler cannot close underneath a borrowed operation; a deadline does not
  reliably interrupt native work.[^lifecycle]
- `MAIN_ACTIVITY` uses manifest metadata/resolution; verify
  resource-valued flags, aliases and launcher selection on new inputs. Recorded
  same-APK GUI/HEADLESS launcher agreement passed, not every input permutation.[^manifest]
- Headless rename remains in-memory (`persisted=false`). GUI ASK does not force a
  save; ALWAYS requires a project path and successful native save. Verify
  `applied` and `persisted` separately.[^persistence]
- Device-debugger positive paths, native save/reopen, asynchronous rename/editor
  refresh and exhaustive APK coverage must be explicitly recorded, not inferred
  from fixture tests or help output.

## Evidence and cleanup

Keep minimal redacted JSON with outcomes, counts, timings, errors and hashes;
avoid repeated build logs or full source-bearing wire captures. Generated smoke
outputs are under `build/smoke/`, not persistent reproduction prerequisites.
Never delete a running JAR, open log or native `.jadx` project. Keep current
acceptance output until its owner has archived the required evidence.

[^build]: [Root release task](build.gradle.kts), [application](app/build.gradle.kts), [GUI smoke tasks](gui-plugin/build.gradle.kts), [CI](.github/workflows/build.yml).
[^cache]: [Owned cache](app/src/main/kotlin/com/yyang/jadxmcp/worker/OwnedWorkerCache.kt), [boundary tests](app/src/test/kotlin/com/yyang/jadxmcp/worker/OwnedWorkerCacheTest.kt), [real workers](app/src/test/kotlin/com/yyang/jadxmcp/backend/AnalysisWorkerIntegrationTest.kt).
[^lifecycle]: [Load hook](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/runtime/JadxGuiLoadHook.kt), [runtime](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/runtime/GuiProjectRuntime.kt), [workspace leases](analysis/src/main/kotlin/com/yyang/jadxmcp/analysis/workspace/JadxWorkspace.kt).
[^manifest]: [Method/source service](analysis/src/main/kotlin/com/yyang/jadxmcp/analysis/source/JadxSourceService.kt), [manifest service](analysis/src/main/kotlin/com/yyang/jadxmcp/analysis/metadata/ManifestService.kt), [resource flags](analysis/src/main/kotlin/com/yyang/jadxmcp/analysis/resource/NativeBooleanResolver.kt).
[^persistence]: [Operation list](protocol/src/main/kotlin/com/yyang/jadxmcp/protocol/Operation.kt), [Worker assembly](app/src/main/kotlin/com/yyang/jadxmcp/backend/AnalysisWorkerEndpointFactory.kt), [project service](analysis/src/main/kotlin/com/yyang/jadxmcp/analysis/project/JadxProjectService.kt), [GUI owner](gui-plugin/src/main/kotlin/com/yyang/jadxmcp/project/GuiProjectDataOwner.kt).

## License

Contributions are licensed under [Apache-2.0](LICENSE).
