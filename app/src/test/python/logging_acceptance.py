#!/usr/bin/env python3.12
"""Bounded packaged-runtime logging acceptance; stdlib only, no existing server/GUI.

Each JVM has a project-local cwd/home/temp and random loopback port. Only owned
process groups and the uniquely created scratch directory are cleaned. Public
evidence contains counts/hashes/sanitized log messages, never wire responses.
"""
import concurrent.futures
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[4]
JAR = ROOT / "build/release/jadx-mcp.jar"
LEVELS = ["DEFAULT", "TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF"]


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def require(condition, label):
    if not condition:
        raise AssertionError(label)


def request(base, path, body=None, headers=None, method="POST"):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method, headers={
        "Content-Type": "application/json", "Accept": "application/json, text/event-stream",
        **(headers or {})})
    try:
        response = urllib.request.urlopen(req, timeout=40)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read().decode()
        if response.headers.get_content_type() == "text/plain":
            return response.status, dict(response.headers), raw
        if raw.startswith("event:") or raw.startswith("data:"):
            raw = next(line[5:].strip() for line in raw.splitlines() if line.startswith("data:"))
        return response.status, dict(response.headers), json.loads(raw) if raw.strip() else None


class Peer:
    def __init__(self, base):
        self.base, self.session, self.sequence = base, None, 0

    def exchange(self, method, params, notification=False):
        self.sequence += 1
        body = {"jsonrpc": "2.0", "method": method, "params": params}
        if not notification:
            body["id"] = self.sequence
        headers = {"MCP-Protocol-Version": "2025-03-26"}
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        status, response_headers, result = request(self.base, "/mcp", body, headers)
        require(200 <= status < 300, "MCP HTTP success: " + method)
        if method == "initialize":
            self.session = next(value for key, value in response_headers.items() if key.lower() == "mcp-session-id")
        if not notification:
            require("result" in result and "error" not in result, "MCP result: " + method)
        return result

    def initialize(self):
        self.exchange("initialize", {"protocolVersion": "2025-03-26", "capabilities": {},
                                    "clientInfo": {"name": "isolated-log-check", "version": "1"}})
        self.exchange("notifications/initialized", {}, notification=True)

    def tool(self, name, arguments):
        result = self.exchange("tools/call", {"name": name, "arguments": arguments})["result"]
        require(not result.get("isError", False), "MCP tool success: " + name)
        return json.loads(result["content"][0]["text"])

    def close(self):
        status, _, _ = request(self.base, "/mcp", headers={"Mcp-Session-Id": self.session,
            "MCP-Protocol-Version": "2025-03-26"}, method="DELETE")
        require(status == 200, "MCP DELETE")


def messages(raw):
    return [line.split(" - ", 1)[1] for line in raw.splitlines() if " - " in line]


def stop(process):
    if process.poll() is None:
        process.send_signal(signal.SIGTERM)
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)
            raise AssertionError("owned Master required forced shutdown")
    # A child retaining this exclusively owned group is a leak, not cleanup success.
    try:
        os.killpg(process.pid, 0)
    except ProcessLookupError:
        return
    os.killpg(process.pid, signal.SIGKILL)
    raise AssertionError("owned worker group survived Master shutdown")


