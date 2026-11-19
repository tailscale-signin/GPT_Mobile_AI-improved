"""Explicit Windows firewall actions. Never run at startup or during auto-update."""
import base64
import ctypes
import hashlib
import os
from pathlib import Path
import subprocess

from desktop.configuration import atomic_json, load


def rule_name(root):
    return 'GPTMobileGateway-' + hashlib.sha256(os.path.abspath(os.fspath(root)).casefold().encode()).hexdigest()[:24]


def apply(root, remove=False, *, phone=False):
    if os.name != 'nt':
        raise RuntimeError('Windows Firewall is available on Windows only')
    root = Path(root)
    cfg = None
    if not remove:
        if phone:
            import ipaddress
            import json
            from desktop.phone_access import directory
            listener = json.loads((directory(root) / 'listener.json').read_text())
            addr = ipaddress.ip_address(listener['host'])
            if not any(addr in ipaddress.ip_network(net) for net in ('10.0.0.0/8', '192.168.0.0/16', '172.16.0.0/12')):
                raise ValueError('Choose a private IPv4 interface first')
            cfg = dict(port=int(listener['port']), bind_host=str(addr), access='network')
        else:
            cfg = load(root)
        if cfg['access'] != 'network':
            raise ValueError('Choose a network listen address and save settings first')
    name = rule_name(root) + ('-phone' if phone else '')
    powershell = os.path.join(os.environ['SystemRoot'], 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe')
    args = ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
            os.path.join(os.path.dirname(__file__), 'Firewall.ps1'), '-RuleName', name]
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
    marker = os.path.join(os.fspath(root), 'phone-firewall.json' if phone else 'firewall.json')
    if remove:
        try:
            os.unlink(marker)
        except FileNotFoundError:
            pass
    else:
        atomic_json(marker, {'name': name, 'port': cfg['port'], 'bind_host': cfg['bind_host'], 'profile': 'Private', 'remote': 'LocalSubnet'})
