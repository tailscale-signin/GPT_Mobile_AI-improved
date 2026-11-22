"""Read-only host observations; no addresses, commands or process identities leave the probe."""
import json
import os
from pathlib import Path
import subprocess
import time

from desktop.configuration import validate
from desktop.firewall import rule_name


MAX_OUTPUT = 16_384
PROBE_TIMEOUT = 12
ENUMS = {
    'listener_state': {'PRESENT', 'NOT_OBSERVED', 'UNKNOWN'},
    'listener_binding': {'MATCHED', 'DIFFERENT_BIND', 'NOT_OBSERVED', 'UNKNOWN'},
    'default_route_state': {'PRESENT', 'NOT_OBSERVED', 'UNKNOWN'},
    'tunnel_adapter_signal': {'PRESENT', 'NOT_OBSERVED', 'UNKNOWN'},
    'firewall_rule_state': {'PRESENT', 'NOT_OBSERVED', 'UNKNOWN'},
    'firewall_rule_action': {'ALLOW', 'BLOCK', 'OTHER', 'UNKNOWN'},
    'firewall_rule_enabled': {'ENABLED', 'DISABLED', 'MIXED', 'UNKNOWN'},
    'firewall_rule_port': {'MATCHED', 'DIFFERENT_PORT', 'UNKNOWN'},
}
COUNTS = {'listener_count', 'ipv4_default_route_count', 'ipv6_default_route_count', 'tunnel_adapter_count'}
NETWORK_PROFILES = {'Private', 'Public', 'DomainAuthenticated'}
FIREWALL_PROFILES = {'Any', 'Domain', 'Private', 'Public', 'NotApplicable'}


def _read_object(path, limit=65_536):
    with path.open('rb') as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError('Oversize host observation')
    value = json.loads(data)
    if not isinstance(value, dict):
        raise ValueError('Invalid host observation')
    return value


def _startup_registration(root):
    """Inspect the same per-user startup value written by the supervisor, without opening it for writes."""
    try:
        import winreg
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r'Software\Microsoft\Windows\CurrentVersion\Run', 0, winreg.KEY_READ) as key:
            observed, kind = winreg.QueryValueEx(key, 'GPTMobileGateway')
        if kind != winreg.REG_SZ or not isinstance(observed, str):
            return 'UNKNOWN'
        powershell = str(Path(os.environ.get('SystemRoot', r'C:\Windows')) / 'System32/WindowsPowerShell/v1.0/powershell.exe')
        expected = subprocess.list2cmdline([powershell, '-NoProfile', '-WindowStyle', 'Hidden', '-ExecutionPolicy', 'Bypass', '-File', str(Path(root) / 'Start-Gateway.ps1')])
        return 'REGISTERED_FOR_INSTALLATION' if observed.casefold() == expected.casefold() else 'DIFFERENT_REGISTRATION'
    except FileNotFoundError:
        return 'NOT_REGISTERED'
    except (ImportError, OSError, ValueError, TypeError):
        return 'UNKNOWN'


def normalize_probe(raw):
    """Cmdlet failures stay unknown. Arbitrary PowerShell values never enter a local report."""
    raw = raw if isinstance(raw, dict) else {}
    result = {key: raw.get(key) if raw.get(key) in choices else 'UNKNOWN' for key, choices in ENUMS.items() if isinstance(raw.get(key), (str, type(None)))}
    result.update({key: 'UNKNOWN' for key in ENUMS if key not in result})
    for key in COUNTS:
        value = raw.get(key)
        if type(value) is int and 0 <= value <= 65_535:
            result[key] = value
    for key, choices in (('active_network_profiles', NETWORK_PROFILES), ('firewall_rule_profiles', FIREWALL_PROFILES)):
        values = raw.get(key)
        if (isinstance(values, list) and len(values) <= 3 and (values or key == 'active_network_profiles')
                and all(isinstance(value, str) and value in choices for value in values)):
            result[key] = sorted(set(values))
    return result


