"""Opt-in Windows engineering prerequisites; no unattended privileged changes."""
from __future__ import annotations
import json
import os
from pathlib import Path
import re
import shutil
import sys

from desktop import repository_validation as validation
from v14.validation_host import executable

DOCKER_DOCS = 'https://docs.docker.com/desktop/setup/install/windows-install/'


def is_windows():
    return sys.platform == 'win32'


def _helpers():
    roots = [Path(os.environ.get('ProgramFiles', r'C:\Program Files')) / 'Docker/Docker/resources',
             Path(os.environ.get('LOCALAPPDATA', r'C:\Users\Default\AppData\Local')) / 'Programs/DockerDesktop/resources']
    return [folder / name / 'docker-credential-desktop.exe' for folder in roots for name in ('bin', '')]


def credential_status(home=None):
    """Read only helper names, never include credentials or raw Docker config in a result."""
    config = Path(home or Path.home()) / '.docker' / 'config.json'
    if not config.exists():
        return {'state': 'PASS', 'detail': 'No Docker credential helper configured.', 'repairable': False}
    try:
        if config.is_symlink() or config.stat().st_size > 65536:
            raise ValueError()
        content = json.loads(config.read_text(encoding='utf-8-sig'))
        if not isinstance(content, dict) or not isinstance(content.get('credHelpers', {}), dict):
            raise ValueError()
        helpers = content.get('credHelpers', {})
        name = (helpers.get('docker.io') or helpers.get('https://index.docker.io/v1/')
                or helpers.get('registry-1.docker.io') or content.get('credsStore'))
        if not name:
            return {'state': 'PASS', 'detail': 'No credential helper needed.', 'repairable': False}
        if not isinstance(name, str) or not re.fullmatch(r'[a-zA-Z0-9_-]{1,60}', name):
            raise ValueError()
    except (ValueError, UnicodeError, OSError, RecursionError):
        return {'state': 'ACTION', 'detail': 'Review Docker credential settings; unchanged.', 'repairable': False}
    executable_name = 'docker-credential-' + name + ('.exe' if is_windows() else '')
    if shutil.which(executable_name):
        return {'state': 'PASS', 'detail': 'Docker credential helper is available.', 'repairable': False}
    if name == 'desktop' and any(p.is_file() for p in _helpers()):
        return {'state': 'ACTION', 'detail': 'Docker helper exists but is absent from PATH.', 'repairable': True}
    return {'state': 'ACTION', 'detail': 'Docker helper is missing; repair Docker Desktop.', 'repairable': False}


def check_environment(cancel=None):
    """Read-only checks. Docker daemon is checked using Gateway's isolated local config."""
    checks = {}
    try:
        executable('git')
        checks['git'] = ('PASS', 'Git is available.')
    except ValueError:
        checks['git'] = ('ACTION', 'Install Git for Windows.')
    if is_windows():
        wsl = shutil.which('wsl.exe') or shutil.which('wsl')
        if wsl:
            try:
                validation.run([wsl, '--status'], cancel=cancel, timeout=20, label='Check WSL 2')
                checks['wsl'] = ('PASS', 'WSL is installed.')
            except ValueError:
                checks['wsl'] = ('ACTION', 'Install or update WSL 2; reboot if required.')
        else:
            checks['wsl'] = ('ACTION', 'WSL 2 is missing.')
    else:
        checks['wsl'] = ('ACTION', 'Windows prerequisites are available only on Windows.')
    try:
        executable('docker')
        try:
            validation.check_docker('docker', cancel=cancel)
            checks['docker'] = ('PASS', 'Local Docker engine runs Linux containers.')
        except ValueError:
            checks['docker'] = ('ACTION', 'Start Docker Desktop with WSL 2 Linux containers.')
    except ValueError:
        checks['docker'] = ('ACTION', 'Install Docker Desktop.')
    helper = credential_status()
    checks['credentials'] = (helper['state'], helper['detail'])
    return {'checks': checks, 'repairable_helper': helper['repairable'],
            'ready_for_validation': all(checks[k][0] == 'PASS' for k in ('git', 'wsl', 'docker'))}


