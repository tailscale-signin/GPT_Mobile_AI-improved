"""Explicit Windows firewall actions. Never run at startup or during auto-update."""
import base64
import ctypes
import hashlib
import os
from pathlib import Path
import subprocess

from desktop.configuration import atomic_json, load


def rule_name(root):
    return 'GPTMobileGateway-' + hashlib.sha256(str(Path(root).resolve()).casefold().encode()).hexdigest()[:24]


def apply(root, remove=False):
    if os.name != 'nt':
        raise RuntimeError('Windows Firewall is available on Windows only')
    root = Path(root)
    cfg = None
    if not remove:
        cfg = load(root)
        if cfg['access'] != 'network':
            raise ValueError('Choose a network listen address and save settings first')
    powershell = os.path.join(os.environ['SystemRoot'], 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe')
    args = ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
            os.path.join(os.path.dirname(__file__), 'Firewall.ps1'), '-RuleName', rule_name(root)]
    args += ['-Remove'] if remove else ['-Port', str(cfg['port']), '-BindAddress', cfg['bind_host']]
    if ctypes.windll.shell32.IsUserAnAdmin():
        result = subprocess.run([powershell, *args], check=False)
    else:
        # Data arguments are quoted once for Windows, then as PowerShell literals.
        quote = lambda value: "'" + value.replace("'", "''") + "'"
        command = "$ErrorActionPreference='Stop'; try { $p = Start-Process -FilePath " + quote(powershell) + \
            ' -ArgumentList ' + quote(subprocess.list2cmdline(args)) + \
            ' -Verb RunAs -Wait -PassThru; exit $p.ExitCode } catch { exit 1 }'
        encoded = base64.b64encode(command.encode('utf-16le')).decode('ascii')
        result = subprocess.run([powershell, '-NoProfile', '-NonInteractive', '-EncodedCommand', encoded], check=False)
    if result.returncode:
        raise RuntimeError('Firewall change was cancelled or failed. The listener settings are saved; no successful firewall change was confirmed.')
    marker = root / 'firewall.json'
    if remove:
        marker.unlink(missing_ok=True)
    else:
        atomic_json(marker, {'name': rule_name(root), 'port': cfg['port'], 'bind_host': cfg['bind_host'], 'profile': 'Private', 'remote': 'LocalSubnet'})