def scenario(java, scratch, requested):
    level = "INFO" if requested == "DEFAULT" else requested
    run = scratch / requested.lower()
    run.mkdir()
    for name in ("home", "temp", "prefs"):
        (run / name).mkdir()
    fixture = run / "input.jar"
    shutil.copyfile(ROOT / "app/src/test/resources/sample.jar", fixture)
    with zipfile.ZipFile(fixture) as archive:
        class_name = next(name[:-6].replace("/", ".") for name in archive.namelist()
                          if name.endswith(".class") and "$" not in name and not name.endswith("module-info.class"))
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    command = [java, f"-Duser.home={run / 'home'}", f"-Djava.io.tmpdir={run / 'temp'}",
               f"-Djava.util.prefs.userRoot={run / 'prefs'}", "-jar", str(JAR), "--port", str(port),
               "--worker-heap", "256m"]
    if requested != "DEFAULT":
        # Both parameter forms launch real JVMs, not only parser tests.
        command += ["--log-level", level] if requested == "DEBUG" else [f"--log-level={level}"]
    raw_file = run / "console.log"
    sensitive = ["PRIVATE_SELECTION_" + uuid.uuid4().hex, "PRIVATE_COMMENT_" + uuid.uuid4().hex,
                 "PRIVATE_PAYLOAD_" + uuid.uuid4().hex, str(run)]
    started = time.monotonic()
    with raw_file.open("wb") as output:
        process = subprocess.Popen(command, cwd=run, stdout=output, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        try:
            deadline = time.monotonic() + 30
            while True:
                require(process.poll() is None, "Master remains alive during startup")
                try:
                    status, _, text = request(base, "/status", method="GET")
                    if status == 200 and text.startswith("server: running\n"):
                        break
                except (OSError, urllib.error.URLError):
                    pass
                require(time.monotonic() < deadline, "Master ready deadline")
                time.sleep(0.1)
            for _ in range(2):
                require(request(base, "/api/session_list", {})[0] == 200, "stateless management")
            require("MCP client connected" not in raw_file.read_text(), "HTTP is not MCP client")
            peer = Peer(base)
            peer.initialize()
            tools = peer.exchange("tools/list", {})["result"]["tools"]
            require(len(tools) == 25 and all(tool["name"] != "get_main_application_classes_names" for tool in tools),
                    "25 MCP tools; REST-only operation excluded")
            peer.tool("session_list", {})
            opened = peer.tool("session_create", {"apk_path": str(fixture), "request_id": "create-check"})
            sid = opened["session_id"]
            require(opened["state"] == "READY", "headless READY")
            peer.tool("meta_summary", {"session_id": sid})
            source = peer.tool("decompile", {"session_id": sid, "class_name": class_name, "target": "JAVA"})
            source_text = source["text"]["content"]
            require(len(source_text) > 20, "real worker source returned")
            sensitive.append(source_text[:100])
            worker_files = list(run.glob("input.jar.jadx_mcp_cache/session-*/logs/server.log"))
            require(len(worker_files) == 1, "worker log directory isolation")
            # Inspect only children of this owned Master; do not publish their argv/paths.
            child_ids = subprocess.run(["pgrep", "-P", str(process.pid)], capture_output=True, text=True,
                                       timeout=5, check=True).stdout.split()
            child_argv = [subprocess.run(["ps", "-p", pid, "-o", "command="], capture_output=True,
                                        text=True, timeout=5, check=True).stdout for pid in child_ids]
            require(any("com.yyang.jadxmcp.worker.WorkerMain" in argv and
                        f"-Djadx.mcp.logLevel={level}" in argv for argv in child_argv), "live worker JVM level flag")
            worker_log = worker_files[0].read_text()
            # A secret comment travels through MCP and is rejected by the headless capability fence.
            rejected = peer.exchange("tools/call", {"name": "add_comment", "arguments": {
                "session_id": sid, "class_name": class_name, "comment": sensitive[1]}})["result"]
            require(rejected.get("isError") is True, "recoverable unsupported request")
            status, _, failed = request(base, "/api/session_create", {"apk_path": str(run / "missing.jar")})
            require(status >= 400 and "error" in failed, "failed create classification")
            # Unknown argument is never logged, even under TRACE.
            request(base, "/api/session_list", {"private_payload": sensitive[2]})
            status, _, lease = request(base, "/gui/register", {"instance_id": "isolated-gui", "name": "isolated GUI",
                "project_epoch": 0, "capabilities": {"backend": "GUI"}})
            require(status == 200 and "token" in lease, "isolated GUI registration")
            sensitive.append(lease["token"])
            gid = lease["session_id"]
            status, _, conflict = request(base, "/api/get_selected_text", {
                "session_id": gid, "project_epoch": 1, "request_id": "epoch-conflict-check"})
            require(status == 409 and conflict["error"]["code"] == "CONFLICT", "epoch conflict classification")
            for _ in range(2):
                require(request(base, "/gui/heartbeat", {"lease": lease, "project_epoch": 0})[0] == 200, "GUI heartbeat")
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
                selected = executor.submit(request, base, "/api/get_selected_text", {"session_id": gid, "timeout_ms": 5000})
                _, _, poll = request(base, "/gui/poll", {"lease": lease, "request_id": "poll-check", "project_epoch": 0,
                    "deadline_epoch_millis": int(time.time() * 1000) + 3000})
                require(poll["status"] == "COMMAND", "GUI command delivered")
                frame = poll["command"]
                require(request(base, "/gui/complete", {"lease": lease, "completion": {
                    "version": 1, "operation": "get_selected_text", "request_id": frame["request_id"],
                    "project_epoch": 0, "result": {"text": sensitive[0]}}})[0] == 200, "GUI completion")
                require(selected.result(timeout=8)[2]["result"]["text"] == sensitive[0], "selected text returned, not logged")
            for _ in range(2):
                require(request(base, "/gui/unregister", lease)[0] == 200, "idempotent GUI unregister")
            for expected in (True, False):
                require(peer.tool("session_close", {"session_id": sid})["closed"] == expected, "idempotent headless close")
            peer.close()
            # No DELETE for the second session: shutdown closes it, not a fabricated TCP event.
            silent = Peer(base)
            silent.initialize()
            silent.tool("session_list", {})
            if level in ("TRACE", "DEBUG", "INFO"):
                require(raw_file.read_text().count("MCP client exited") == 1, "silent session not falsely declared exited")
        finally:
            stop(process)
    console = raw_file.read_text()
    logs = [console] + [path.read_text(errors="replace") for path in run.rglob("*.log") if path != raw_file]
    require(not any(secret in text for secret in sensitive for text in logs), "no token/path/payload/source/selection/comment leak")
    lifecycle = level in ("TRACE", "DEBUG", "INFO")
    expected_counts = {"MCP client connected": 2, "MCP client exited": 2, "GUI registered": 1,
                       "GUI closed": 1, "Session opened": 1, "Session closed": 1,
                       "HTTP server started": 1, "HTTP server stopped": 1}
    for marker, count in expected_counts.items():
        require(console.count(marker) == (count if lifecycle else 0), "console lifecycle exactly once: " + marker)
    require(("Request started" in console) == (level in ("TRACE", "DEBUG")), "DEBUG request visibility")
    require(("Session open failed" in console) == (level != "OFF"), "ERROR visibility")
    rejected = [line for line in console.splitlines() if "Request rejected" in line]
    unsupported = [line for line in rejected if "operation=add_comment code=UNSUPPORTED" in line]
    conflict = [line for line in rejected if "requestId=epoch-conflict-check operation=get_selected_text code=CONFLICT" in line]
    require(len(unsupported) == (1 if level in ("TRACE", "DEBUG") else 0) and
            all(" DEBUG " in line for line in unsupported), "ordinary client rejection DEBUG visibility")
    require(len(conflict) == (1 if level in ("TRACE", "DEBUG", "INFO", "WARN") else 0) and
            all(" WARN " in line for line in conflict), "epoch conflict WARN visibility")
    require(("Worker started" in worker_log) == lifecycle, "worker inherits selected level")
    if level == "OFF":
        require(not console.strip(), "OFF console empty")
    examples = []
    for message in messages(console):
        if any(message.startswith(marker) for marker in (*expected_counts, "Request started", "Request completed")):
            message = re.sub(r"[0-9a-f]{8}-[0-9a-f-]{27,}", "<id>", message)
            message = re.sub(r"elapsedMs=\d+", "elapsedMs=<ms>", message)
            if message not in examples:
                examples.append(message)
    return {"requested": requested, "effective": level, "passed": True,
            "seconds": round(time.monotonic() - started, 2), "console_sha256": digest(raw_file),
            "console_lines": len(console.splitlines()), "worker_log_sha256": hashlib.sha256(worker_log.encode()).hexdigest(),
            "lifecycle_counts": {marker: console.count(marker) for marker in expected_counts},
            "live_worker_level_flag_verified": True,
            "examples": examples[:12], "sensitive_checks": "token, absolute scratch path, source, payload, comment, selection absent"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', help='Fresh repository-local JSON summary; existing files are not overwritten')
    options = parser.parse_args()
    summary = (ROOT/options.output).resolve() if options.output else ROOT/'build/smoke'/('logging-'+uuid.uuid4().hex+'.json')
    require(summary.is_relative_to(ROOT) and not summary.exists(), 'summary must be a fresh repository-local file')
    summary.parent.mkdir(parents=True, exist_ok=True)
    def interrupted(signum, frame):
        raise TimeoutError("logging acceptance interrupted/deadline; owned cleanup follows")

    # Leave time for each scenario's finally to reap its group before the outer 600s bound.
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGALRM, interrupted)
    signal.alarm(540)
    require(sys.version_info[:2] == (3, 12), "use python3.12")
    java = str(Path(os.environ.get("JAVA_HOME", "")) / "bin/java") if os.environ.get("JAVA_HOME") else shutil.which("java")
    require(java and JAR.is_file(), "existing full JDK 21 and releaseArtifacts required")
    build = ROOT / "build"
    require(build.resolve().is_relative_to(ROOT), "scratch parent must be repository-local")
    scratch = build / ("logging-smoke-" + uuid.uuid4().hex)
    scratch.mkdir()
    scratch_stat = scratch.lstat()
    results = []
    try:
        before = {path.name: digest(path) for path in ROOT.glob("*.jar")}
        for args in (["--help"], ["-h"], ["--log-level=BAD"], ["--log-level", "BAD"], ["--log-level="]):
            result = subprocess.run([java, "-jar", str(JAR), *args], cwd=scratch, capture_output=True, timeout=15)
            help_case = args[0] in ("--help", "-h")
            require((result.returncode == 0) == help_case, "CLI exit status")
            text = (result.stdout + result.stderr).decode()
            require("--log-level=INFO" in text if help_case else ("Invalid log level" in text or "Missing value" in text), "CLI diagnostic")
        for level in LEVELS:
            results.append(scenario(java, scratch, level))
        require(before == {path.name: digest(path) for path in ROOT.glob("*.jar")}, "root runtime packages unchanged")
        report = {"date": datetime.date.today().isoformat(), "scope": "isolated packaged logging acceptance, copied checked-in sample.jar",
                  "command": "python3.12 -B app/src/test/python/logging_acceptance.py", "passed": True,
                  "cli_cases": 5, "levels": results, "root_jars_unchanged": True,
                  "cleanup": "all owned process groups reaped; unique scratch removed",
                  "limits": ["GUI channel fixture, not a native GUI window", "no APK/device/debugger/rename matrix rerun",
                             "silent MCP session has no SDK default idle timeout; exit recorded on DELETE/transport close/shutdown",
                             "dependency logs restricted to WARN+; DEBUG/TRACE apply to application diagnostics"]}
        report['artifact_sha256'] = digest(JAR)
        with summary.open("x") as output:
            output.write(json.dumps(report, indent=2) + "\n")
        print(json.dumps({"passed": True, "levels": len(results), "evidence": str(summary.relative_to(ROOT))}))
    finally:
        # Every scenario reaps its group in finally before this exact owned subtree is removed.
        try:
            current = scratch.lstat()
            require(not scratch.is_symlink() and (current.st_dev, current.st_ino) ==
                    (scratch_stat.st_dev, scratch_stat.st_ino), "scratch identity changed; refusing cleanup")
            shutil.rmtree(scratch)
        finally:
            signal.alarm(0)


if __name__ == "__main__":
    main()