def install_prerequisite(kind, *, approved=False, cancel=None):
    if not approved:
        raise ValueError('Confirm the system installation first.')
    if not is_windows():
        raise ValueError('Windows prerequisites can only be installed on Windows.')
    if kind == 'docker':
        winget = shutil.which('winget.exe') or shutil.which('winget')
        if not winget:
            raise ValueError('Windows Package Manager is unavailable. Open the official Docker installer.')
        argv = [winget, 'install', '--id', 'Docker.DockerDesktop', '--exact', '--source', 'winget',
                '--accept-package-agreements', '--accept-source-agreements']
        label = 'Install Docker Desktop'
    elif kind == 'wsl':
        wsl = shutil.which('wsl.exe') or shutil.which('wsl')
        argv = ([wsl, '--update'] if wsl else
                [str(Path(os.environ.get('SystemRoot', r'C:\Windows')) / 'System32/wsl.exe'),
                 '--install', '--no-distribution'])
        label = 'Install or update WSL'
    else:
        raise ValueError('Unsupported prerequisite; nothing was executed.')
    validation.run(argv, cancel=cancel, timeout=1800, label=label)
    return label + ' command completed; restart Windows if prompted, then Check PC again.'


def repair_helper_path(*, approved=False):
    """Add only the official installed helper folder to HKCU Path after confirmation."""
    if not approved:
        raise ValueError('Confirm the PATH change first.')
    if not is_windows():
        raise ValueError('Windows PATH repair is Windows-only.')
    if not credential_status()['repairable']:
        raise ValueError('No repairable Docker Desktop helper was found.')
    helper = next((p for p in _helpers() if p.is_file()), None)
    if helper is None:
        raise ValueError('Docker credential helper is missing; use the official Docker installer.')
    import winreg
    with winreg.CreateKeyEx(winreg.HKEY_CURRENT_USER, 'Environment', 0, winreg.KEY_READ | winreg.KEY_WRITE) as key:
        try:
            saved, value_type = winreg.QueryValueEx(key, 'Path')
        except FileNotFoundError:
            saved, value_type = '', winreg.REG_EXPAND_SZ
        if not isinstance(saved, str) or value_type not in (winreg.REG_SZ, winreg.REG_EXPAND_SZ):
            raise ValueError('Unsupported Windows user PATH; unchanged.')
        folder = str(helper.parent)
        entries = [v.strip().strip('"') for v in saved.split(';') if v.strip()]
        if any(os.path.normcase(os.path.expandvars(v).rstrip('\\/')) ==
               os.path.normcase(folder.rstrip('\\/')) for v in entries):
            return 'Credential helper is already present in the saved user PATH.'
        new_path = saved.rstrip(';') + (';' if saved.rstrip(';') else '') + folder
        if len(new_path) > 30000:
            raise ValueError('Windows user PATH is full; unchanged.')
        winreg.SetValueEx(key, 'Path', 0, value_type, new_path)
    return 'Credential helper added to user PATH. Restart Gateway and PowerShell to apply.'


def smoke_image(image, *, cancel=None):
    """Real offline, isolated smoke test, not a repository build or acceptance pass."""
    if not isinstance(image, str) or not validation.PIN.fullmatch(image):
        raise ValueError('A real approved image registry digest is required.')
    output = validation.docker_run(
        ['run', '--rm', '--pull=never', '--network=none', '--read-only',
         '--cap-drop=ALL', '--security-opt=no-new-privileges', '--pids-limit=64',
         '--memory=128m', image, 'python3', '--version'],
        cancel=cancel, timeout=90, label='Check disposable Linux container')
    if not output.startswith('Python 3.12.'):
        raise ValueError('Image test did not verify Python 3.12; no success is claimed.')
    return 'Verified a disposable, offline Python 3.12 Linux container.'
