# Latest local acceptance and historical validation

Final verification was completed on **2026-11-09 against the uncommitted working
tree** by the assigned verification owner. This documentation task independently
read the final report, small original evidence and existing JUnit XML, and
rehashed both packages; that documentation closeout did not rebuild or rerun the
application. The later smoke consolidation below did execute fresh checks.

## Logging acceptance (latest)

The final logging working tree was rebuilt and tested on **2026-11-09**, with a
full Temurin JDK 21.0.12.1+1, offline, serial, one Gradle worker and a 1200-second
outer timeout. **239 tests passed**, with zero failures/errors/skips: protocol
**13**, analysis **71**, app **61**, GUI **94**. All **23** build tasks executed
under `test releaseArtifacts --rerun-tasks`; this is a new full-suite result, not
the two filtered tests or the historical 233-test result below.

- [Build command, revision, module/XML counts and hashes](evidence/logging-build-validation-20261109.json)
- [Packaged console/runtime matrix and redacted messages](evidence/logging-validation-20261109.json)
- [Reproduction commands](README.md#packaged-logging-acceptance)

The packaged driver passed **7/7 configurations** (default INFO, TRACE, DEBUG,
INFO, WARN, ERROR, OFF) and **5/5 CLI cases** (both help forms; invalid equals,
invalid space, empty equals). DEBUG used the space form in a live JVM; the other
explicit levels used equals. Every configuration used a fresh project-local
directory, copied sample.jar, random loopback port and a real child analysis JVM.
The raw console line counts were respectively **14/44/44/14/3/1/0**. INFO contains
lifecycle but no request DEBUG; WARN/ERROR filter lower levels; OFF is truly empty.

Each INFO-visible configuration recorded two MCP initialized sessions and two
exits exactly once each: the first closed with real DELETE, the second remained
registered until application shutdown. Init/list/call, source decompilation,
HEADLESS READY/create/close (duplicate close), GUI channel registration/heartbeat/
poll/selected-text completion/unregister (duplicate unregister), HTTP start/stop,
WARN recoverable rejection and ERROR failed creation were checked. Actual worker
argv inherited the selected level; its file log stayed under the owned session
directory and respected INFO visibility. Token, scratch absolute path, payload,
source excerpt, comment and selected-text sentinels were absent from all captured
console/file logs. This is bounded fixture coverage, not a universal information-
flow proof for every dependency/error/input.

Examples (IDs redacted):

```text
INFO  MCP client connected sdkSessionId=<id> semantics=initialized-session
INFO  Session opened sessionId=<id> backend=HEADLESS state=READY
INFO  GUI registered sessionId=<id> backend=GUI state=READY
DEBUG Request completed requestId=<id> operation=decompile elapsedMs=<ms>
INFO  MCP client exited sdkSessionId=<id> semantics=transport-session-closed
INFO  HTTP server stopped
```

An initial matrix failed OFF because kotlin-logging printed its default factory
banner directly to stdout. An isolated diagnostic reproduced it; the startup
configuration now disables that banner before SDK loading, and the **entire
forced suite/build and complete matrix were rerun successfully**. Earlier attempts
are recorded, not added to the final pass totals. Existing Kotlin warnings and
macOS input-method diagnostics from GUI fixture tests remain; they are not Master
Logback events.

Current release SHA-256 (both verified again at closeout):

```text
a0f425c2fce312de6bcba9ec7f5f4a8f32bfd86b53371b6c363b451cc6e14799  build/release/jadx-mcp.jar
3fa6f5c69bdb4ef103f46394bcd4fd8a1ead9a16ca5d43b312d9f56a31106272  build/release/jadx-mcp-gui-plugin.jar
```

Limits: dependency logs remain **WARN+ even at TRACE/DEBUG** to suppress SDK/library
payload diagnostics; DEBUG exposes application request/backend timings, not every
library's DEBUG. Application failures emit type/code without exception messages
or stacks; dependency WARN/ERROR can include their own stacks and require review
before sharing. SDK 0.14 has **no default idle expiry for silent MCP sessions**:
transport/session close is observable, instant TCP/half-open loss is not promised.
GUI lease timeout/retention is a separate lifecycle. The packaged GUI channel was
an isolated HTTP fixture, not a native GUI window. The previous live-GUI/APK/
rename/debugger matrices were **not rerun** for this logging change. All owned
Master/worker process groups were reaped and exact generated scratch copies/logs/
config removed; root runtime JAR hashes remained unchanged. Existing Master/GUI
services and their open root logs were not replaced, restarted or cleaned.
No dependency installation, remote CI, commit or push was performed.

## Five-file smoke revalidation

The later 2026-11-09 consolidation reduced `scripts/smoke/` from eight files to
five **after real executions**, not just AST checks. It used the existing release
packages with the SHA-256 values below and existing GUI host classes, JDK 21 and
JADX 1.5.6; it did not rebuild release packages. Concurrent later app logging
source changes are **not included in those older packaged-runtime claims**; the
new logging packages are verified separately above.

| Fresh scope | Result |
|---|---|
| Independent `gui_live_status.py`, copied sample.jar | **42/42** |
| `gui_acceptance.py`, copied sample.jar | **45/45** |
| `gui_acceptance.py`, copied authorized APK | **55/55**, including launcher agreement across GUI/HEADLESS |
| `apk_http_smoke.py` prepare/run/publish | **60 PASS, 4 NO_OBJECT, 0 FAIL**; relative-path publish succeeded |
| Two existing formal stale-position tests | **2 tests**, zero failures/errors/skips; only the two test tasks executed, required compilation was UP-TO-DATE |

[Minimal new JSON](evidence/smoke-five-file-validation-20261109.json) records
commands, hashes, checks, prior failed attempts and cleanup. The sample acceptance
pass preceded only the final prerequisite/cleanup guards; the independent sample
and APK acceptance passes used the final driver. One earlier GUI attempt failed
because its reader reused old visible-window output after unload; the reader now
uses only the current command's output. The first HTTP publish attempt exposed a
relative-path print error, fixed and rerun successfully. These were driver issues,
not product failures, and the failed attempts are not counted as passes.

The new GUI runs covered IP/Port Apply validation, no Cancel, exact Applied!,
single window/timer ownership, counters, actual Master restart, lost completion
ACK without replay, comment restoration, explicit close and GUI/HEADLESS chains.
All owned isolated processes were reaped. The APK remained read-only; only copied
inputs were analyzed. The four HTTP NO_OBJECT checks are unavailable resource
representations, not positive resource passes; observed Smali ID resolved to a
name, not a string value.

The removed settings driver is merged into the shared GUI entry. The removed
private method driver and Java fault host are **no longer supported reproduction
entries**. Existing formal analysis and GUI method-identity tests already inject
the same stale native position deterministically, so no private fixture was
migrated. This does not rerun the retired private Android constructor/lifecycle
before/after matrix. Historical JSON outcomes remain unchanged. In particular,
the old live-status assertion label about multiple menu observers did not prove
two listener removals; current checks assert the single owned observer.

The historical full-suite/rename totals below were not rerun or added to this
round. No debugger-positive/device, native save/reopen, stress/race, exhaustive
input coverage, logging-source acceptance or remote CI claim is made.

## Earlier native-GUI final accepted results

| Scope | Final result |
|---|---|
| Full module tests | **233**, all failures/errors/skips zero: protocol **13**, analysis **71**, app **55**, GUI **94** |
| Packaging/help/entry descriptors | Both `build/release/` packages and CLI help passed |
| Real menu reset/load/reload | One **Jadx MCP** entry, no duplicates |
| sample.jar GUI/MCP/HEADLESS | **44/44** assertions |
| Same APK GUI+HEADLESS | **54/54** assertions: launcher/manifest agreement, edit restoration, reconnect without replay |
| Immediate native rename/readback | sample.jar **96/96**, APK **40/40**, including restore and named-method readback |
| GUI controls | **Cancel absent**, exact **Applied!** success text, one live window |
| Owned isolated processes | All reaped, including superseded attempts |

Rename checks performed no sleep or retry between mutation and immediate reads.
The bounded runs **did not reproduce earlier instability; they do not prove that
no race exists**. Readiness waits are not rename-readback retries.

Stable evidence (no temporary-directory prerequisite):

- [Minimal final report](../../.agent/data/final-verification-20261109.json)
- [JUnit counts and original XML hashes](../../.agent/data/final-junit-counts-20261109.json)
- Original small outcome records: [sample](../../.agent/data/final-sample-gui-mcp-20261109.json),
  [APK/launcher](../../.agent/data/final-apk-gui-headless-20261109.json),
  [sample rename](../../.agent/data/final-rename-sample-20261109.json),
  [APK rename](../../.agent/data/final-rename-apk-20261109.json)
- [Build/menu/help markers and original log hashes](../../.agent/data/final-build-menu-cli-20261109.json)
- [Workflow static checks](../../.agent/data/final-workflow-static-20261109.json)

Historical release SHA-256 for that earlier native-GUI round (superseded Master):

```text
cb1e8106b91165a7ae0a65dd47cbbba01db9400ea204b7becf87dbe2c40b99c8  build/release/jadx-mcp.jar
3fa6f5c69bdb4ef103f46394bcd4fd8a1ead9a16ca5d43b312d9f56a31106272  build/release/jadx-mcp-gui-plugin.jar
```

GitHub Actions received **static text checks only**. No PyYAML or other complete
YAML parser was available/installed, no full YAML semantic validation is claimed,
and **remote GitHub Actions was not run**. **No commit or push was performed.**
Superseded harness expectation/fixture-path adjustments are not product-defect
claims. The portable acceptance overlay was archived and parameterized after the
historical run; those archival edits were only statically checked at closeout.
It has since been replaced by the directly called driver verified above.

## Earlier recorded rounds

These counts belong to earlier working trees, not additional final test runs.

| Scope | Recorded outcome | Evidence / limits |
|---|---|---|
| Four-module rebuild before cache suffix update | 203 tests, 0 failures/errors/skips (12/67/51/73) | [Historical cache record](evidence/cache-layout-validation-20261109.json), not the current suite. Initial incremental attempt had 29 app failures before guarded cleanup was corrected. |
| Cache suffix and worker-log containment | 52 app tests plus app packaging/help | Other modules were not rerun in that round. Current naming is complete filename + `.jadx_mcp_cache`. |
| Packaged APK HTTP | 60 PASS, 4 NO_OBJECT, 0 FAIL | [HTTP summary](evidence/apk-headless-http-smoke-20261109.json); real Smali ID→name, not string ID→value; HTTP-only, one APK, no execution/device debugging. |
| Existing GUI/MCP read-only | 33 attempts: 21 successes, 7 expected rejections, 5 environment-not-ready | [GUI summary](evidence/mcp-gui-smoke-20261109.json); one rejection occurred client-side, 32 reached server; raw isError was not visible through that wrapper. |
| System matrix | 27 operations classified across HEADLESS/GUI; lifecycle/edit/timeout paths | [Matrix](evidence/system-test-mcp-20261109.json); coverage is not 27 positive end-to-end passes per backend. |
| Exact method-source identity | 212 module tests; real before/after defect reproduction | [Method summary](evidence/method-source-identity-fix-20261109.json); exact overload/constructor/alias queries; native GUI rename/editor refresh remained asynchronous. |
| Live status and reconnect | 221 tests (12/68/52/89), 34 real integration assertions | [Live-status summary](evidence/gui-live-status-validation-20261109.json); actual Master restart, lost completion ACK without edit replay, counters and unload cleanup. |
| Unified menu | 91 GUI tests, 13 isolated checks | [Menu summary](evidence/gui-unified-menu-20261109.json); not a new four-module pass. |
| IP/port fields | 94 GUI tests, 16 isolated checks | [Settings summary](evidence/gui-ip-port-20261109.json); predates Cancel removal and Applied! wording. |

The eight earlier narrative records were consolidated here and in the
[usage notes](../usage/gui-and-cache.md). Their implementation rationale is not
maintained as a separate design dossier; consult the corresponding code/tests.
The bounded [retired raw-run summaries](evidence/retired-raw-evidence-20261109.json)
retain 17 original-record hashes, assertion outcomes and call counts, including
failed attempts. [Build-log metadata](evidence/build-log-metadata-20261109.json)
retains original log hashes and completion markers without full log contents.

## Verified changes and scope

- GUI Cancel removal and **Applied!** wording passed the final isolated checks;
  do not substitute the older 94-test/16-check record for this evidence.
- `MAIN_ACTIVITY` uses the manifest model/resolver; final tests and the same-APK
  GUI/HEADLESS launcher checks passed. This does not exhaust every manifest,
  alias or resource-valued flag combination.
- `build/release/` is the unified distribution directory in the current root
  release task and CI. The two older root-level JARs are not the current release
  directory and must not be moved while a running service uses them.
- The historical scenario logic and formal rename harness have recorded runtime
  evidence. Portable script archival edits were not rerun by that closeout;
  the later five-file smoke revalidation is recorded separately above.
  No release upload, remote CI, commit or push is asserted.

## Still outside a complete claim

- Exhaustive large-APK/class/resource validation and APK/device execution.
- Debugger positive paths with an attached, paused device.
- Every parameter combination across all 27 tools. The earlier MCP tool wrapper
  did not expose raw `isError`; direct final HTTP clients checked raw errors but
  do not remove that wrapper's visibility limit.
- Native GUI close during in-flight native analysis; deadlines are not reliable
  cancellation of all native JADX work.
- Native ASK/ALWAYS save/reopen and asynchronous rename/editor refresh under
  stress. Applied edits and persisted saves remain separate outcomes.
- Protection against malicious same-user filesystem mutation races beyond the
  portable identity/no-follow checks.

## Reproduce without a private temporary tree

Use the [current commands and prerequisites](README.md). The historical full
suite command was a forced serial Gradle test plus both Shadow tasks, using
Temurin JDK 21.0.12.1+1-LTS on macOS. Use the current `releaseArtifacts` task for
packaging, not manual moves or retired artifact names. Record current provenance
and results afresh for future source changes instead of carrying counts forward.

Historical evidence is compacted; final small outcome records are preserved with
hashes. No source-bearing full responses or private paths are published. Input
immutability/cleanup assertions do not authorize deleting any user's original
APK or native `.jadx` project. Generated isolated copies were removed only after
archival and an open-file/process-reference check.
