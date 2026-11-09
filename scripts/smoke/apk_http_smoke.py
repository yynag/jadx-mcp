#!/usr/bin/env python3.12
"""Real packaged HTTP smoke only; no APK execution or direct JADX API calls.

Preparation records read-only source metadata/hash before copying. All generated runtime data
belongs to the workspace copy; finally always closes sessions and reaps this driver's processes.
Raw bounded evidence stays in build/smoke; publish emits a redacted result separately.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid

WORKSPACE = Path(__file__).resolve().parents[2]
SOURCE = Path(os.environ['JADX_SMOKE_INPUT']).resolve() if os.environ.get('JADX_SMOKE_INPUT') else None
JAVA = Path(os.environ['JAVA_HOME']) / 'bin/java' if os.environ.get('JAVA_HOME') else Path(shutil.which('java') or 'java')
JAR = WORKSPACE / 'build/release/jadx-mcp.jar'


class SmokeDeadline(BaseException):
    """Global deadline must bypass per-step exception recording and reach cleanup."""


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def stat(path):
    try:
        value = path.stat()
        return {k: getattr(value, k) for k in
                ('st_dev', 'st_ino', 'st_size', 'st_mode', 'st_mtime_ns', 'st_ctime_ns')}
    except FileNotFoundError:
        return {'exists': False}


def source_state():
    if SOURCE is None or not SOURCE.is_file():
        raise SystemExit('Set JADX_SMOKE_INPUT to an explicitly authorized APK; the driver only copies it')
    return {'apk': stat(SOURCE), 'sha256': sha(SOURCE),
            'parent': stat(SOURCE.parent),
            'siblings': {name: stat(SOURCE.parent / name) for name in
                         (SOURCE.name + '.jadx', SOURCE.stem + '.jadx',
                          SOURCE.name + '.jadx_mcp_cache', SOURCE.name + '_jadx_cache')}}


def save(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')


def prepare():
    before = source_state()  # Must precede creating the copy or starting any JVM.
    root = WORKSPACE / 'build/smoke' / ('apk-e2e-' + time.strftime('%Y%m%dT%H%M%S') + '-' + uuid.uuid4().hex[:8])
    inputs = root / 'input'
    inputs.mkdir(parents=True)
    apk = inputs / SOURCE.name
    shutil.copyfile(SOURCE, apk)
    assert sha(apk) == before['sha256']
    state = {'root': str(root), 'input': str(apk), 'original_before': before,
             'copy_initial_sha256': sha(apk), 'artifact_sha256': sha(JAR),
             'artifact_stat': stat(JAR), 'prepared_at': time.time()}
    save(root / 'prepared.json', state)
    print(json.dumps({'prepared': str(root.relative_to(WORKSPACE)),
                      'bytes': before['apk']['st_size'], 'sha256': before['sha256']}), flush=True)


class Smoke:
    def __init__(self, root):
        self.root = root.resolve()
        assert self.root.is_relative_to(WORKSPACE / 'build/smoke')
        self.state = json.loads((root / 'prepared.json').read_text())
        self.apk = Path(self.state['input'])
        self.cache = self.apk.with_name(self.apk.name + '.jadx_mcp_cache')
        self.evidence = self.cache / ('e2e-evidence-' + uuid.uuid4().hex[:8])
        self.evidence.mkdir(parents=True)
        self.cwd = self.root / ('master-cwd-' + uuid.uuid4().hex[:8])
        self.cwd.mkdir()
        self.rows, self.calls, self.sessions = [], [], []
        self.master = None
        self.pid_records = {}
        self.started = time.monotonic()
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.report = {'artifact_sha256': sha(JAR), 'driver_sha256': sha(Path(__file__)),
                       'input_sha256': sha(self.apk), 'mode': 'HEADLESS HTTP packaged JAR',
                       'rows': self.rows, 'calls': self.calls}

    def row(self, name, status='PASS', **detail):
        self.rows.append({'name': name, 'status': status, **detail})
        print(json.dumps(self.rows[-1], ensure_ascii=False), flush=True)

    def step(self, name, fn):
        start = time.monotonic()
        try:
            value = fn()
            self.row(name, elapsed_ms=round((time.monotonic() - start) * 1000))
            return value
        except Exception as exc:
            self.row(name, 'FAIL', error=str(exc)[:400], elapsed_ms=round((time.monotonic() - start) * 1000))
            return None

    @staticmethod
    def require(condition, message):
        if not condition:
            raise AssertionError(message)

    def api(self, operation, params=None, expected=None):
        params = dict(params or {})
        params.setdefault('timeout_ms', 60000)
        start = time.monotonic()
        request = urllib.request.Request(self.url + '/api/' + operation,
                                        data=json.dumps(params).encode(),
                                        headers={'Content-Type': 'application/json'})
        try:
            response = self.opener.open(request, timeout=params['timeout_ms'] / 1000 + 15)
        except urllib.error.HTTPError as exc:
            response = exc
        with response:
            raw = response.read(2 * 1024 * 1024 + 1)
            self.require(len(raw) <= 2 * 1024 * 1024, 'HTTP response exceeded smoke bound')
            body = json.loads(raw)
            status = response.status
        record = {'operation': operation, 'params': params, 'http_status': status,
                  'elapsed_ms': round((time.monotonic() - start) * 1000),
                  'response': body, 'response_bytes': len(raw)}
        self.calls.append(record)
        save(self.evidence / f'{len(self.calls):03}-{operation}.json', record)
        error = body.get('error')
        if expected:
            self.require(error and error.get('code') in expected,
                         f'{operation}: expected {expected}, got HTTP {status}, {str(error)[:250]}')
            return body
        self.require(status == 200 and not error, f'{operation}: HTTP {status} {str(error)[:300]}')
        return body['result']

    def process_snapshot(self):
        output = subprocess.check_output(['ps', '-axo', 'pid=,ppid=,command='], text=True, timeout=5)
        entries = {}
        for line in output.splitlines():
            parts = line.strip().split(None, 2)
            if len(parts) == 3:
                entries[int(parts[0])] = (int(parts[1]), parts[2])
        if self.master:
            for pid, (parent, command) in entries.items():
                if pid == self.master.pid or (parent == self.master.pid and 'com.yyang.jadxmcp.worker.WorkerMain' in command):
                    self.pid_records[pid] = command
        return entries

    def start(self):
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0))
            port = reservation.getsockname()[1]
        self.url = f'http://127.0.0.1:{port}'
        self.report['port'] = port
        temp = self.evidence / 'master-temp'
        temp.mkdir()
        command = [str(JAVA), '-Xmx256m', '-Djava.awt.headless=true',
                   '-Djava.io.tmpdir=' + str(temp),
                    '-Dlogback.configurationFile=' + str(Path(__file__).with_name('apk-e2e-logback.xml')),
                   '-jar', str(JAR), '--host', '127.0.0.1', '--port', str(port), '--worker-heap', '1g']
        self.report['command'] = command
        self.master = subprocess.Popen(command, cwd=self.cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       start_new_session=True)
        self.report['master_pid'] = self.master.pid
        self.log_tail = bytearray()

        def drain():
            while block := self.master.stdout.read(4096):
                self.log_tail.extend(block)
                del self.log_tail[:-256 * 1024]
        self.log_thread = threading.Thread(target=drain, daemon=True)
        self.log_thread.start()
        end = time.monotonic() + 30
        while time.monotonic() < end:
            if self.master.poll() is not None:
                raise RuntimeError('Master exited during startup')
            try:
                with self.opener.open(self.url + '/status', timeout=1) as response:
                    status = response.read(64 * 1024).decode()
                    self.require(status.startswith('server: running\n'), 'Server status was not running')
                    self.report['server_status'] = status
                    return status
            except (urllib.error.URLError, TimeoutError):
                time.sleep(0.1)  # Bounded service readiness, not background-tool polling.
        raise TimeoutError('Master readiness exceeded 30 seconds')

    def create(self):
        before = set(self.cache.glob('session-*'))
        info = self.api('session_create', {'type': 'HEADLESS', 'apk_path': str(self.apk),
                                         'deobf': False, 'max_heap': '1g', 'timeout_ms': 120000})
        self.sessions.append(info['session_id'])
        after = set(self.cache.glob('session-*'))
        new = after - before
        self.require(info['backend'] == 'HEADLESS' and info['state'] == 'READY', 'Session not headless/ready')
        self.require(len(new) == 1, 'Session did not create exactly one exclusive child')
        owned = new.pop()
        self.require(owned.parent.name == self.apk.name + '.jadx_mcp_cache', 'Wrong actual cache name')
        self.require((owned / '.jadx-mcp-owner').is_file(), 'Missing ownership marker')
        self.report.setdefault('session_layouts', []).append({
            'directory': str(owned.relative_to(self.cache)),
            'entries_after_ready': sorted(path.name for path in owned.iterdir())})
        self.process_snapshot()
        return info, owned

    def suite(self):
        self.step('status_loopback', self.start)
        if not self.master or self.master.poll() is not None:
            return
        created = self.step('create_headless_first_and_owned_cache_layout', self.create)
        if not created:
            return
        first, first_dir = created
        sid = first['session_id']
        base = {'session_id': sid}
        summary = self.step('summary', lambda: self.api('meta_summary', base)) or {}
        self.step('summary_actual_classes', lambda: self.require(summary.get('classes_count', 0) > 0, 'No actual APK classes'))
        manifest = self.step('manifest_all', lambda: self.api('meta_manifest', base)) or {}
        activities = self.step('manifest_activities', lambda: self.api('meta_manifest', {**base, 'component_type': 'ACTIVITY'})) or {}
        exported = self.step('manifest_exported_activities', lambda: self.api('meta_manifest', {**base, 'component_type': 'ACTIVITY', 'only_exported': True})) or {}
        self.step('exported_filter_consistency', lambda: self.require(
            all(x['type'] == 'ACTIVITY' and x['exported'] for x in exported.get('components', [])) and
            {x['class_name'] for x in exported.get('components', [])} ==
            {x['class_name'] for x in activities.get('components', []) if x['exported']},
            'Exported activity result differs from filtered activity metadata'))
        names = self.step('class_list_main_app', lambda: self.api('meta_class', {**base, 'main_app': True, 'page': {'count': 30}})) or {}
        candidates = [summary.get('main_activity')] + [x['class_name'] for x in activities.get('components', [])] + names.get('classes', [])
        candidates = list(dict.fromkeys(x for x in candidates if x and not x.endswith('.R') and '.R$' not in x))
        selected, details = None, None
        for candidate in candidates[:8]:
            try:
                result = self.api('meta_class', {**base, 'class_name': candidate})
                if result.get('methods'):
                    selected, details = candidate, result
                    break
            except Exception:
                continue
        if selected:
            self.row('class_metadata_actual', methods=len(details['methods']), fields=len(details.get('fields', [])))
            source = self.step('java_source', lambda: self.api('decompile', {**base, 'class_name': selected, 'target': 'JAVA'})) or {}
            text = source.get('text', {}).get('content', '')
            self.step('java_source_nonempty', lambda: self.require(bool(text.strip()), 'Empty Java source'))
            cached = self.step('java_cache_repeat', lambda: self.api('decompile', {**base, 'class_name': selected, 'target': 'JAVA'})) or {}
            self.step('java_cache_repeat_consistent', lambda: self.require(cached.get('text') == source.get('text'), 'Repeated source changed without an edit'))
            methods = [m for m in details['methods'] if not m['name'].startswith('<')]
            methods.sort(key=lambda m: (m['name'] != 'onCreate', len(m.get('parameter_types', []))))
            if methods:
                method = methods[0]
                exact = self.step('exact_method_source', lambda: self.api('decompile', {**base, 'class_name': selected, 'target': 'METHOD',
                                      'method_name': method['name'], 'method_signature': method['descriptor']}))
                if exact:
                    self.step('exact_method_identity_and_nonempty', lambda: self.require(
                        exact.get('method_name') == method['name'] and bool(exact.get('text', {}).get('content', '').strip()),
                        'Method identity/source not returned'))
            else:
                self.row('exact_method_source', 'NO_OBJECT', reason='Selected class has no non-constructor method')
            smali = self.step('smali_source', lambda: self.api('decompile', {**base, 'class_name': selected, 'target': 'SMALI'})) or {}
            smali_text = smali.get('text', {}).get('content', '')
            self.step('smali_nonempty', lambda: self.require('.class ' in smali_text and '.method ' in smali_text, 'No actual Smali class/method'))
            search = self.step('class_search', lambda: self.api('search', {**base, 'scope': 'CLASS', 'term': selected.rsplit('.', 1)[-1],
                                    'package_name': selected.rsplit('.', 1)[0], 'budget': {'max_scan': 10000, 'max_decompile': 0}})) or {}
            self.step('class_search_has_actual_hit', lambda: self.require(bool(search.get('hits')), 'Class search returned no selected-class hit'))
            literals = re.findall(r'const-string(?:/jumbo)?[^\n]*?"([^"\\]{3,100})"', smali_text)
            if not literals:
                literals = re.findall(r'"([^"\\\n]{3,80})"', text)
            if literals:
                constants = self.step('dex_string_constants_search', lambda: self.api('search', {**base, 'scope': 'STRING', 'term': literals[0],
                                           'string_mode': 'DEX_CONSTANTS', 'package_name': selected.rsplit('.', 1)[0],
                                           'budget': {'max_scan': 10000, 'max_decompile': 0}})) or {}
                self.step('dex_constants_actual_hit_no_decompile', lambda: self.require(
                    bool(constants.get('hits')) and constants.get('stats', {}).get('decompiled') == 0,
                    'No actual constant hit or unexpected source access'))
            else:
                self.row('dex_string_constants_search', 'NO_OBJECT', reason='No searchable literal observed in selected class')
            referenced = False
            for candidate in candidates[:8]:
                xref = self.step('xref_class_candidate', lambda candidate=candidate: self.api('xref', {**base, 'target_type': 'CLASS', 'target': {'class_name': candidate}, 'page': {'count': 5}}))
                if xref and xref.get('references'):
                    self.row('xref_actual_reference', references=len(xref['references']))
                    referenced = True
                    break
            if not referenced:
                self.row('xref_actual_reference', 'NO_HIT', reason='Bounded actual-class candidates have no code references')
            renamed = 'HeadlessSmokeRenamedActivity'
            mutation = self.step('rename_in_memory', lambda: self.api('rename', {**base, 'target_type': 'CLASS',
                                  'target': {'class_name': selected}, 'new_name': renamed}))
            if mutation:
                self.step('rename_truthful_persistence', lambda: self.require(
                    mutation['state']['applied'] and mutation['state']['persisted'] is False and not mutation['state'].get('project_path'),
                    'Rename falsely reports persistence or was not applied'))
                after = self.step('java_after_rename', lambda: self.api('decompile', {**base, 'class_name': selected, 'target': 'JAVA'})) or {}
                self.step('rename_invalidates_cached_source', lambda: self.require(
                    renamed in after.get('text', {}).get('content', '') and after.get('text', {}).get('content') != text,
                    'Renamed source stayed stale'))
        else:
            self.row('class_metadata_actual', 'NO_OBJECT', reason='No resolvable class/method from bounded app candidates')
        resources = self.step('resource_list', lambda: self.api('resource', {**base, 'action': 'LIST', 'page': {'count': 10000}})) or {}
        strings = self.step('resource_strings', lambda: self.api('resource', {**base, 'action': 'STRINGS', 'page': {'count': 50}})) or {}
        if not strings.get('strings'):
            self.row('resource_strings_actual_entries', 'NO_OBJECT', reason='APK has no returned base string resources')
        else:
            self.row('resource_strings_actual_entries', count=len(strings['strings']))
        files = resources.get('files', [])
        manifest_file = next((x for x in files if x == 'AndroidManifest.xml'), None)
        if manifest_file:
            resource_file = self.step('resource_file_xml', lambda: self.api('resource', {**base, 'action': 'FILE', 'file_name': manifest_file})) or {}
            self.step('resource_file_actual_xml', lambda: self.require('<manifest' in resource_file.get('file', {}).get('text', {}).get('content', ''), 'Manifest resource XML missing'))
        else:
            self.row('resource_file_xml', 'NO_OBJECT', reason='Manifest file not in resource listing')
        public_name = next((x for x in files if x.endswith('res/values/public.xml')), None)
        if public_name:
            public = self.step('resource_public_ids_file', lambda: self.api('resource', {**base, 'action': 'FILE', 'file_name': public_name})) or {}
            xml = public.get('file', {}).get('text', {}).get('content', '')
            tags = re.findall(r'<public\b[^>]+>', xml)
            tag = next((t for t in tags if 'type="string"' in t and 'id="0x' in t), None)
            if tag:
                resource_id = re.search(r'id="(0x[0-9a-fA-F]+)"', tag)[1]
                resolved = self.step('resource_id_from_actual_public_xml', lambda: self.api('resource', {**base, 'action': 'ID', 'resource_id': resource_id})) or {}
                self.step('resource_id_resolves_name_value', lambda: self.require(
                    resolved.get('resource', {}).get('name') and resolved['resource'].get('value') is not None,
                    'Actual string ID lacks resolved name/value'))
            else:
                self.row('resource_id_from_actual_public_xml', 'NO_OBJECT', reason='No string resource ID in bounded public.xml text')
        else:
            self.row('resource_public_ids_file', 'NO_OBJECT', reason='No public.xml in resource listing; inspect actual R$string instead')
            string_class = next((name for name in names.get('classes', []) if name.endswith('R$string')), None)
            if string_class:
                resource_source = self.step('resource_r_string_smali', lambda: self.api('decompile', {**base, 'class_name': string_class, 'target': 'SMALI'})) or {}
                raw_smali = resource_source.get('text', {}).get('content', '')
                ids = re.findall(r'\.field[^\n]*?\s([^\s:]+):I\s*=\s*(0x7f[0-9a-fA-F]+)', raw_smali)
                if ids:
                    resource_id = ids[0][1]
                    resolved = self.step('resource_id_from_actual_r_string', lambda: self.api('resource', {**base, 'action': 'ID', 'resource_id': resource_id})) or {}
                    self.step('resource_id_resolves_name_value', lambda: self.require(
                        resolved.get('resource', {}).get('name') and resolved['resource'].get('value') is not None,
                        'Actual R$string ID lacks resolved name/value'))
                else:
                    self.row('resource_r_string_smali_fields', 'NO_OBJECT', reason='R$string is generated by JADX; no actual Smali fields')
                    try:
                        java_r = self.api('decompile', {**base, 'class_name': string_class, 'target': 'JAVA'})
                        self.row('resource_r_string_java', chars=len(java_r.get('text', {}).get('content', '')))
                    except AssertionError:
                        if self.calls[-1]['response'].get('error', {}).get('code') != 'NOT_FOUND':
                            raise
                        java_r = {}
                        self.row('resource_r_string_java', 'NO_OBJECT', reason='Synthetic R$string has no generated Java source; HTTP NOT_FOUND')
                    java_r_text = java_r.get('text', {}).get('content', '')
                    fields = re.findall(r'\bint\s+(\w+)\s*=\s*(0x[0-9a-fA-F]+|[0-9]+)', java_r_text)
                    actual_strings = {entry['name']: entry['value'] for entry in strings.get('strings', [])}
                    known = next(((name, value) for name, value in fields if name in actual_strings), None)
                    if known:
                        resource_id = known[1]
                        resolved = self.step('resource_id_from_actual_r_string_java', lambda: self.api('resource', {**base, 'action': 'ID', 'resource_id': resource_id})) or {}
                        self.step('resource_id_resolves_exact_string_name_value', lambda: self.require(
                            resolved.get('resource', {}).get('name', '').endswith('/' + known[0]) and
                            resolved['resource'].get('value') == actual_strings[known[0]],
                            'Actual R$string ID does not match the STRINGS name/value'))
                    else:
                        self.row('resource_id_from_actual_r_string_java', 'NO_OBJECT', reason='No ID field matching returned STRINGS entries in bounded R source')
            else:
                self.row('resource_id_from_actual_r_string', 'NO_OBJECT', reason='No R$string class in bounded app class list')
        if not any(row['name'] == 'resource_id_resolves_exact_string_name_value' and row['status'] == 'PASS' for row in self.rows):
            actual_ids = list(dict.fromkeys(re.findall(r'\b0x7f[0-9a-fA-F]{6}\b', locals().get('smali_text', ''))))
            resolved_actual = False
            for actual_id in actual_ids[:8]:
                resolved = self.api('resource', {**base, 'action': 'ID', 'resource_id': actual_id})
                if resolved.get('resource', {}).get('name'):
                    self.row('resource_id_from_actual_application_smali', resource_id=actual_id,
                             has_name=True, has_value=resolved['resource'].get('value') is not None)
                    resolved_actual = True
                    break
            if not resolved_actual:
                self.row('resource_id_from_actual_application_smali', 'NO_OBJECT',
                         reason='No resolvable resource ID among bounded actual application Smali constants')
        for label, op, params in [
            ('reject_unknown_field', 'meta_summary', {**base, 'smoke_unknown': True}),
            ('reject_page_zero', 'meta_class', {**base, 'page': {'count': 0}}),
            ('reject_lowercase_enum', 'search', {**base, 'scope': 'class', 'term': 'anything'}),
            ('reject_invalid_timeout', 'meta_summary', {**base, 'timeout_ms': 0}),
            ('reject_exported_without_type', 'meta_manifest', {**base, 'only_exported': True}),
            ('reject_smali_presentation', 'decompile', {**base, 'class_name': selected or 'missing', 'target': 'SMALI', 'slim': True}),
        ]:
            self.step(label, lambda op=op, params=params: self.api(op, params, expected={'INVALID_ARGUMENT'}))
        self.step('missing_class_typed', lambda: self.api('decompile', {**base, 'class_name': 'smoke.missing.DoesNotExist'}, expected={'NOT_FOUND'}))
        self.step('gui_only_explicitly_unsupported', lambda: self.api('fetch_current_class', base, expected={'UNSUPPORTED'}))
        sibling = self.step('create_same_apk_second_exclusive_session', self.create)
        if sibling:
            second, second_dir = sibling
            sid2 = second['session_id']
            sentinel = second_dir / 'smoke-active-sentinel'
            sentinel.write_text('active sibling')
            self.report['preclear_worker_files'] = {
                str(directory.relative_to(self.cache)): [str(p.relative_to(directory)) for p in directory.rglob('*') if p.is_file()][:80]
                for directory in (first_dir, second_dir)}
            self.step('worker_logs_and_plugin_dirs_inside_each_session', lambda: self.require(
                all((directory / 'logs/server.log').is_file() and
                    all((directory / child).is_dir() for child in ('jvm-temp', 'plugin-config', 'plugin-cache', 'temp'))
                    for directory in (first_dir, second_dir)), 'Worker runtime data is not in the exclusive session directory'))
            self.report['native_input_warnings'] = []
            for directory in (first_dir, second_dir):
                logfile = directory / 'logs/error.log'
                if logfile.exists():
                    lines = logfile.read_text(errors='replace').splitlines()
                    self.report['native_input_warnings'] += [line[:400] for line in lines if 'Unknown constant pool tag' in line or 'Failed to load class data' in line][:8]
            self.step('session_directories_are_distinct', lambda: self.require(first_dir != second_dir and first_dir.parent == second_dir.parent, 'Directories shared'))
            if selected:
                other_source = self.step('second_session_unmodified_source', lambda: self.api('decompile', {'session_id': sid2, 'class_name': selected})) or {}
                self.step('rename_isolation_between_workers', lambda: self.require(
                    'HeadlessSmokeRenamedActivity' not in other_source.get('text', {}).get('content', '') and
                    bool(other_source.get('text', {}).get('content')), 'Sibling sees rename or has no source'))
            self.step('close_first_clear_cache', lambda: self.api('session_close', {**base, 'clear_cache': True}))
            self.step('clear_cache_preserves_active_sibling_input_and_native_project', lambda: self.require(
                not first_dir.exists() and sentinel.read_text() == 'active sibling' and sha(self.apk) == self.state['copy_initial_sha256'] and
                self.native.read_text() == 'native project sentinel - must remain unchanged', 'Unsafe or incomplete clear_cache'))
            self.step('sibling_still_analyzes_after_first_clear', lambda: self.api('meta_summary', {'session_id': sid2}))
            start = time.monotonic()
            try:
                timed = self.api('search', {'session_id': sid2, 'scope': 'CODE', 'term': 'HeadlessSmokeImpossibleNeedle',
                                           'budget': {'max_scan': 10000, 'max_decompile': 10000}, 'timeout_ms': 1})
                self.row('bounded_deadline', 'NOT_TRIGGERED', elapsed_ms=round((time.monotonic() - start) * 1000), reason='Request completed before deadline; no false timeout claim')
            except Exception as exc:
                body = self.calls[-1]['response'] if self.calls else {}
                code = body.get('error', {}).get('code')
                self.row('bounded_deadline', 'PASS' if code == 'DEADLINE_EXCEEDED' else 'FAIL',
                         code=code, elapsed_ms=round((time.monotonic() - start) * 1000), error=str(exc)[:200])
            self.step('close_second_clear_cache_after_deadline', lambda: self.api('session_close', {'session_id': sid2, 'clear_cache': True}))
            self.step('second_owned_directory_deleted', lambda: self.require(not second_dir.exists(), 'Second session directory remains'))

    def finish(self):
        self.process_snapshot()
        for session in reversed(self.sessions):
            try:
                self.api('session_close', {'session_id': session, 'clear_cache': True, 'timeout_ms': 10000})
            except Exception as exc:
                self.report.setdefault('finally_close_errors', []).append(str(exc)[:250])
        if self.master:
            self.master.terminate()
            try:
                self.master.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(self.master.pid, signal.SIGKILL)
                self.master.wait(timeout=5)
            self.log_thread.join(timeout=3)
            (self.evidence / 'master-tail.log').write_bytes(self.log_tail)
        entries = self.process_snapshot()
        alive = [pid for pid, command in self.pid_records.items() if pid in entries and entries[pid][1] == command]
        # Any survivor is this driver's child only; never kill unrelated JVMs.
        for pid in alive:
            os.kill(pid, signal.SIGTERM)
        if alive:
            time.sleep(1)
            entries = self.process_snapshot()
            alive = [pid for pid, command in self.pid_records.items() if pid in entries and entries[pid][1] == command]
            for pid in alive:
                os.kill(pid, signal.SIGKILL)
        self.report['observed_pids'] = sorted(self.pid_records)
        self.report['alive_after_master_shutdown'] = alive
        self.row('master_and_workers_reaped', 'PASS' if not alive else 'FAIL', observed=len(self.pid_records), survivors=alive)
        after = source_state()
        self.report['original_before'] = self.state['original_before']
        self.report['original_after'] = after
        self.step('original_apk_hash_and_metadata_unchanged', lambda: self.require(
            after['sha256'] == self.state['original_before']['sha256'] and after['apk'] == self.state['original_before']['apk'],
            'Original APK content/metadata changed'))
        self.step('downloads_and_original_jadx_metadata_unchanged', lambda: self.require(
            after['parent'] == self.state['original_before']['parent'] and after['siblings'] == self.state['original_before']['siblings'],
            'Original input directory or sibling metadata changed'))
        self.step('workspace_input_and_native_sentinel_unchanged', lambda: self.require(
            sha(self.apk) == self.state['copy_initial_sha256'] and self.native.read_text() == 'native project sentinel - must remain unchanged',
            'Workspace input/sentinel changed'))
        self.step('input_directory_only_expected_entries', lambda: self.require(
            {p.name for p in self.apk.parent.iterdir()} == {self.apk.name, self.native.name, self.cache.name},
            'Unexpected data appeared next to workspace input'))
        escapes = [str(p.relative_to(self.root)) for p in self.cwd.rglob('*') if p.is_file()]
        self.report['generated_files_outside_input_cache'] = escapes
        self.row('all_worker_generated_files_contained_in_input_cache', 'PASS' if not escapes else 'FAIL', escaped_files=escapes[:20])
        self.report['cache_tree'] = [str(p.relative_to(self.cache)) for p in self.cache.rglob('*')][:100]
        self.report['elapsed_seconds'] = round(time.monotonic() - self.started, 3)
        save(self.evidence / 'report-raw.json', self.report)
        save(self.root / 'latest-run.json', {'evidence': str(self.evidence), 'report': str(self.evidence / 'report-raw.json')})
        print(json.dumps({'report': str((self.evidence / 'report-raw.json').relative_to(WORKSPACE)),
                          'statuses': {status: sum(r['status'] == status for r in self.rows) for status in sorted({r['status'] for r in self.rows})}}), flush=True)

    def run(self):
        self.native = self.apk.with_name(self.apk.name + '.jadx')
        if not self.native.exists():
            self.native.write_text('native project sentinel - must remain unchanged')
        signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(SmokeDeadline('Driver terminated')))
        signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(SmokeDeadline('Driver global timeout')))
        signal.alarm(540)
        try:
            self.suite()
        except BaseException as exc:
            self.row('driver_unhandled', 'FAIL', error=repr(exc)[:400])
        finally:
            signal.alarm(0)
            self.finish()
        return 1 if any(row['status'] == 'FAIL' for row in self.rows) else 0


def publish(root):
    """Publish bounded API evidence, never app source, constants, user paths or bearer tokens."""
    root = root.resolve()
    if not root.is_relative_to(WORKSPACE / 'build/smoke'):
        raise ValueError('Expected an owned prepared root under build/smoke')
    latest = json.loads((root / 'latest-run.json').read_text())
    report = json.loads(Path(latest['report']).read_text())
    session_map = {}
    def private_symbol(value):
        return '<symbol-sha256:' + hashlib.sha256(value.encode()).hexdigest()[:12] + '>'
    def params_excerpt(params):
        result = {}
        for key, value in params.items():
            if key == 'session_id':
                value = session_map.setdefault(value, '<session-' + str(len(session_map) + 1) + '>')
            elif key == 'apk_path':
                value = '<WORKSPACE_COPY>/input/app-armeabi-v7a-release.apk'
            elif key in ('class_name', 'package_name', 'method_name'):
                value = private_symbol(value)
            elif key == 'term':
                value = '<observed-search-term-sha256:' + hashlib.sha256(value.encode()).hexdigest()[:12] + '>'
            elif key == 'target' and isinstance(value, dict):
                value = {k: private_symbol(v) if k in ('class_name', 'member_name') else v for k, v in value.items()}
            result[key] = value
        return result
    calls = []
    for call in report['calls']:
        # This driver uses REST's {version, operation, result} envelope; its epoch
        # excerpt intentionally does not mirror the smaller MCP session summary.
        body = call['response']
        result = body.get('result', {})
        excerpt = {}
        op = call['operation']
        if 'error' in body:
            excerpt = {'error_code': body['error']['code'], 'retryable': body['error'].get('retryable', False)}
        elif op == 'session_create':
            excerpt = {k: result[k] for k in ('backend', 'state', 'project_epoch') if k in result}
        elif op == 'meta_summary':
            excerpt = {'classes_count': result.get('classes_count'), 'has_main_activity': bool(result.get('main_activity')), 'deobf': result.get('deobf')}
        elif op == 'meta_manifest':
            components = result.get('components', [])
            excerpt = {'component_count': len(components), 'exported_count': sum(c['exported'] for c in components),
                       'activity_count': sum(c['type'] == 'ACTIVITY' for c in components),
                       'has_xml': bool(result.get('xml')), 'component_type': result.get('component_type')}
        elif op == 'meta_class':
            excerpt = {'classes_returned': len(result.get('classes', [])), 'methods_returned': len(result.get('methods', [])),
                       'fields_returned': len(result.get('fields', [])), 'page': result.get('page')}
        elif op == 'decompile':
            text = result.get('text', {})
            content = text.get('content', '')
            excerpt = {'chars_returned': len(content), 'total_chars': text.get('total_chars'), 'truncated': text.get('truncated'),
                       'media_type': text.get('media_type'), 'content_sha256': hashlib.sha256(content.encode()).hexdigest(),
                       'contains_smoke_rename': 'HeadlessSmokeRenamedActivity' in content,
                       'method_signature': result.get('method_signature')}
        elif op == 'search':
            excerpt = {'hits_returned': len(result.get('hits', [])), 'stats': result.get('stats'), 'page': result.get('page'), 'string_mode': result.get('string_mode')}
        elif op == 'xref':
            excerpt = {'references_returned': len(result.get('references', [])), 'page': result.get('page'),
                       'first_source': {k: private_symbol(v) if k in ('class_name', 'member_name') else v
                                        for k, v in (result.get('references') or [{}])[0].get('source', {}).items()}}
        elif op == 'rename':
            excerpt = {'state': result.get('state'), 'new_name': result.get('new_name')}
        elif op == 'resource':
            excerpt = {'action': result.get('action'), 'files_returned': len(result.get('files', [])),
                       'strings_returned': len(result.get('strings', [])), 'page': result.get('page')}
            if result.get('file'):
                file = result['file']
                excerpt['file'] = {'name': call['params'].get('file_name'), 'binary': file.get('binary'),
                                   'chars_returned': len((file.get('text') or {}).get('content', ''))}
            if result.get('resource'):
                resource = result['resource']
                excerpt['resource'] = {'id_hex': resource.get('id_hex'), 'name': private_symbol(resource['name']) if resource.get('name') else None,
                                       'has_value': resource.get('value') is not None,
                                       'value_sha256': hashlib.sha256(resource.get('value', '').encode()).hexdigest()}
        elif op == 'session_close':
            excerpt = {'closed': result.get('closed')}
        calls.append({'operation': op, 'request_excerpt': params_excerpt(call['params']), 'http_status': call['http_status'],
                      'elapsed_ms': call['elapsed_ms'], 'response_excerpt': excerpt,
                      'raw_response_sha256': hashlib.sha256(json.dumps(body, sort_keys=True).encode()).hexdigest()})
    output = {'mode': report['mode'], 'artifact_sha256': report['artifact_sha256'], 'driver_sha256': report['driver_sha256'],
              'input_sha256': report['input_sha256'], 'original_before': report['original_before'], 'original_after': report['original_after'],
              'elapsed_seconds': report['elapsed_seconds'], 'rows': report['rows'], 'calls': calls,
              'observed_pids': report['observed_pids'], 'alive_after_master_shutdown': report['alive_after_master_shutdown'],
              'generated_files_outside_input_cache': report['generated_files_outside_input_cache'],
              'session_layouts': report.get('session_layouts'), 'preclear_worker_files': report.get('preclear_worker_files'),
              'native_warning_summary': 'JADX Java input plugin reports an unparseable bundled Darwin jffi native library; selected DEX analysis still succeeds.' if report.get('native_input_warnings') else None,
               'raw_evidence_location': '<local generated output; not published>',
              'not_covered': ['APK install/execution', 'GUI', 'device debugger', 'all classes/resources exhaustively', 'MCP transport']}
    destination = root / 'summary.json'
    save(destination, output)
    print(json.dumps({'published': str(destination.relative_to(WORKSPACE)), 'calls': len(calls), 'rows': len(output['rows'])}), flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('prepare', help='hash authorized input and create an isolated copy')
    for name in ('run', 'publish'):
        command = commands.add_parser(name, help='run HTTP checks' if name == 'run' else 'write redacted summary.json')
        command.add_argument('root', type=Path, help='prepared directory under build/smoke')
    arguments = parser.parse_args()
    if arguments.command == 'prepare':
        prepare()
    elif arguments.command == 'run':
        raise SystemExit(Smoke(arguments.root).run())
    else:
        publish(arguments.root)