def collect(root, *, now=None):
    """Inspect saved settings only. A port listener is not proof of Gateway ownership or phone reachability."""
    root = Path(root)
    now = time.time() if now is None else now
    result = {'schema': 'gptmobile.host-connection-observation.v1', 'observed_at': int(now),
              'collection_state': 'UNAVAILABLE', 'startup_registration': 'UNKNOWN',
              'service_start_state': 'UNKNOWN', 'listener_ownership': 'NOT_VERIFIED',
              'firewall_connectivity_verdict': 'NOT_ESTABLISHED', **normalize_probe({})}
    try:
        cfg = validate(_read_object(root / 'settings.json'))
    except (OSError, ValueError, TypeError, KeyError):
        result['collection_state'] = 'INVALID_SETTINGS'
        result['summary'] = summary(result)
        return result
    result['startup_requested'] = cfg['start_at_login']
    try:
        saved = _read_object(root / 'status.json')
        states = {'starting': 'STARTING_REPORTED', 'running': 'RUNNING_REPORTED', 'recovering': 'RECOVERY_REPORTED',
                  'restarting': 'RESTARTING_REPORTED', 'stopped': 'STOPPED_REPORTED', 'error': 'ERROR_REPORTED'}
        result['service_start_state'] = 'STARTUP_FAILURE_RECORDED' if saved.get('updateState') == 'startup-failed' else states.get(saved.get('state'), 'UNKNOWN')
        stamp = saved.get('updatedAt')
        if type(stamp) in (float, int) and 0 <= now - stamp <= 31_536_000:
            result['supervisor_observation_age_seconds'] = int(now - stamp)
    except (OSError, ValueError, TypeError):
        pass
    if os.name != 'nt':
        result['collection_state'] = 'UNSUPPORTED'
    else:
        result['startup_registration'] = _startup_registration(root)
        powershell = Path(os.environ.get('SystemRoot', r'C:\Windows')) / 'System32/WindowsPowerShell/v1.0/powershell.exe'
        command = [str(powershell), '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File',
                   str(Path(__file__).with_name('Inspect-Connection.ps1')), '-Port', str(cfg['port']),
                   '-BindAddress', cfg['bind_host'], '-RuleName', rule_name(root)]
        try:
            observed = subprocess.run(command, capture_output=True, timeout=PROBE_TIMEOUT, check=False,
                                      creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            if observed.returncode == 0 and len(observed.stdout) <= MAX_OUTPUT:
                raw = json.loads(observed.stdout.decode('utf-8-sig'))
                if not isinstance(raw, dict):
                    raise ValueError('Invalid host observation')
                result.update(normalize_probe(raw))
                # A successful lookup can establish that the rule is absent. Its
                # action, scope and port only apply to rules that were found.
                required = {'listener_state', 'listener_binding', 'default_route_state', 'tunnel_adapter_signal', 'firewall_rule_state'}
                if result['firewall_rule_state'] == 'PRESENT':
                    required.update(('firewall_rule_action', 'firewall_rule_enabled', 'firewall_rule_port', 'firewall_rule_profiles'))
                incomplete = any(not result.get(key) or result[key] == 'UNKNOWN' for key in required)
                result['collection_state'] = 'PARTIAL' if incomplete else 'OBSERVED'
        except subprocess.TimeoutExpired:
            result['collection_state'] = 'TIMEOUT'
        except (OSError, ValueError, UnicodeError):
            pass
    result['summary'] = summary(result)
    return result


def summary(observed):
    """Human-readable observations deliberately distinguish absent evidence from failed connectivity."""
    lines = [f"Host inspection: {observed.get('collection_state', 'UNAVAILABLE').lower().replace('_', ' ')}."]
    listener = observed.get('listener_state')
    if listener == 'PRESENT':
        matched = 'matches the saved listen address' if observed.get('listener_binding') == 'MATCHED' else 'does not confirm the saved listen address'
        lines.append(f'A process is listening on the saved port; its binding {matched}. Gateway ownership is not verified by this check.')
    elif listener == 'NOT_OBSERVED':
        lines.append('No listening process was observed on the saved port.')
    else:
        lines.append('The port listener could not be observed.')
    route = {'PRESENT': 'Default routes were observed.', 'NOT_OBSERVED': 'No default route was observed.'}
    lines.append(route.get(observed.get('default_route_state'), 'Default routes could not be observed.'))
    tunnel = {'PRESENT': 'An active adapter has a VPN or tunnel signal; account connectivity is not established.',
              'NOT_OBSERVED': 'No active adapter with a VPN or tunnel signal was observed.'}
    lines.append(tunnel.get(observed.get('tunnel_adapter_signal'), 'VPN or tunnel adapters could not be observed.'))
    profiles = observed.get('active_network_profiles')
    if profiles:
        lines.append('Active Windows network profiles: ' + ', '.join(profiles) + '.')
    firewall = observed.get('firewall_rule_state')
    if firewall == 'PRESENT':
        lines.append('The Gateway firewall rule was observed: ' + ', '.join(str(observed.get(key, 'UNKNOWN')).lower().replace('_', ' ') for key in ('firewall_rule_enabled', 'firewall_rule_action', 'firewall_rule_port')) + '.')
        rule_profiles = observed.get('firewall_rule_profiles')
        profile_labels = {'Any': 'Any (all profiles)', 'NotApplicable': 'not applicable'}
        scope = ', '.join(profile_labels.get(profile, profile) for profile in rule_profiles) if rule_profiles else 'unknown'
        lines.append('Gateway firewall rule profiles: ' + scope + '.')
    elif firewall == 'NOT_OBSERVED':
        lines.append('The Gateway-specific firewall rule was not observed; this does not prove traffic is blocked.')
    else:
        lines.append('The Gateway firewall rule could not be observed.')
    lines.append('Startup registration: ' + observed.get('startup_registration', 'UNKNOWN').lower().replace('_', ' ') + '.')
    age = observed.get('supervisor_observation_age_seconds')
    lines.append('Last saved supervisor state: ' + observed.get('service_start_state', 'UNKNOWN').lower().replace('_', ' ') + (f' ({age}s ago).' if age is not None else ' (observation time unavailable).'))
    lines.append('These local observations do not establish reachability from your phone. Use the saved PC address on the phone to test the full path.')
    return '\n'.join(lines)
