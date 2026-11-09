"""Shared bounded MCP client, fresh workspace and owned-child cleanup.

Uses only the standard library and existing JDK/JADX/release artifacts; never
downloads dependencies or connects to a user service. Drivers own their Popen
handles; stop() must never be passed an unrelated process discovered by name.
"""
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time
import urllib.request
import uuid

BASE = Path(__file__).resolve().parents[2]
JAVA = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java')
DIST = os.environ.get('JADX_GUI_DISTRIBUTION', '')
RELEASE = BASE / 'build/release'


class SmokeDeadline(BaseException):
    """Must escape readiness loops that intentionally retry ordinary exceptions."""


def workspace(label, seconds=240):
    """Fresh generated output, never a persisted prerequisite or user project directory."""
    if not JAVA or not Path(DIST).is_file():
        raise SystemExit('Set JAVA_HOME to a full JDK 21 and JADX_GUI_DISTRIBUTION to the JADX 1.5.6 all JAR')
    required = [RELEASE/'jadx-mcp.jar', RELEASE/'jadx-mcp-gui-plugin.jar',
                BASE/'gui-plugin/build/classes/guiSmoke/GuiBridgeIntegrationSmoke.class',
                BASE/'gui-plugin/build/classes/guiSmoke/MainWindowPluginsMenuSmoke$MemoryPreferencesFactory.class']
    if missing := [str(path.relative_to(BASE)) for path in required if not path.is_file()]:
        raise SystemExit('Build releaseArtifacts and :gui-plugin:compileGuiMenuSmoke first; missing: '+', '.join(missing))
    def timed_out(*_):
        raise SmokeDeadline('Smoke driver deadline exceeded')
    signal.signal(signal.SIGTERM, timed_out)
    signal.signal(signal.SIGALRM, timed_out)
    signal.alarm(seconds)
    root = BASE / 'build/smoke' / (label + '-' + uuid.uuid4().hex[:12])
    root.mkdir(parents=True)
    return root


class Mcp:
    def __init__(self, port):
        self.url = f'http://127.0.0.1:{port}/mcp'
        self.session = None
        self.sequence = 0
        self.rows = []
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def rpc(self, method, params=None, notification=False):
        self.sequence += 1
        body = {'jsonrpc': '2.0', 'method': method}
        if params is not None:
            body['params'] = params
        if not notification:
            body['id'] = self.sequence
        headers = {'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream',
                   'MCP-Protocol-Version': '2025-03-26'}
        if self.session:
            headers['Mcp-Session-Id'] = self.session
        request = urllib.request.Request(self.url, json.dumps(body).encode(), headers, method='POST')
        with self.opener.open(request, timeout=40) as response:
            self.session = response.headers.get('Mcp-Session-Id', self.session)
            raw = response.read(2 * 1024 * 1024 + 1)
        if len(raw) > 2 * 1024 * 1024:
            raise RuntimeError('MCP response exceeded smoke bound')
        text = raw.decode()
        if not text.strip() or notification:
            return None
        if text.lstrip().startswith('{'):
            return json.loads(text)
        values = [json.loads(line[5:].strip()) for line in text.splitlines() if line.startswith('data:')]
        return next(value for value in values if str(value.get('id')) == str(body['id']))

    def initialize(self):
        self.session = None
        self.rpc('initialize', {'protocolVersion': '2025-03-26', 'capabilities': {},
                               'clientInfo': {'name': 'isolated-smoke', 'version': '1'}})
        self.rpc('notifications/initialized', notification=True)
        return True

    def call(self, name, arguments):
        started = time.monotonic()
        wire = self.rpc('tools/call', {'name': name, 'arguments': arguments})
        result = wire.get('result')
        # Persist only outcome metadata; decompiled source and input paths stay out of evidence.
        self.rows.append({'operation': name, 'elapsed_ms': round((time.monotonic() - started) * 1000),
                          'is_error': result.get('isError', False) if result else True})
        if not result:
            raise RuntimeError('MCP tool result missing')
        texts = [item['text'] for item in result.get('content', []) if item.get('type') == 'text']
        return result.get('isError', False), json.loads(texts[0]) if texts else {}


def wait_for(check, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            value = check()
            if value:
                return value
        except Exception:
            pass
        time.sleep(.25)
    raise TimeoutError('Smoke readiness deadline exceeded')


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
