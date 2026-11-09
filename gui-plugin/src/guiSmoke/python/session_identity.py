"""Actual MainWindow-generated names, native project changes and persistence over real MCP/REST.

Only copied inputs and newly spawned isolated JVMs are owned. Readiness polling is bounded;
native mutation-to-source readback is immediate, without a sleep or an edit retry.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import urllib.error
import urllib.request
import zipfile

BASE = Path(__file__).resolve().parents[4]
spec = importlib.util.spec_from_file_location('identity_helpers', BASE/'scripts/smoke/mcp_helper.py')
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def run(args):
    root = h.workspace('session-identity', seconds=600)
    (root/'tmp').mkdir()
    processes, logs, checks = [], [], []
    evidence = {'status': 'FAIL', 'scope': 'real isolated native GUI, MCP and REST', 'checks': checks}
    plugin = Path(args.plugin).resolve() if args.plugin else h.RELEASE/'jadx-mcp-gui-plugin.jar'
    master_jar = Path(args.master).resolve() if args.master else h.RELEASE/'jadx-mcp.jar'
    source = Path(args.input).resolve()
    original_digest = digest(source)
    for name in ('one', 'two', 'invalid'):
        (root/name).mkdir()
    sample = root/'one/sample.jar'
    apk = root/'one'/source.name
    second_apk = root/'two'/source.name
    shutil.copy2(BASE/'app/src/test/resources/sample.jar', sample)
    shutil.copy2(sample, root/'two/sample.jar')
    shutil.copy2(sample, root/'invalid/sample.jar')
    shutil.copy2(source, apk)
    shutil.copy2(source, second_apk)
    auxiliary = root/'one/auxiliary.jar'
    with zipfile.ZipFile(auxiliary, 'w') as archive:
        archive.writestr('assets/probe.txt', 'owned resource-only input')
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        port = sock.getsockname()[1]

    def require(value, label):
        checks.append({'check': label, 'passed': bool(value)})
        if not value:
            raise AssertionError(label)

    def start(command, label, environment=None):
        log = (root/(label+'.log')).open('w')
        logs.append(log)
        process = subprocess.Popen(command, cwd=root, env=environment or os.environ,
                                   stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT,
                                   text=True, start_new_session=True)
        processes.append(process)
        return process

    def call(operation, **parameters):
        error, payload = client.call(operation, dict(timeout_ms=60000, **parameters))
        require(not error, operation+' succeeds')
        return payload

    def sessions():
        error, payload = client.call('session_list', {'timeout_ms': 3000})
        if error:
            raise RuntimeError('session_list failed during readiness')
        return [s for s in payload if s['backend'] == 'GUI' and s['state'] == 'READY']

    def ready(name, excluded=()):
        return next((s for s in sessions() if (name is None or s['name'] == name) and s['session_id'] not in excluded), None)

    seen_sessions = set()

    def new_session(name, seconds=100):
        item = h.wait_for(lambda: ready(name, seen_sessions), seconds)
        seen_sessions.add(item['session_id'])
        evidence.setdefault('observed_gui_descriptors', []).append({
            'session_key_sha256': hashlib.sha256(item['session_id'].encode()).hexdigest(),
            **{key: item[key] for key in ('backend', 'name', 'project_epoch', 'state')}})
        return item

    def launch(label, inputs, invalid=False):
        home = root/label/'gui'
        flags = [f'-Djadx.mcp.url=http://127.0.0.1:{port}', f'-Djava.io.tmpdir={root/"tmp"}']
        if invalid:
            flags.append(f'-Djadx.mcp.smoke.savedOrigin=https://127.0.0.1:{port}')
        process = start([h.JAVA, *flags, '-cp', h.DIST+os.pathsep+str(BASE/'gui-plugin/build/classes/guiSmoke'),
                         'GuiBridgeIntegrationSmoke', str(home), str(plugin),
                         *map(str, inputs)], label,
                        dict(os.environ, JADX_CONFIG_DIR=str(root/label/'config'),
                             JADX_CACHE_DIR=str(root/label/'cache'), TMPDIR=str(root/'tmp')))
        h.wait_for(lambda: 'GUI_READY' in (root/(label+'.log')).read_text(), 100)
        return process

    def native(process, label, command):
        path = root/(label+'.log')
        before = path.read_text()
        count = before.count('DONE '+command+'\n')
        process.stdin.write(command+'\n')
        process.stdin.flush()
        def completed():
            if process.poll() is not None:
                raise SystemExit('Owned GUI exited during '+command)
            return path.read_text().count('DONE '+command+'\n') > count
        h.wait_for(completed, 40)
        return path.read_text()[len(before):]

    def fenced(session):
        stale = dict(session_id=session['session_id'], project_epoch=session['project_epoch']-1, timeout_ms=5000)
        for operation in ('session_close',):
            error, envelope = client.call(operation, stale)
            require(error and envelope.get('error', {}).get('code') == 'CONFLICT', operation+' rejects stale epoch via MCP')
            request = urllib.request.Request(f'http://127.0.0.1:{port}/api/{operation}',
                                             json.dumps(stale).encode(), {'Content-Type': 'application/json'})
            try:
                response = urllib.request.urlopen(request, timeout=10)
            except urllib.error.HTTPError as failure:
                response = failure
            with response:
                payload = json.loads(response.read())
                require(response.status == 409 and payload.get('error', {}).get('code') == 'CONFLICT',
                        operation+' rejects stale epoch via REST')
        current = ready(session['name'])
        require(current is not None and current['session_id'] == session['session_id'] and
                current['project_epoch'] == session['project_epoch'],
                'stale close leaves current GUI listed READY and unclosed')

    try:
        start([h.JAVA, f'-Duser.home={root/"master-home"}', f'-Djava.io.tmpdir={root/"tmp"}',
               '-jar', str(master_jar), '--port', str(port)], 'master')
        client = h.Mcp(port)
        h.wait_for(client.initialize, 30)
        first = launch('one', [sample, apk, auxiliary])
        a = new_session(sample.name)
        second = launch('two', [second_apk, root/'two/sample.jar'])
        b = new_session(apk.name)
        require(a['name'] != b['name'], 'two actual GUI hosts register distinct input basenames')
        require('INPUT_NAMES=[sample.jar]' in native(first, 'one', 'plugin-state'), 'first name comes from native project inputs')
        state_a = native(first, 'one', 'plugin-state')
        instance_a = next(line.split('=', 1)[1] for line in state_a.splitlines() if line.startswith('PROJECT_INSTANCE_ID='))
        headless = call('session_create', type='HEADLESS', apk_path=str(apk), deobf=False)['session_id']
        summary = call('meta_summary', session_id=headless)
        owner = summary['main_activity']
        details = call('meta_class', session_id=headless, class_name=owner)
        method = next(m for m in details['methods'] if not m['name'].startswith('<'))
        fixture = {'class_name': owner, 'method_name': method['name'], 'method_signature': method['descriptor']}
        (root/'fixture.json').write_text(json.dumps(fixture))
        evidence['fixture_discovered'] = True
        for operation, parameters in (
                ('meta_summary', {}), ('meta_class', {'class_name': owner}),
                ('decompile', {'class_name': owner}),
                ('decompile', {'target': 'METHOD', 'class_name': owner, 'method_name': method['name'], 'method_signature': method['descriptor']})):
            gui_result = call(operation, session_id=b['session_id'], **parameters)
            worker_result = call(operation, session_id=headless, **parameters)
            if operation == 'meta_summary':
                require(all(gui_result[k] == worker_result[k] for k in ('classes_count', 'main_activity', 'main_package')),
                        'same APK GUI/HEADLESS metadata raw identity agrees')
            elif operation == 'meta_class':
                require(gui_result['class_name'] == worker_result['class_name'] == owner and
                        {(m['name'], m['descriptor']) for m in gui_result['methods']} ==
                        {(m['name'], m['descriptor']) for m in worker_result['methods']}, 'same APK exact class/method lookup agrees')
            else:
                require(gui_result['class_name'] == worker_result['class_name'] and gui_result['text']['content'] and worker_result['text']['content'],
                        'same APK source lookup resolves identical class with nonempty source')
                evidence.setdefault('same_apk_source_comparison', []).append({
                    'target': parameters.get('target', 'JAVA'),
                    'gui_sha256': hashlib.sha256(gui_result['text']['content'].encode()).hexdigest(),
                    'headless_sha256': hashlib.sha256(worker_result['text']['content'].encode()).hexdigest(),
                    'equal': gui_result['text']['content'] == worker_result['text']['content']})
                require(gui_result['text']['content'] == worker_result['text']['content'],
                        'same APK GUI/HEADLESS generated source bytes agree for matching fixture')
        call('session_close', session_id=headless, clear_cache=True)
        native(first, 'one', 'load:1')
        changed = new_session(apk.name)
        state_b = native(first, 'one', 'plugin-state')
        instance_b = next(line.split('=', 1)[1] for line in state_b.splitlines() if line.startswith('PROJECT_INSTANCE_ID='))
        require(instance_a != instance_b and instance_a != 'null' and instance_b != 'null',
                'native project switch replaces the plugin instance identity as well as bridge/client')
        require(changed['session_id'] != a['session_id'], 'native A-to-B project switch registers a fresh session ID')
        listed_ids = {item['session_id'] for item in sessions()}
        require(a['session_id'] not in listed_ids and changed['session_id'] in listed_ids,
                'old project session is unregistered and only B remains listed for that GUI')
        require(changed['name'] == apk.name, 'A to B switch publishes B name in session_list')
        error, envelope = client.call('decompile', dict(session_id=a['session_id'], project_epoch=a['project_epoch'], class_name='com.yyang.sample.HelloSample', timeout_ms=5000))
        require(error and envelope.get('error', {}).get('code') in ('CLOSED', 'NOT_FOUND'), 'old project request cannot execute against replacement project session')
        still_b = ready(apk.name)
        require(still_b is not None and still_b['session_id'] == changed['session_id'] and
                still_b['project_epoch'] == changed['project_epoch'], 'rejected A request leaves B READY and unchanged')
        fenced(changed)
        native(first, 'one', 'load:0')
        a = new_session(sample.name)
        native(second, 'two', 'load:1')
        twin = new_session(sample.name)
        require(a['session_id'] != twin['session_id'] and len([s for s in sessions() if s['name'] == sample.name]) == 2,
                'two real GUI instances keep identical short filenames and distinct session IDs')
        native(first, 'one', 'bridge-name:same-epoch.jar')
        renamed = new_session('same-epoch.jar', 30)
        require(renamed['project_epoch'] == a['project_epoch'], 'explicit bridge API same-epoch name update is not dropped')
        native(first, 'one', 'bridge-name:restore')
        a = new_session(sample.name, 30)
        native(first, 'one', 'load:0,2')
        multi = new_session(None)
        state = native(first, 'one', 'plugin-state')
        names = next(line for line in state.splitlines() if line.startswith('INPUT_NAMES='))[len('INPUT_NAMES=['):-1].split(', ')
        main_name = 'base.apk' if 'base.apk' in names else names[0]
        require(set(names) == {sample.name, auxiliary.name} and multi['name'] == main_name+' (+1 file)' and
                '/' not in multi['name'] and '\\' not in multi['name'],
                'real multi-input name follows captured host order and count without absolute paths')
        native(first, 'one', 'load:0')
        a = new_session(sample.name)
        native(second, 'two', 'close-project')
        h.wait_for(lambda: len(sessions()) == 1, 30)
        require(len(sessions()) == 1, 'native close removes its project registration without a replacement')
        sample_owner = 'com.yyang.sample.HelloSample'
        call('decompile', session_id=a['session_id'], class_name=sample_owner)
        call('decompile', session_id=a['session_id'], class_name=sample_owner)
        require(call('get_cache_stats', session_id=a['session_id'])['hits'] > 0, 'warm real GUI source cache before native manual event')
        native(first, 'one', 'native-rename:ManualEventProbe')
        text = call('decompile', session_id=a['session_id'], class_name=sample_owner)['text']['content']
        require('class ManualEventProbe' in text, 'native manual rename immediately invalidates cached source without mutation/read sleep')
        native(first, 'one', 'native-rename:HelloSample')
        require('class HelloSample' in call('decompile', session_id=a['session_id'], class_name=sample_owner)['text']['content'], 'native manual rename restoration is immediately readable')
        native(first, 'one', 'policy-ask')
        state = call('add_comment', session_id=a['session_id'], class_name=sample_owner, comment='owned-ask-probe')['state']
        require(state['applied'] and not state['persisted'] and not (root/'one/gui/owned-project.jadx').exists(), 'ASK edit stays in memory without Save As')
        native(first, 'one', 'save-owned-project')
        native(first, 'one', 'policy-always')
        state = call('add_comment', session_id=a['session_id'], class_name=sample_owner, comment='owned-always-probe')['state']
        require(state['applied'] and state['persisted'] and (root/'one/gui/owned-project.jadx').is_file(), 'ALWAYS acknowledges actual owned native project persistence')
        native(first, 'one', 'close-project')
        native(first, 'one', 'reopen-owned-project')
        a = new_session(sample.name)
        require(any(c['comment'] == 'owned-always-probe' for c in call('list_comments', session_id=a['session_id'])['comments']), 'saved native comment survives owned project close and reopen')
        call('add_comment', session_id=a['session_id'], class_name=sample_owner, comment='')
        invalid = launch('invalid', [root/'invalid/sample.jar'], invalid=True)
        native(invalid, 'invalid', 'open')
        h.wait_for(lambda: 'bound=true configured=false bridge=false' in native(invalid, 'invalid', 'plugin-state'), 100)
        require(len(sessions()) == 1, 'failed saved HTTPS origin does not fall through to a reachable default Master')
        require('Saved address unsupported' in native(invalid, 'invalid', 'status'), 'configuration load failure remains visible')
        native(invalid, 'invalid', 'apply')
        repaired = new_session(sample.name, 30)
        require(repaired['name'] == sample.name, 'explicit valid Apply recovers the isolated invalid configuration')
        fenced(repaired)
        require(digest(source) == original_digest == digest(apk) == digest(second_apk), 'authorized original and both APK copies remain byte-identical')
        evidence['status'] = 'PASS'
    except BaseException as failure:
        evidence['failure_type'] = type(failure).__name__
        evidence['failure'] = str(failure) if isinstance(failure, AssertionError) else 'see owned local log'
        raise
    finally:
        signal.alarm(0)
        for process in reversed(processes):
            h.stop(process)
            try:
                os.killpg(process.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        for log in logs:
            log.close()
        evidence['owned_processes_reaped'] = all(p.poll() is not None for p in processes)
        evidence['artifact_sha256'] = {name: digest(h.RELEASE/name) for name in ('jadx-mcp.jar', 'jadx-mcp-gui-plugin.jar')}
        (root/'evidence.json').write_text(json.dumps(evidence, indent=2)+'\n')
        print(evidence['status'], len(checks), 'checks', root, flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True, help='Authorized APK, read only and copied into the owned workspace')
    parser.add_argument('--plugin', help='Staged plugin package; release plugin is the default')
    parser.add_argument('--master', help='Staged master package; release master is the default')
    run(parser.parse_args())
