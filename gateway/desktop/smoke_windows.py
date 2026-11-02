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

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'gateway'))
from desktop.configuration import atomic_json
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
        child=subprocess.Popen(launcher)
        try:
            state=wait_state(root,'running')
            assert state['backend'] != 'ready'
            results.append('frozen runtime healthy with offline model backend')
            subprocess.run([str(root/'releases'/VERSION/'Gateway.exe'), 'validate-ui', '--root', str(root), '--quiet'], check=True, timeout=20)
            results.append('frozen dashboard renders Overview, General, Advanced and Add-ons at minimum size')
            request(root,'stop');assert child.wait(timeout=40)==0
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
            request(root,'stop');assert child.wait(timeout=40)==0
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
