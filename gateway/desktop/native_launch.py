"""Route the packaged Windows UI to WPF without transferring runtime ownership."""
import os
import subprocess
import sys
import time
from pathlib import Path

from desktop.configuration import atomic_json
from v14.state_lock import StateDirectoryLock, StateDirectoryAlreadyOwned


NATIVE_VIEWS = ("Overview", "Chat", "Saved", "Jobs", "Tools", "Benchmarks", "Android controls",
                "Diagnostics", "Settings", "Connect phone", "Advanced settings")


def native_executable():
    return Path(sys.executable).parent / 'native' / 'Gateway.Desktop.exe'


def _launcher(root, command):
    powershell = Path(os.environ['SystemRoot']) / 'System32' / 'WindowsPowerShell' / 'v1.0' / 'powershell.exe'
    return subprocess.Popen([str(powershell), '-NoProfile', '-WindowStyle', 'Hidden', '-ExecutionPolicy', 'Bypass',
                      '-File', str(Path(root) / 'Start-Gateway.ps1'), '-Command', command],
                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))


def start_supervisor(root):
    return _launcher(root, 'supervise')


def launch_installed_dashboard(root):
    _launcher(root, 'dashboard')
    return 0


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
        # The stable supervisor already serializes startup with its state-directory lock.
        # Open the window immediately, including when the backend is offline.
        if not smoke:
            start_supervisor(root)
        args = [str(executable), '--root', str(root), '--runtime', str(Path(sys.executable)), '--view', view]
        if smoke:
            args.append('--smoke-ui')
        try:
            result = subprocess.run(args, check=False).returncode
        except OSError:
            if smoke:
                raise
            return None
        return result if result == 0 or smoke else None
    finally:
        lock.release()
