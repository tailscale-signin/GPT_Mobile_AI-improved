"""Route the packaged Windows UI to WPF without transferring runtime ownership."""
import os
import subprocess
import sys
import time
from pathlib import Path

from desktop.configuration import atomic_json
from v14.state_lock import StateDirectoryLock, StateDirectoryAlreadyOwned


def native_executable():
    return Path(sys.executable).parent / 'native' / 'Gateway.Desktop.exe'


def start_supervisor(root):
    powershell = Path(os.environ['SystemRoot']) / 'System32' / 'WindowsPowerShell' / 'v1.0' / 'powershell.exe'
    subprocess.Popen([str(powershell), '-NoProfile', '-WindowStyle', 'Hidden', '-ExecutionPolicy', 'Bypass',
                      '-File', str(Path(root) / 'Start-Gateway.ps1')],
                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))


def show(root, view='Overview', smoke=False):
    executable = native_executable()
    if os.name != 'nt' or not getattr(sys, 'frozen', False) or not executable.is_file():
        return None
    lock = StateDirectoryLock(Path(root) / 'native-ui' / 'owner')
    try:
        lock.acquire()
    except StateDirectoryAlreadyOwned:
        atomic_json(Path(root) / 'show-native.json', {'requestedAt': time.time(), 'view': view})
        return 0
    try:
        args = [str(executable), '--root', str(root), '--runtime', str(Path(sys.executable)), '--view', view]
        if smoke:
            args.append('--smoke-ui')
        return subprocess.run(args, check=False).returncode
    finally:
        lock.release()
