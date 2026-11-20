"""Exercise the actual unsigned installer, frozen runtime, rollback and uninstall on CI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import requests

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'gateway'))
from desktop.configuration import atomic_json, validate
from desktop.manager import request
from desktop.native_launch import NATIVE_VIEWS
from v14 import VERSION


def wait_state(root, expected, version=VERSION, timeout=100):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        try:
            state=json.loads((root/'status.json').read_text(encoding='utf-8'))
            if state.get('state')==expected and state.get('version')==version:
                return state
        except (OSError,ValueError):
            pass
        time.sleep(.5)
    raise RuntimeError((root/'logs'/'gateway.log').read_text(encoding='utf-8',errors='replace')[-12000:])



def stop_checked(root, child, phase, output):
    """Require both a confirmed stop receipt and a clean launcher exit."""
    acknowledgement = request(root, 'stop')
    try:
        exit_code = child.wait(timeout=40)
    except subprocess.TimeoutExpired:
        exit_code = None
    acknowledged = bool(acknowledgement.get('id')) and acknowledgement.get('accepted') is True
    if exit_code == 0 and acknowledged:
        return
    # The temporary installed root is deleted at the end of this smoke run.
    # Retain narrowly scoped synthetic CI evidence so a failed stop is actionable.
    status = {}
    try:
        status = json.loads((root/'status.json').read_text(encoding='utf-8'))
    except (OSError, ValueError):
        pass
    summary = {
        'phase': phase,
        'launcherExitCode': exit_code,
        'stopAcknowledged': acknowledged,
        'supervisorState': status.get('state'),
        'supervisorVersion': status.get('version'),
        'supervisorUpdateState': status.get('updateState'),
        'supervisorRecoveryAttempt': status.get('recoveryAttempt'),
        'logEvidence': 'smoke-gateway.log',
    }
    output.mkdir(parents=True, exist_ok=True)
    atomic_json(output/'smoke-failure.json', summary)
    try:
        log_lines = (root/'logs'/'gateway.log').read_text(encoding='utf-8', errors='replace').splitlines()
        # This smoke creates an offline local fixture. Keep only a bounded tail
        # and redact common credential assignments before uploading CI artifacts.
        import re
        tail = '\n'.join(log_lines[-80:])[-12000:]
        tail = re.sub(r'(?i)(token|password|secret|api[_-]?key|authorization)(\s*[:=]\s*)[^\s,}]+', r'\1\2[redacted]', tail)
        (output/'smoke-gateway.log').write_text(tail, encoding='utf-8')
    except OSError:
        summary['logEvidence'] = 'unavailable'
        atomic_json(output/'smoke-failure.json', summary)
    raise AssertionError(f'{phase}: stop verification failed (acknowledged={acknowledged}, code={exit_code}, state={status.get("state")})')


def seed_density_receipts(root):
    """Populate the installed temporary Gateway with completed, owner-scoped catalog receipts."""
    import sqlite3
    from contextlib import closing
    from desktop.conversation_client import Client
    from gateway_security import DeviceStore
    client = Client(root)
    principal = client.identity()['principal_id']
    client.request('GET', '/gateway/job-catalog', params={'limit': 1})
    owner = DeviceStore(root / 'data' / 'auth.sqlite3')
    now = time.time()
    with closing(sqlite3.connect(root / 'data' / 'jobs.sqlite3', timeout=20)) as db, db:
        for index in range(105):
            job = f'layout-smoke-{index:03d}'
            stamp = now - (index + 1) * 60
            public = dict(job_id=job, title=f'Layout qualification {index:03d}', model='offline-density-fixture',
                          status='completed', stage='finished', created_at=stamp, finished_at=stamp+5,
                          accepted_at=stamp, last_sequence=1, result_available=True)
            db.execute('INSERT INTO gateway_jobs(job_id,public_json,result_json,status,created_at,updated_at,finished_at) '
                       'VALUES(?,?,?,?,?,?,?)', (job, json.dumps(public), '{}', 'completed', stamp, stamp+5, stamp+5))
            db.execute("INSERT INTO gateway_catalog_keys(job_kind,job_id) VALUES('generation',?)", (job,))
            owner.claim(job, principal)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--upgrade-archive', type=Path)
    upgrade = parser.parse_args().upgrade_archive
    output=ROOT/'dist'/'windows'
    installer=output/f'Gateway-{VERSION}-windows-x64-setup.exe'
    results=[]
    with tempfile.TemporaryDirectory(prefix='Gateway smoke ü ') as temp:
        root=Path(temp)/'Gateway install'
        # Drive the shipped installer itself before testing unattended install and repair.
        from desktop.windows.setup_smoke import check_installed_launch, check_wizard
        check_wizard(installer, root, output)
        results.append('interactive setup opens, validates custom settings and returns to the recommended path')
        cmd=[str(installer),'/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART','/TASKS=',f'/DIR={root}']
        subprocess.run(cmd,check=True,timeout=120)
        results.append('fresh per-user install with spaces and Unicode')
        cfg=json.loads((root/'settings.json').read_text(encoding='utf-8'))
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',0));cfg['port']=sock.getsockname()[1]
        cfg.update(update_mode='off',start_at_login=False,backend='http://127.0.0.1:1')
        atomic_json(root/'settings.json',cfg)
        exe=root/'releases'/VERSION/'Gateway.exe'
        subprocess.run([str(exe),'validate-engine','--root',str(root)],check=True,timeout=40)
        (root/'data'/'preserve.txt').write_text('user data',encoding='utf-8')
        powershell=str(Path(os.environ['SystemRoot'])/'System32/WindowsPowerShell/v1.0/powershell.exe')
        launcher=[powershell,'-NoProfile','-ExecutionPolicy','Bypass','-File',str(root/'Start-Gateway.ps1')]
        child=subprocess.Popen(launcher, env={key: value for key, value in os.environ.items()
                                             if key not in {'GATEWAY_ENGINEERING_GITHUB_SERVER', 'GATEWAY_ENGINEERING_GITHUB_TOKEN'}})
        try:
            state=wait_state(root,'running')
            assert state['backend'] != 'ready'
            results.append('frozen runtime healthy with offline model backend')
            capabilities = requests.get(f"http://127.0.0.1:{cfg['port']}/v1/gateway/engineering/capabilities", timeout=5)
            capabilities.raise_for_status()
            assert capabilities.json()['code_graph']['access'] == 'public_only'
            assert capabilities.json()['code_graph']['enabled']
            assert not capabilities.json()['enabled']
            results.append('public repository map is ready by default without a GitHub credential')
            subprocess.run([str(exe), 'validate-ui', '--root', str(root), '--quiet'], check=True, timeout=90)
            settings_captures = list(root.glob('settings-*.png'))
            assert {'settings-repository-validation-basic-compact.png',
                    'settings-repository-validation-advanced-compact.png'} <= {capture.name for capture in settings_captures}
            for capture in settings_captures:
                shutil.copy2(capture, output / capture.name)
            results.append('General, Advanced, Add-ons and GitHub render at minimum size; Repository Validation basic and Advanced captures retained')
            deadline = time.monotonic() + 20
            while not (root / 'dashboard.json').exists() and time.monotonic() < deadline:
                time.sleep(.2)
            observation = json.loads((root / 'dashboard.json').read_text(encoding='utf-8'))
            assert observation['service']['activeWorkers'] == 0
            assert observation['historyError'] is None
            assert (root / 'data' / 'activity.json').exists()
            subprocess.run([str(exe), 'dashboard', '--root', str(root), '--smoke-ui', '--quiet', '--legacy-ui'], check=True, timeout=150)
            ui = json.loads((root / 'dashboard-ui-smoke.json').read_text(encoding='utf-8'))
            assert ui['pages'] == ['Overview', 'Chat', 'Saved', 'Jobs', 'Tools', 'Benchmarks']
            assert ui['width'] >= 900 and ui['height'] >= 600
            for capture in root.glob('dashboard-*.png'):
                shutil.copy2(capture, output / capture.name)
            results.append('frozen themed dashboard renders all views; observer persists metadata; UI closes without stopping gateway')
            seed_density_receipts(root)
            subprocess.run([str(exe), 'dashboard', '--root', str(root), '--smoke-ui', '--quiet'], check=True, timeout=260)
            native = json.loads((root / 'native-ui-smoke.json').read_text(encoding='utf-8'))
            assert native['runtime'] == 'WPF' and native['passed'] is True
            assert native['pages'] == list(NATIVE_VIEWS), native['pages']
            for view in NATIVE_VIEWS:
                capture = root / f"native-{view.lower().replace(' ', '-')}.png"
                assert capture.is_file() and capture.stat().st_size > 0, capture.name
            assert native['width'] >= 900 and native['height'] >= 600
            assert native['highContrastPaletteExercised'] and native['reducedMotionExercised'] and native['focusAndScrollPreserved']
            assert native['simulatedLayoutScale'] == 1.5
            assert native['visibleCompactRows'] >= 30 and native['catalogReceipts'] >= 105
            assert native['selectionAndPinsPreserved'] and native['simulatedScales'] == [1.0,1.25,1.5,2.0]
            results.append(f"{native['visibleCompactRows']} fully visible compact job rows at the 1920x1080 reference size; "
                           f"{native['catalogReceipts']} catalog receipts with selection and pins retained across pagination")
            shutil.copy2(root / 'native-jobs-reference.png', output / 'native-jobs-reference.png')
            for capture in root.glob('native-*.png'):
                shutil.copy2(capture, output / capture.name)
            shutil.copy2(root / 'native-ui-smoke.json', output / 'native-ui-smoke.json')
            results.append(f'packaged WPF workspace renders {len(NATIVE_VIEWS)} live views at 900x600 and closes without stopping runtime')
            assert child.poll() is None
            from desktop.tray import TrayIcon
            tray = TrayIcon(lambda: 'Gateway smoke test', {})
            tray.start()
            assert tray.ready.wait(10), 'Tray thread did not initialize'
            assert tray.error is None, tray.error
            assert tray.available, 'Windows notification-area icon was not registered'
            tray.close()
            assert not tray.thread.is_alive(), 'Tray thread did not shut down'
            results.append('native Windows notification-area icon registers and shuts down cleanly')
            stop_checked(root, child, 'first loopback stop', output)
            cfg = validate(dict(cfg, bind_host='0.0.0.0'))
            atomic_json(root/'settings.json', cfg)
            assert not (root/'firewall.json').exists()
            for attempt in range(2):
                (root/'status.json').unlink(missing_ok=True)
                child = subprocess.Popen(launcher)
                wait_state(root, 'running')
                with requests.Session() as http:
                    http.trust_env = False
                    response = http.get(f'http://127.0.0.1:{cfg["port"]}/v1/gateway/capabilities', timeout=5)
                    response.raise_for_status()
                    assert response.json()['auth'] == {'mode': 'open_network', 'apiKeyRequired': False, 'legacyBearerEnabled': False}
                stop_checked(root, child, f'all-interface restart {attempt + 1} stop', output)
            results.append('frozen all-interface listener works without API key across restart')
            # Exercise an explicitly requested Private/LocalSubnet rule on CI.
            from desktop.firewall import apply, rule_name
            try:
                apply(root)
                command = "$r=Get-NetFirewallRule -Name '" + rule_name(root) + "'; if ($r.Profile -ne 'Private' -or $r.Direction -ne 'Inbound' -or $r.Action -ne 'Allow') { exit 1 }; $p=$r | Get-NetFirewallPortFilter; if ($p.LocalPort -ne '" + str(cfg['port']) + "') { exit 2 }; $a=$r | Get-NetFirewallAddressFilter; if ($a.RemoteAddress -notcontains 'LocalSubnet') { exit 3 }"
                subprocess.run([powershell, '-NoProfile', '-NonInteractive', '-Command', command], check=True, timeout=30)
            finally:
                apply(root, remove=True)
            assert not (root/'firewall.json').exists()
            results.append('explicit firewall rule is scoped to saved port, Private profile and LocalSubnet; removal succeeds')
            # A candidate with incompatible runtime version must roll back before admission.
            candidate='15.0.99'
            shutil.copytree(root/'releases'/VERSION,root/'releases'/candidate)
            manifest=json.loads((root/'releases'/candidate/'release.json').read_text(encoding='utf-8'))
            manifest['version']=candidate
            atomic_json(root/'releases'/candidate/'release.json',manifest)
            atomic_json(root/'current.json',dict(version=candidate,previous=VERSION,pending=True))
            (root/'status.json').unlink()
            child=subprocess.Popen(launcher)
            wait_state(root,'running',timeout=120)
            pointer=json.loads((root/'current.json').read_text(encoding='utf-8'))
            assert pointer['version']==VERSION and pointer['failedVersion']==candidate
            results.append('candidate startup failure rolls back through stable launcher')
            stop_checked(root, child, 'post-rollback stop', output)
            # Repair must work even when the old executable and version pointer cannot launch.
            (root / 'current.json').write_text('{damaged pointer', encoding='utf-8')
            exe.rename(exe.with_suffix('.missing'))
            exe.with_suffix('.missing').unlink()
            subprocess.run(cmd,check=True,timeout=120)
            assert exe.is_file() and json.loads((root/'current.json').read_text())['version'] == VERSION
            assert any((root/'data'/'setup-recovery').glob('*-current.json'))
            assert json.loads((root/'settings.json').read_text(encoding='utf-8'))==cfg
            assert (root/'data'/'preserve.txt').read_text()=='user data'
            results.append('repair install retains settings and persistent data')
            check_installed_launch(root, exe, wait_state)
            results.append('both installed executables open one dashboard without arguments and start the repaired supervisor')
            if upgrade:
                from unittest.mock import patch
                from desktop import releases, windows_job
                from desktop.manager import Supervisor
                target = '18.0.0'
                assert VERSION == '17.0.2' and upgrade.name == f'Gateway-{target}-windows-x64.zip'
                with upgrade.open('rb') as stream:
                    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                url = f'https://github.com/{releases.REPOSITORY}/releases/download/gateway-v{target}/{upgrade.name}'
                candidate = releases.choose([{'tag_name': f'gateway-v{target}', 'assets': [{'name': upgrade.name,
                    'browser_download_url': url, 'size': upgrade.stat().st_size, 'digest': 'sha256:' + digest}]}], VERSION)
                assert candidate and candidate['version'] == target
                # Replace only the network transport with the CI-built ZIP. Exercise
                # digest verification, frozen preflight, activation and real boot.
                manager = Supervisor(root, headless=True)
                try:
                    with requests.Response() as response, upgrade.open('rb') as stream:
                        response.status_code, response.url, response.raw = 200, url, stream
                        with patch.object(requests.Session, 'get', return_value=response):
                            manager.stage(candidate)
                    assert manager.activate(target)
                finally:
                    manager.http.close()
                    windows_job.close(manager.job)
                    for handler in manager.log.handlers[:]:
                        handler.close()
                        manager.log.removeHandler(handler)
                child = subprocess.Popen(launcher)
                observed = wait_state(root, 'running', version=target)
                assert observed.get('updateState') == 'installed'
                assert json.loads((root/'current.json').read_text())['previous'] == VERSION
                assert json.loads((root/'settings.json').read_text()) == cfg
                assert (root/'data'/'preserve.txt').read_text() == 'user data'
                stop_checked(root, child, 'updated v18 stop', output)
                results.append('17.0.2 verified and staged the v18 ZIP, booted the installed v18 runtime and retained settings and data')
            subprocess.run([str(root/'unins000.exe'),'/VERYSILENT','/SUPPRESSMSGBOXES','/NORESTART',f'/LOG={output / "uninstall.log"}'],check=True,timeout=120)
            # Inno relocates its uninstaller; the original bootstrap may exit first.
            deadline=time.monotonic()+60
            while (root/'current.json').exists() and time.monotonic()<deadline:
                time.sleep(.2)
            assert (root/'data'/'preserve.txt').read_text()=='user data'
            assert not (root/'current.json').exists(), (output/'uninstall.log').read_text(encoding='utf-8',errors='replace')[-10000:]
            results.append('uninstall retains user data and removes active version pointer')
        finally:
            if child.poll() is None:
                try:
                    request(root,'stop')
                    child.wait(timeout=40)
                except Exception:
                    child.terminate()
    atomic_json(output/'smoke-results.json',{'version':VERSION,'passed':results})
    print(json.dumps(results,indent=2))


if __name__=='__main__':
    main()
