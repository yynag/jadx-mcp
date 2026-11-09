"""Owned real GUI: Apply to offline port, recover when Master appears, then switch back.
No user preferences/window/server access. Ports are allocated by the driver, not assumed free.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import time

BASE = Path(__file__).resolve().parents[4]
spec = importlib.util.spec_from_file_location('helper', BASE/'scripts/smoke/mcp_helper.py')
h = importlib.util.module_from_spec(spec)
spec.loader.exec_module(h)


def free_port(ip='127.0.0.1'):
    with socket.socket(socket.AF_INET6 if ':' in ip else socket.AF_INET) as listener:
        listener.bind((ip, 0))
        return listener.getsockname()[1]


def run(args):
    root = h.workspace('port-switch')
    source = root/'sample.jar'
    shutil.copy2(BASE/'app/src/test/resources/sample.jar', source)
    first, second = free_port(), free_port(args.target_ip)
    checks, processes, files = [], [], []
    gui = None
    evidence = {'status':'FAIL', 'failure_injection':args.fail_preferences, 'target_ip':args.target_ip}
    def require(value, label):
        checks.append({'check':label, 'passed':bool(value)})
        if not value:
            raise AssertionError(label)
    def start_master(port, ip='127.0.0.1'):
        log = (root/f'master-{port}.log').open('w'); files.append(log)
        process = subprocess.Popen([h.JAVA, '-jar', str(h.RELEASE/'jadx-mcp.jar'), '--host', ip, '--port', str(port)],
                                   cwd=root, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        client = h.Mcp(port)
        client.url = f'http://{"["+ip+"]" if ":" in ip else ip}:{port}/mcp'
        h.wait_for(client.initialize, 25)
        return client
    def ready(client):
        err, payload = client.call('session_list', {'timeout_ms':3000})
        if err:
            return None
        return next((s for s in payload
                     if s['backend']=='GUI' and s['state']=='READY'), None)
    def command(name):
        before = (root/'gui.log').read_text().count('DONE '+name)
        gui.stdin.write(name+'\n'); gui.stdin.flush()
        h.wait_for(lambda:(root/'gui.log').read_text().count('DONE '+name)>before, 15)
    def status():
        command('status')
        return (root/'gui.log').read_text().rsplit('STATUS_VISIBLE',1)[-1].split('DONE status')[0]
    try:
        client1 = start_master(first)
        log = (root/'gui.log').open('w'); files.append(log)
        plugin = Path(args.plugin).resolve() if args.plugin else h.RELEASE/'jadx-mcp-gui-plugin.jar'
        gui = subprocess.Popen([h.JAVA, f'-Djadx.mcp.url=http://127.0.0.1:{first}',
            f'-Djadx.mcp.smoke.targetPort={second}',
            f'-Djadx.mcp.smoke.targetIp={args.target_ip}',
            f'-Djadx.mcp.smoke.failPreferences={str(args.fail_preferences).lower()}',
            '-cp', h.DIST+os.pathsep+str(BASE/'gui-plugin/build/classes/guiSmoke'),
            'GuiBridgeIntegrationSmoke', str(root/'gui'), str(plugin), str(source)], cwd=root,
            env=dict(os.environ, JADX_CONFIG_DIR=str(root/'config'), JADX_CACHE_DIR=str(root/'cache')),
            stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.PIPE, text=True)
        h.wait_for(lambda:ready(client1), 90)
        command('open')
        command('invalid-port')
        require('Port must be a number from 1 to 65535' in status(), 'invalid port rejected inline')
        command('apply-target')
        if args.fail_preferences:
            h.wait_for(lambda:'Could not save settings (BackingStoreException)' in status(), 15)
            require('connection unchanged' in status(), 'actual preferences failure diagnosed without raw detail')
            require('injected-private-detail' not in status(), 'private exception message not exposed')
            require(ready(client1) is not None, 'failed save leaves original bridge connected')
        else:
            h.wait_for(lambda:'LABEL Applied!' in status(), 15)
            require(True, 'offline destination still acknowledges saved settings Applied!')
            h.wait_for(lambda:'RECONNECTING' in status(), 15)
            require('Connection settings failed' not in status(), 'offline HTTP is not a settings failure')
            require('Retry in ' in status(), 'offline destination enters bounded backoff')
            client2 = start_master(second, args.target_ip)
            session = h.wait_for(lambda:ready(client2), 50)
            require(True, 'service started later recovers without another Apply')
            err, _ = client2.call('meta_summary', {'session_id':session['session_id'], 'timeout_ms':10000})
            require(not err, 'business command succeeds on newly started destination')
            # Repeated actual endpoint replacement, not same-address Apply.
            for cycle in range(2):
                command('apply')
                h.wait_for(lambda:ready(client1), 30)
                require('LABEL Applied!' in status(), f'cycle {cycle} switch back to existing service succeeds')
                command('apply-target')
                h.wait_for(lambda:ready(client2), 30)
                require('LABEL Applied!' in status(), f'cycle {cycle} switch to other existing service succeeds')
        command('quit'); gui.wait(timeout=15)
        evidence['status']='PASS'
    except BaseException as failure:
        evidence['failure']=repr(failure)
        raise
    finally:
        # The scenario deadline must not interrupt bounded owned-child cleanup.
        signal.alarm(0)
        evidence['gui_exit']=h.stop(gui)
        evidence['master_exits']=[h.stop(process) for process in processes]
        for file in files: file.close()
        evidence['checks']=checks
        evidence['owned_processes_reaped']=all(p.poll() is not None for p in processes) and (gui is None or gui.poll() is not None)
        (root/'evidence.json').write_text(json.dumps(evidence, indent=2))
        print(evidence['status'], len(checks), root, flush=True)


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plugin', help='Optional plugin artifact to exercise in the isolated GUI')
    parser.add_argument('--fail-preferences', action='store_true')
    parser.add_argument('--target-ip', choices=('127.0.0.1', '::1'), default='127.0.0.1', help='Owned loopback destination; ::1 also exercises an IP-family switch')
    run(parser.parse_args())
