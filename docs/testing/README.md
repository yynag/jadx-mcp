# Tests and isolated smoke runs

Run from the repository root with a full JDK 21. Use one build at a time; in an
agent harness, dispatch long-running commands in the background with an outer
timeout. These are reproduction commands. Historical full-suite acceptance and
the newer five-file smoke revalidation are distinguished in [validation](validation.md).

```bash
./gradlew --no-daemon --no-parallel --max-workers=1 test --rerun-tasks releaseArtifacts
java -jar build/release/jadx-mcp.jar --help
unzip -p build/release/jadx-mcp.jar META-INF/MANIFEST.MF
unzip -p build/release/jadx-mcp-gui-plugin.jar META-INF/services/jadx.api.plugins.JadxPlugin
```

Use `--offline` only when all dependencies already exist. Record revision,
working-tree state, JDK, command, exit status, module test counts, artifact hashes
and explicit exclusions. `--help`, packaging and a historical pass do not prove
current runtime correctness. Do not install prerequisites automatically.

## Packaged logging acceptance

After the serial build above (use `--offline` when cached), run with a full JDK 21
and existing Python 3.12 on macOS/Linux (`ps`/`pgrep` available):

```bash
python3.12 -B app/src/test/python/logging_acceptance.py
```

Allow a 600-second outer timeout and dispatch in the background. The driver
creates a unique project-local `build/logging-smoke-<unique>/`, uses random
loopback ports and copied sample.jar, isolates JVM home/temp/preferences, and
reaps only its owned process groups before deleting that exact scratch tree.
It writes a minimal redacted [JSON summary](evidence/logging-validation-20261109.json).
It never connects to an existing Master/GUI or replaces root runtime packages.

