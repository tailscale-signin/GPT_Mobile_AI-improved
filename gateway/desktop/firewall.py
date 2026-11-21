"""Explicit Windows firewall actions. Never run at startup or during auto-update."""
import base64
import ctypes
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import subprocess

from desktop.configuration import atomic_json, load, settings_write_lock


def rule_name(root):
    return 'GPTMobileGateway-' + hashlib.sha256(os.path.abspath(os.fspath(root)).casefold().encode()).hexdigest()[:24]


def control_listener(root):
    path = Path(root) / 'data' / 'control-listener.json'
    if path.stat().st_size > 4096:
        raise ValueError('The saved control listener is invalid.')
    listener = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(listener, dict) or set(listener) != {'host', 'port'}:
        raise ValueError('Enable the owner control listener first.')
    address = ipaddress.ip_address(listener['host'])
    private_networks = ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', '100.64.0.0/10', 'fc00::/7')
    if not any(address in ipaddress.ip_network(network) for network in private_networks):
        raise ValueError('Choose a private LAN or VPN control interface first.')
    port = listener['port']
    if type(port) is not int or not 1024 <= port <= 65535:
        raise ValueError('The saved control port is invalid.')
    return dict(port=port, bind_host=str(address), access='network')


def prepare_control_listener(root, cfg):
    """Prepare a missing listener only during an explicit network setup action."""
    from desktop.configuration import default_phone_listener, setup_interface
    path = root / 'data' / 'control-listener.json'
    if not path.exists():
        if cfg['bind_host'] == '::':
            raise ValueError('Choose a private IPv6 control interface in Android controls before allowing this IPv6 listener.')
        selected = cfg
        if cfg['bind_host'] == '0.0.0.0':
            selected = dict(cfg, bind_host=setup_interface(prefer_overlay=False))
        listener = default_phone_listener(selected)
        if listener is None:
            return None
        atomic_json(path, listener)
    listener = control_listener(root)
    if listener['port'] == cfg['port']:
        raise ValueError('The phone control port must differ from the Gateway port.')
    return listener


def apply(root, remove=False, *, phone=False, include_control=False):
    if os.name != 'nt':
        raise RuntimeError('Windows Firewall is available on Windows only')
    root = Path(root)
    cfg = listener = None
    if not remove:
        with settings_write_lock(root):
            cfg = control_listener(root) if phone else load(root)
            if cfg['access'] != 'network':
                raise ValueError('Choose a network listen address and save settings first')
            if include_control and not phone:
                listener = prepare_control_listener(root, cfg)
    name = rule_name(root) + ('-phone' if phone else '')
    powershell = os.path.join(os.environ['SystemRoot'], 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe')
    args = ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
            os.path.join(os.path.dirname(__file__), 'Firewall.ps1'), '-RuleName', name]
    args += ['-Remove'] if remove else ['-Port', str(cfg['port']), '-BindAddress', cfg['bind_host']]
    if listener:
        # A wildcard inference listener must not widen the private TLS rule.
        args += ['-ControlPort', str(listener['port']), '-ControlAddress', listener['bind_host']]
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
    marker = root / ('phone-firewall.json' if phone else 'firewall.json')
    if remove:
        marker.unlink(missing_ok=True)
        if not phone:
            (root / 'phone-firewall.json').unlink(missing_ok=True)
    else:
        atomic_json(marker, {'name': name, 'port': cfg['port'], 'bind_host': cfg['bind_host'], 'profile': 'Private', 'remote': 'LocalSubnet'})
        if listener:
            atomic_json(root / 'phone-firewall.json', {'name': name + '-phone', 'port': listener['port'],
                        'bind_host': listener['bind_host'], 'profile': 'Private', 'remote': 'LocalSubnet'})
