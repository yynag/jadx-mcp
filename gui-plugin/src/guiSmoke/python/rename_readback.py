"""Real isolated native-event rename/readback regression; standard library only.

Run only after compiling guiSmoke and both Shadow JARs. A release JADX GUI JAR and JDK21
are explicit arguments, never private source-tree or agent-directory dependencies. This
driver creates only owned JVMs and a unique build directory; it does not attach to a user GUI.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import socket
import shutil
import subprocess
import time
import urllib.error
import urllib.request


class McpClient:
    """Keep raw MCP isError visible; transport success alone is not operation success."""

    def __init__(self, port):
        self.url = f"http://127.0.0.1:{port}/mcp"
        self.session = None
        self.sequence = 0

    def rpc(self, method, params=None, notification=False):
        self.sequence += 1
        body = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            body["params"] = params
        if not notification:
            body["id"] = self.sequence
        headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream",
                   "MCP-Protocol-Version": "2025-03-26"}
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        request = urllib.request.Request(self.url, json.dumps(body).encode(), headers, method="POST")
        with urllib.request.urlopen(request, timeout=25) as response:
            self.session = response.headers.get("Mcp-Session-Id", self.session)
            text = response.read().decode()
        if notification or not text.strip():
            return None
        if text.lstrip().startswith("{"):
            return json.loads(text)
        messages = [json.loads(line[5:].strip()) for line in text.splitlines() if line.startswith("data:")]
        return next(message for message in messages if message.get("id") == self.sequence)

    def initialize(self):
        self.rpc("initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
                               "clientInfo": {"name": "native-rename-readback", "version": "1"}})
        self.rpc("notifications/initialized", notification=True)

    def call(self, name, arguments):
        wire = self.rpc("tools/call", {"name": name, "arguments": arguments})
        result = wire.get("result", {})
        if "isError" not in result:
            raise AssertionError(f"{name}: missing raw MCP isError")
        texts = [item["text"] for item in result.get("content", []) if item.get("type") == "text"]
        payload = json.loads(texts[0]) if texts else {}
        if result["isError"] or "error" in payload:
            code = payload.get("error", {}).get("code", "unknown")
            raise AssertionError(f"{name}: MCP operation failed ({code})")
        return payload["result"]


def wait_for(check, seconds):
    """Bound readiness only; never retry an edit with an ambiguous outcome."""
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(0.2)
    raise TimeoutError("Owned process readiness timed out")


def stop(process):
    if process is None:
        return None
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
    return process.returncode


def run(args):
    repository = Path(__file__).resolve().parents[4]
    output_root = (repository / args.work_root).resolve()
    if not output_root.is_relative_to(repository):
        raise ValueError("Smoke output must remain in this repository")
    work = output_root / str(time.time_ns())
    work.mkdir(parents=True)
    java = str(Path(args.java_home).resolve() / "bin" / "java")
    distribution = str(Path(args.distribution).resolve())
    master_jar = Path(args.master).resolve() if args.master else repository / "build/release/jadx-mcp.jar"
    plugin_jar = Path(args.plugin).resolve() if args.plugin else repository / "build/release/jadx-mcp-gui-plugin.jar"
    classes = repository / "gui-plugin/build/classes/guiSmoke"
    fixture = Path(args.input).resolve() if args.input else repository / "app/src/test/resources/sample.jar"
    for prerequisite in (Path(java), Path(distribution), master_jar, plugin_jar, classes, fixture):
        if not prerequisite.exists():
            raise FileNotFoundError(f"Build prerequisite unavailable: {prerequisite.name}")
    # Native GUI cache/project side effects must stay beside a test-owned input, not the fixture.
    sample = work / ("input" + fixture.suffix)
    shutil.copy2(fixture, sample)
    input_digest = hashlib.sha256(sample.read_bytes()).hexdigest()

    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
    master = gui = None
    evidence = {"status": "FAIL", "checks": [], "scope": "native GUI rename readback",
                "target_kind": args.target_kind, "cycles": args.cycles,
                "artifact_sha256": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (master_jar, plugin_jar)}}
    owner = args.class_name

    def require(value, label):
        evidence["checks"].append({"check": label, "passed": bool(value)})
        if not value:
            raise AssertionError(label)

    with (work / "master.log").open("w") as master_log, (work / "gui.log").open("w") as gui_log:
        try:
            master = subprocess.Popen([java, "-jar", str(master_jar), "--port", str(port)],
                                      cwd=work, stdout=master_log, stderr=subprocess.STDOUT)
            client = McpClient(port)
            wait_for(lambda: (client.initialize(), True)[1], 30)
            environment = dict(os.environ, JADX_CONFIG_DIR=str(work / "config"),
                               JADX_CACHE_DIR=str(work / "cache"))
            gui = subprocess.Popen([java, f"-Djadx.mcp.url=http://127.0.0.1:{port}", "-cp",
                distribution + os.pathsep + str(classes), "GuiBridgeIntegrationSmoke", str(work / "gui"),
                str(plugin_jar), str(sample)], cwd=work, env=environment, stdout=gui_log,
                stderr=subprocess.STDOUT, stdin=subprocess.PIPE, text=True)

            def ready():
                if gui.poll() is not None:
                    raise AssertionError("Owned GUI exited during startup")
                sessions = client.call("session_list", {"timeout_ms": 3_000})["sessions"]
                return next((item for item in sessions if item["backend"] == "GUI" and item["state"] == "READY"), None)

            session = wait_for(ready, 90)
            common = {"session_id": session["session_id"], "timeout_ms": 15_000}

            def call(name, **parameters):
                return client.call(name, dict(common, **parameters))

            def source():
                return call("decompile", target="JAVA", class_name=owner)["text"]["content"]

            # Do not waitForComplete or sleep before readback: either would hide the async race.
            # Every edit is sent once. A timeout is an unknown mutation, never an invitation to retry.
            targets = [
                ("CLASS", None, None, owner.rsplit('.', 1)[-1], "RenameReadbackProbe"),
                ("METHOD", args.method_name, args.method_descriptor, args.method_name, "renamedReadbackMethod"),
            ]
            if args.field_name:
                targets.append(("FIELD", args.field_name, args.field_descriptor, args.field_name, "RENAMED_READBACK_FIELD"))
            if args.target_kind != 'ALL':
                targets = [target for target in targets if target[0] == args.target_kind]
            if not targets:
                raise ValueError('Selected target kind requires a nonempty fixture member')
            for cycle in range(args.cycles):
                for kind, member, descriptor, original, alias in targets:
                    target = {"class_name": owner}
                    if member:
                        target.update(member_name=member, descriptor=descriptor)
                    applied = False
                    try:
                        receipt = call("rename", target_type=kind, target=target, new_name=alias)
                        applied = receipt["state"]["applied"]
                        require(applied, f"cycle {cycle} {kind} native project mutation acknowledged")
                        for read in range(3):
                            require(alias in source(), f"cycle {cycle} {kind} immediate source read {read}")
                        if kind == "METHOD":
                            method = call("get_method_by_name", class_name=owner, method_name=alias,
                                          method_signature=descriptor)
                            require(alias + '(' in method['text']['content'],
                                    f"cycle {cycle} METHOD named readback resolves current alias")
                    finally:
                        # Compensate only an acknowledged test-owned edit; never retry an unknown edit.
                        if applied:
                            restored = call("rename", target_type=kind, target=target, new_name=original)
                            require(restored["state"]["applied"], f"cycle {cycle} {kind} restored")
                            restored_source = source()
                            if alias in restored_source:
                                # Keep local diagnostics separate from the bounded public outcome record.
                                (work / 'restore-diagnostic-local.txt').write_text(restored_source)
                                evidence['restore_diagnostic'] = {
                                    'kind': kind, 'alias_occurrences': restored_source.count(alias),
                                    'identifier_call_occurrences': len(re.findall(r'\b'+re.escape(alias)+r'\s*\(', restored_source)),
                                    'source_sha256': hashlib.sha256(restored_source.encode()).hexdigest()}
                                if kind == 'METHOD':
                                    details = call('meta_class', class_name=owner)
                                    evidence['restore_diagnostic']['metadata_has_temporary_alias'] = any(
                                        m['name'] == alias and m['descriptor'] == descriptor for m in details['methods'])
                                    evidence['restore_diagnostic']['metadata_has_restored_name'] = any(
                                        m['name'] == original and m['descriptor'] == descriptor for m in details['methods'])
                            require(alias not in restored_source, f"cycle {cycle} {kind} immediate restored source")
            call("session_close", clear_cache=False)
            require(hashlib.sha256(sample.read_bytes()).hexdigest() == input_digest, "test input bytes unchanged")
            evidence["status"] = "PASS"
        except Exception as failure:
            evidence["failure"] = str(failure)
            raise
        finally:
            if gui is not None and gui.poll() is None:
                try:
                    gui.stdin.write("quit\n")
                    gui.stdin.flush()
                    gui.wait(timeout=15)
                except (OSError, subprocess.TimeoutExpired):
                    pass
            evidence["exits"] = {"gui": stop(gui), "master": stop(master)}
            evidence["owned_processes_reaped"] = all(process is None or process.poll() is not None
                                                      for process in (gui, master))
            gui_log.flush()
            text = (work/'gui.log').read_text(errors='replace')
            evidence['native_class_loading_error_count'] = sum(text.count(marker) for marker in
                ('Failed to parse attribute:', 'Method load error in method:', 'Error decode class:'))
            # Keep bounded labels/results, not request IDs, tokens, parameters or decompiled source.
            (work / "evidence.json").write_text(json.dumps(evidence, indent=2))
            print(evidence["status"], len(evidence["checks"]), "checks; evidence:", work)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", required=True)
    parser.add_argument("--distribution", required=True, help="Existing JADX1.5.6 release GUI JAR")
    parser.add_argument("--cycles", type=int, choices=range(1, 21), default=5)
    parser.add_argument("--target-kind", choices=('ALL', 'CLASS', 'METHOD', 'FIELD'), default='ALL')
    parser.add_argument("--master", help="Optional staged Master artifact; leaves release packages untouched")
    parser.add_argument("--plugin", help="Optional staged GUI artifact; leaves release packages untouched")
    parser.add_argument("--work-root", default="build/gui-rename-smoke")
    parser.add_argument("--input", help="Optional explicitly authorized input, copied into the owned workspace")
    parser.add_argument("--class-name", default="com.yyang.sample.HelloSample")
    parser.add_argument("--method-name", default="greet")
    parser.add_argument("--method-descriptor", default="(Ljava/lang/String;)Ljava/lang/String;")
    parser.add_argument("--field-name", default="MAGIC", help="Use an empty value to skip the field scenario")
    parser.add_argument("--field-descriptor", default="Ljava/lang/String;")
    run(parser.parse_args())