The seven configurations are no option (default INFO), TRACE, DEBUG (space form),
INFO/WARN/ERROR/OFF (equals form), plus five help/invalid-CLI cases. Real packaged
checks cover MCP init/list/call/DELETE and a second session closed at shutdown,
headless create/summary/decompile/idempotent close, isolated GUI channel
register/heartbeat/poll/selected-text completion/idempotent unregister, WARN/ERROR
visibility, worker JVM flags/isolated logs, and absent token/path/payload/source/
selection/comment content. INFO uses the real console; OFF must be empty.
This GUI channel fixture is **not a native GUI-window acceptance run**; dependency
DEBUG is deliberately suppressed by the documented WARN+ safety policy.
No silent-disconnect timeout is invented. [Latest outcome](validation.md#logging-acceptance-latest).

## Fixture-backed GUI/MCP

Set `JAVA_HOME` to a full JDK 21 and `JADX_GUI_DISTRIBUTION` to the existing JADX
1.5.6 `jadx-1.5.6-all.jar`. The optional host smoke source set is separate from
ordinary headless tests:[^build]

```bash
./gradlew --offline --no-daemon --no-parallel --max-workers=1 \
  -PjadxGuiDistribution="$JADX_GUI_DISTRIBUTION" :gui-plugin:compileGuiMenuSmoke
python3.12 -B scripts/smoke/gui_live_status.py
python3.12 -B scripts/smoke/gui_acceptance.py
```

`scripts/smoke/` intentionally has exactly five support files:

| File | Responsibility |
|---|---|
| `apk_http_smoke.py` | Prepare a copied APK, run packaged HTTP checks, publish a redacted summary |
| `apk-e2e-logback.xml` | Console-only logging for that isolated HTTP Master |
| `mcp_helper.py` | Shared bounded MCP client, fresh workspace, owned-child cleanup |
| `gui_live_status.py` | IP/Port Apply, no Cancel, Applied!, single window, counters, restart/no replay, edit restoration and GUI/HEADLESS core chains |
| `gui_acceptance.py` | Directly calls the shared driver with debugger-negative and optional APK launcher checks; no source-string overlay |

Use existing release JARs and compiled host classes; these Python drivers never
download dependencies. The GUI drivers copy the checked-in `sample.jar`, isolate
preferences/config/cache and own only their child JVMs and loopback proxy, never
an existing service or window. Their scenario deadline is 240 seconds; allow an
outer timeout of 330 seconds for bounded cleanup. Output is fresh under
`build/smoke/`, never an old temporary prerequisite. They use both packages in
`build/release/`. `--help` describes startup requirements without starting JVMs.
The independent live-status entry and comprehensive acceptance entry were both
actually rerun during the [five-file revalidation](validation.md#five-file-smoke-revalidation).

For the same-APK GUI+HEADLESS acceptance scenario, set `JADX_SMOKE_INPUT` to an
authorized APK and `JADX_GUI_FIXTURE` to a local JSON descriptor containing
`class_name`, `method_name`, `method_signature` (a JVM descriptor). Run
`gui_acceptance.py`; it copies the input and additionally checks launcher
agreement between manifest, summary, MAIN_ACTIVITY and both backends. Omit both
variables for the checked-in sample.jar scenario. Private fixture descriptors
are not distributed.

## Immediate native rename/readback

The formal [rename driver](../../gui-plugin/src/guiSmoke/python/rename_readback.py)
uses native GUI edit events and checks source immediately, including a named
method query and restoration. No sleep/retry is inserted between mutation and
reads; bounded readiness waits are separate.

```bash
python3.12 -B gui-plugin/src/guiSmoke/python/rename_readback.py \
  --java-home "$JAVA_HOME" --distribution "$JADX_GUI_DISTRIBUTION"
```

Its default fixture is sample.jar. For an authorized copied APK, supply `--input`,
`--class-name`, `--method-name`, `--method-descriptor` and appropriate field flags
from your own fixture; an empty `--field-name` skips that scenario. Do not embed
private APK paths/symbols in published examples. Final results were 96/96 for
sample.jar and 40/40 for the bounded APK configuration, **not proof of no race**.

## Authorized APK HTTP smoke

Set `JADX_SMOKE_INPUT` to an explicitly authorized input APK. The driver hashes
and copies that input; it does not execute/install the APK or change its original
native project. All analysis runs on the generated copy:

```bash
python3.12 -B scripts/smoke/apk_http_smoke.py prepare
# Replace RUN with the workspace-relative directory printed by prepare.
python3.12 -B scripts/smoke/apk_http_smoke.py run build/smoke/RUN
python3.12 -B scripts/smoke/apk_http_smoke.py publish build/smoke/RUN
```

The run deadline is 540 seconds; use an outer timeout of at least 600 seconds.
`publish` writes a bounded `summary.json` inside that run, not into historical
evidence. Review and redact it before publication. Raw local responses may
contain source and must not be committed. This scenario is APK-dependent;
unavailable resource representations are not passing positive tests.

## Deterministic method identity regression

No private APK or JSON descriptor is needed. Existing formal tests inject a
stale native method position directly: analysis checks the exact overload and
numbered source; GUI checks the borrowed native workspace, overload/inner-class
identity, ambiguity, aliases and cache hits. They were freshly executed before
retiring the dedicated harness:

```bash
./gradlew --offline --no-daemon --no-parallel --max-workers=1 \
  :analysis:test --rerun --tests 'com.yyang.jadxmcp.analysis.source.JadxSourceServiceTest.methodSliceUsesCurrentCodeMetadataNotMutableNativeDefinitionPosition' \
  :gui-plugin:test --rerun --tests 'com.yyang.jadxmcp.ui.GuiQueryAdapterTest.exactMethodIdentitySurvivesStalePositionsAliasesInnerClassesAndCacheHits'
```

`gui_settings.py`, `method_identity.py` and `IsolatedGuiMcpSmoke.java` have been
removed and are no longer supported reproduction entries. Settings coverage is
now in the shared GUI driver. The old private Android lifecycle/constructor
before/after scenario remains historical evidence, not a claim that the ordinary
GUI host can inject those faults. Its unique stale-position regression is covered
by the formal tests above; no dedicated dead fixture was retained.

After reviewing/archiving a run's small evidence, remove only that printed run's
generated copies/config/cache/logs, after all owned JVMs and proxy have stopped.
Never clean another run, the original input/native project, user preferences,
existing services, root runtime JARs or open logs. Formal drivers do not depend
on `.agent/tmp/`.

## Evidence policy

[Final and historical results](validation.md) link to minimal redacted JSON: checks,
counts, timing, error codes and hashes. Full decompiled responses, credentials,
private absolute paths, APK copies and repeated build logs are not public
evidence. Old raw runs were compacted before cleanup; hashes identify the retired
record but cannot reconstruct it. This is deliberately **not a full wire archive**.

[^build]: [GUI smoke tasks](../../gui-plugin/build.gradle.kts), [release task](../../build.gradle.kts), [CI](../../.github/workflows/build.yml).
