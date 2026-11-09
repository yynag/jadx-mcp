"""Owned isolated Master + release GUI + MCP + HEADLESS regression (no user service).

Prerequisites: JDK 21, JADX_GUI_DISTRIBUTION=existing JADX 1.5.6 all JAR,
build/release packages and :gui-plugin:compileGuiMenuSmoke host classes. No downloads.
Copies sample.jar by default; acceptance optionally copies an authorized APK/descriptor.
The proxy loses one edit acknowledgement; finally reaps only our JVMs and proxy.
Generated copies/config/cache/logs remain in the printed build/smoke run for review.
"""
import argparse
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import threading
import time
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = Path(__file__).resolve().parents[2]
helper_file = Path(__file__).with_name('mcp_helper.py')
spec = importlib.util.spec_from_file_location('mcp_smoke_helpers', helper_file)
helpers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)
Mcp, wait_for, stop = helpers.Mcp, helpers.wait_for, helpers.stop
JAVA, DIST = helpers.JAVA, helpers.DIST
ROOT = None
evidence = {'assertions': [], 'status': 'RUNNING'}
master = gui = None
logs = []
proxy = None
drop = threading.Event()
dropped = threading.Event()
edit_completions = 0

def require(value, label):
    evidence['assertions'].append({'check': label, 'passed': bool(value)})
    if not value:
        raise AssertionError(label)

def free_port():
    with socket.socket() as s:
        s.bind(('127.0.0.1', 0))
        return s.getsockname()[1]

port = None

class Proxy(BaseHTTPRequestHandler):
    def log_message(self, *args): pass
    def do_POST(self):
        global edit_completions
        try:
            body = self.rfile.read(int(self.headers['Content-Length']))
            if self.path.endswith('/complete') and json.loads(body).get('completion', {}).get('operation') == 'add_comment':
                edit_completions += 1
            req = urllib.request.Request(f'http://127.0.0.1:{port}{self.path}', body,
                                         {'Content-Type': 'application/json'}, method='POST')
            try:
                with urllib.request.urlopen(req, timeout=35) as response:
                    status, result = response.status, response.read()
            except urllib.error.HTTPError as error:
                status, result = error.code, error.read()
            if self.path.endswith('/complete') and drop.is_set():
                drop.clear(); dropped.set()
                self.connection.shutdown(socket.SHUT_RDWR); self.connection.close()
                return
            self.send_response(status); self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(result))); self.end_headers(); self.wfile.write(result)
        except (OSError, urllib.error.URLError):
            self.close_connection = True

