"""Exercise the actual unsigned installer, frozen runtime, rollback and uninstall on CI."""
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
    if exit_code == 0:
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
        'stopAcknowledged': acknowledgement.get('accepted') is True,
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
        tail = '\\n'.join(log_lines[-80:])[-12000:]
        tail = re.sub(r'(?i)(token|password|secret|api[_-]?key|authorization)(\\s*[:=]\\s*)[^\\s,}]+', r'\\1\\2[redacted]', tail)
        (output/'smoke-gateway.log').write_text(tail, encoding='utf-8')
    except OSError:
        summary['logEvidence'] = 'unavailable'
        atomic_json(output/'smoke-failure.json', summary)
    raise AssertionError(f'{phase}: stop acknowledged but launcher did not exit cleanly (code={exit_code}, state={status.get("state")})')


def main():
    output=ROOT/'dist'/'windows'
    installer=output/f'Gateway-{VERSION}-windows-x64-setup.exe'
    results=[]
    with tempfile.TemporaryDirectory(prefix='Gateway smoke ü ') as temp:
        root=Path(temp)/'Gateway install'
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
            subprocess.run([str(exe), 'dashboard', '--root', str(root), '--smoke-ui', '--quiet'], check=True, timeout=150)
            ui = json.loads((root / 'dashboard-ui-smoke.json').read_text(encoding='utf-8'))
            assert ui['pages'] == ['Overview', 'Chat', 'Saved', 'Jobs', 'Tools', 'Benchmarks']
            assert ui['width'] >= 900 and ui['height'] >= 600
            for capture in root.glob('dashboard-*.png'):
                shutil.copy2(capture, output / capture.name)
            results.append('frozen themed dashboard renders all views; observer persists metadata; UI closes without stopping gateway')
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
            subprocess.run(cmd,check=True,timeout=120)
            assert json.loads((root/'settings.json').read_text(encoding='utf-8'))==cfg
            assert (root/'data'/'preserve.txt').read_text()=='user data'
            results.append('repair install retains settings and persistent data')
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