def start_master():
    global master, client
    log = (ROOT / f'master-{len(logs)}.log').open('w'); logs.append(log)
    master = subprocess.Popen([JAVA, '-jar', str(helpers.RELEASE/'jadx-mcp.jar'), '--port', str(port)],
                              cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    client = Mcp(port); wait_for(client.initialize, 30)

def native(command):
    log = ROOT/'gui.log'
    before = log.read_text()
    count = before.count('DONE '+command)
    gui.stdin.write(command+'\n'); gui.stdin.flush()
    wait_for(lambda: log.read_text().count('DONE '+command)>count, 20)
    return log.read_text()[len(before):].split('DONE '+command)[0]

def status():
    # Only this response counts: after unload there is no STATUS_VISIBLE marker.
    # Looking backwards for that marker would silently reuse an older live window.
    return native('status')

def ready():
    error, payload = client.call('session_list', {'timeout_ms': 3000})
    return next((s for s in payload.get('result', {}).get('sessions', [])
                 if s['backend']=='GUI' and s['state']=='READY'), None)

def call(name, **kwargs):
    err, payload = client.call(name, dict(session_id=session['session_id'], timeout_ms=15000, **kwargs))
    require(not err, name+' success')
    return payload['result']

def main(acceptance=False):
    global ROOT, port, master, gui, proxy, session
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    ROOT = helpers.workspace('gui-acceptance' if acceptance else 'live-status')
    port = free_port()
    evidence['artifacts'] = {
        name: hashlib.sha256((helpers.RELEASE/name).read_bytes()).hexdigest()
        for name in ('jadx-mcp.jar', 'jadx-mcp-gui-plugin.jar')
    }
    evidence['driver_sha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    evidence['acceptance'] = acceptance
    run(acceptance)


def run(acceptance):
    global master, gui, proxy, session
    try:
        start_master()
        proxy = ThreadingHTTPServer(('127.0.0.1', 0), Proxy)
        threading.Thread(target=proxy.serve_forever, daemon=True).start()
        env = dict(os.environ, JADX_CONFIG_DIR=str(ROOT/'config'), JADX_CACHE_DIR=str(ROOT/'gui-cache'))
        source = BASE/'app/src/test/resources/sample.jar'
        owner, method, signature = 'com.yyang.sample.HelloSample', 'greet', '(Ljava/lang/String;)Ljava/lang/String;'
        apk = os.environ.get('JADX_SMOKE_INPUT') if acceptance else None
        if apk:
            source = Path(apk).resolve()
            fixture = json.loads(Path(os.environ['JADX_GUI_FIXTURE']).read_text())
            owner, method, signature = (fixture[key] for key in ('class_name', 'method_name', 'method_signature'))
            if not all(isinstance(value, str) and value for value in (owner, method, signature)):
                raise ValueError('Fixture requires nonempty class_name, method_name and JVM method_signature')
        input_file = ROOT/('input.apk' if apk else 'sample.jar')
        shutil.copy2(source, input_file)
        input_sha = hashlib.sha256(input_file.read_bytes()).hexdigest()
        evidence['input_sha256'] = input_sha
        evidence['fixture'] = 'authorized copied APK' if apk else 'checked-in sample.jar copy'
        log = (ROOT/'gui.log').open('w'); logs.append(log)
        gui = subprocess.Popen([JAVA, f'-Djadx.mcp.url=http://127.0.0.1:{proxy.server_port}',
            '-cp', DIST+os.pathsep+str(BASE/'gui-plugin/build/classes/guiSmoke'), 'GuiBridgeIntegrationSmoke',
            str(ROOT/'gui'), str(helpers.RELEASE/'jadx-mcp-gui-plugin.jar'), str(input_file)],
            cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.PIPE, text=True)
        session = wait_for(ready, 100)
        wait_for(lambda: 'GUI_READY' in (ROOT/'gui.log').read_text(), 30)
        native('open'); native('open'); native('open')
        wait_for(lambda: 'modal=false' in status(), 20)
        require('WINDOW_COUNT=1' in status(), 'repeated menu clicks focus exactly one non-modal window')
        require('BUTTON Apply' in status() and 'BUTTON Cancel' not in status(), 'only Apply exists; Cancel removed')
        native('invalid-ip')
        require('Enter a valid IPv4 or IPv6 address' in status(), 'URL input rejected in numeric IP field')
        native('invalid-port')
        require('Port must be a number from 1 to 65535' in status(), 'invalid port rejected inline')
        native('apply')
        wait_for(lambda: 'LABEL Applied!' in status(), 20)
        require(True, 'successful background IP/Port Apply shows exact Applied! text')
        session = wait_for(ready, 30)
        require('WINDOW_COUNT=1' in status(), 'Apply leaves live window open')
        require('5s: 0   10s: 0   30s: 0' in status(), 'registration/poll/heartbeat excluded at idle')
        call('meta_summary')
        wait_for(lambda: '5s: 1   10s: 1   30s: 1' in status(), 5)
        require(True, 'real GUI timer refreshes received business counter')
        time.sleep(10.5)
        require('5s: 0   10s: 0   30s: 1' in status(), 'real 5/10 second expiry with no traffic')
        time.sleep(20.5)
        wait_for(lambda: '5s: 0   10s: 0   30s: 0' in status(), 5)
        require(True, 'real Swing timer returns all three idle windows to zero')
        native('reset'); time.sleep(.3)
        native('capture-cleanup'); native('close-status'); native('verify-timer')
        require('CLOSED_TIMER_STOPPED' in (ROOT/'gui.log').read_text(), 'close stops owned timer')
        native('open')
        require('modal=false' in status(), 'status menu restored after real host menu reset')
        if acceptance:
            for debug_tool in ('debug_get_threads', 'debug_get_stack_frames', 'debug_get_variables'):
                error, payload = client.call(debug_tool, {'session_id':session['session_id'], 'timeout_ms':5000})
                debug_code = payload.get('error',{}).get('code')
                evidence.setdefault('debug_negative', {})[debug_tool] = debug_code
                require(error and debug_code in ('DISCONNECTED', 'BUSY', 'UNSUPPORTED'), debug_tool+' disconnected negative only')
        if apk:
            gui_summary = call('meta_summary')
            gui_manifest = call('meta_manifest', component_type='ACTIVITY')
            gui_main = call('decompile', target='MAIN_ACTIVITY')
            require(gui_main['class_name'] == gui_summary['main_activity'], 'GUI MAIN_ACTIVITY agrees with summary launcher')
            require(gui_main['class_name'] in [item['class_name'] for item in gui_manifest['components']], 'GUI launcher resolved from manifest component')
        call('meta_class', class_name=owner)
        call('search', scope='CLASS', term=owner.rsplit('.', 1)[-1], budget={'max_scan':20,'max_decompile':0})
        call('decompile', target='JAVA', class_name=owner)
        queried = call('get_method_by_name', class_name=owner, method_name=method, method_signature=signature)
        require(method+'(' in queried['text']['content'], 'real GUI named method resolves correct declaration')
        # Native project edit, then lose its completion acknowledgement. Never replay this edit.
        drop.set()
        call('add_comment', class_name=owner, comment='bridge-restart-once')
        require(dropped.wait(10), 'completion delivered but acknowledgement deliberately lost')
        old = session['session_id']
        master.kill(); master.wait(timeout=10)
        time.sleep(.8)
        require('RECONNECTING' in status(), 'visible reconnect state during real Master outage')
        start_master()
        session = wait_for(ready, 60)
        require(session['session_id'] != old, 'GUI automatically recovers after actual Master kill/restart')
        time.sleep(1.2)
        require('5s: 0   10s: 0   30s: 0' in status(), 'fresh generation empty: no automatic command replay')
        comments = call('list_comments')
        require(sum(c.get('comment')=='bridge-restart-once' for c in comments['comments'])==1,
                'uncertain completion edit remains exactly once after reconnection')
        require(edit_completions == 1, 'proxy observed exactly one edit completion across lost acknowledgement and restart')
        call('add_comment', class_name=owner, comment='')
        require(not call('list_comments')['comments'], 'isolated edit restored after reconnect without modifying original input')
        call('meta_summary')
        # Real HEADLESS core chain through same published Master and MCP.
        err, created = client.call('session_create', {'type':'HEADLESS','apk_path':str(input_file),'timeout_ms':60000})
        require(not err, 'HEADLESS session_create through MCP')
        headless = created['result']['session_id']
        for name, kwargs in [('meta_summary',{}),('search',{'scope':'CLASS','term':owner.rsplit('.', 1)[-1]}),
                              ('decompile',{'target':'JAVA','class_name':owner}),
                              ('decompile',{'target':'METHOD','class_name':owner,'method_name':method,'method_signature':signature})]:
            err, payload = client.call(name,dict(session_id=headless,timeout_ms=30000,**kwargs))
            require(not err, 'HEADLESS '+name+' core chain')
        if apk:
            results = {}
            for operation, parameters in [('meta_summary',{}), ('meta_manifest',{'component_type':'ACTIVITY'}), ('decompile',{'target':'MAIN_ACTIVITY'})]:
                error, envelope = client.call(operation, dict(session_id=headless, timeout_ms=30000, **parameters))
                require(not error, 'HEADLESS launcher '+operation+' success')
                results[operation] = envelope['result']
            require(results['decompile']['class_name'] == results['meta_summary']['main_activity'], 'HEADLESS MAIN_ACTIVITY agrees with summary launcher')
            require(results['decompile']['class_name'] == gui_main['class_name'], 'same APK GUI and HEADLESS launcher agree')
        error, closed = client.call('session_close', {'session_id':headless,'clear_cache':True})
        require(not error and closed['result']['closed'], 'owned HEADLESS session closed and generated cache cleared')
        call('session_close', clear_cache=False)
        time.sleep(1.2)
        require('Actively closed by master' in status(), 'explicit session_close remains disabled')
        time.sleep(2)
        require(ready() is None, 'explicitly closed GUI session never auto-resurrects')
        native('capture-cleanup')
        native('unload'); time.sleep(.3)
        native('verify-cleanup')
        require('CLEANUP_PASS' in (ROOT/'gui.log').read_text(), 'unload stops captured Swing timer and removes single menu observer')
        require('WINDOW_COUNT=0' in status(), 'plugin unload disposes owned live status window')
        native('quit'); gui.wait(timeout=15)
        require(hashlib.sha256(input_file.read_bytes()).hexdigest() == input_sha, 'isolated analysis/edit input remains byte-identical')
        evidence['status']='PASS'
    except BaseException as error:
        evidence['status']='FAIL'; evidence['error']=repr(error)
        raise
    finally:
        signal.alarm(0)  # The scenario deadline must not interrupt bounded owned-child cleanup.
        evidence['gui_exit']=stop(gui); evidence['master_exit']=stop(master)
        if proxy: proxy.shutdown(); proxy.server_close()
        evidence['all_owned_processes_reaped']=(gui is None or gui.poll() is not None) and (master is None or master.poll() is not None)
        evidence['mcp_calls']=client.rows if 'client' in globals() else []
        for log in logs: log.close()
        (ROOT/'evidence.json').write_text(json.dumps(evidence,indent=2))
        print(evidence['status'], len(evidence['assertions']), 'assertions', str(ROOT), flush=True)


if __name__ == '__main__':
    main()
