"""Frozen Windows entry point; engine imports happen only after validated settings."""
import argparse
import json
import os
from pathlib import Path
import runpy
import sys

# Source development and PyInstaller use the same import roots.
SOURCE = Path(getattr(sys, '_MEIPASS', Path(__file__).resolve().parents[1]))
sys.path.insert(0, str(SOURCE))
from desktop.configuration import DEFAULTS, atomic_json, environment, load, validate
from desktop.manager import read_pointer, release_path, request, startup_registration, supervise
from desktop.releases import validate_release
from v14.state_lock import StateDirectoryLock


def main():
    parser = argparse.ArgumentParser(description='GPT Mobile Gateway desktop manager')
    parser.add_argument('command', choices=['supervise', 'settings', 'configure', 'serve-engine', 'validate-engine', 'stop', 'restart', 'check', 'install'])
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--version')
    parser.add_argument('--settings-file', type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    if args.command in {'serve-engine', 'validate-engine'}:
        token = os.environ.get('GATEWAY_MANAGEMENT_TOKEN', '')
        quiesced = os.environ.get('GATEWAY_START_QUIESCED', '')
        env = environment(root, load(root), token)
        env['GATEWAY_START_QUIESCED'] = quiesced
        os.environ.clear()
        os.environ.update(env)
        sys.argv = ['gateway.py', 'serve' if args.command == 'serve-engine' else 'validate-config']
        runpy.run_path(str(SOURCE / 'gateway.py'), run_name='__main__')
        return 0
    if args.command == 'configure':
        # Installer only writes config/pointer after the existing supervisor has stopped.
        lock = StateDirectoryLock(root / 'manager.sqlite3')
        lock.acquire()
        try:
            validate_release(release_path(root, args.version), args.version)
            cfg = load(root) if (root / 'settings.json').exists() else validate(json.loads(args.settings_file.read_text(encoding='utf-8-sig')) if args.settings_file else DEFAULTS)
            atomic_json(root / 'settings.json', cfg)
            previous = read_pointer(root)['version'] if (root / 'current.json').exists() else None
            if previous and args.version != previous:
                from desktop.releases import version
                if version(args.version) < version(previous):
                    raise ValueError('Installer downgrade refused; use the compatible newer installer')
            atomic_json(root / 'current.json', {'version': args.version, 'previous': previous, 'pending': bool(previous and previous != args.version)})
            startup_registration(root, cfg['start_at_login'])
        finally:
            lock.release()
        return 0
    if args.command == 'settings':
        from desktop.ui import show
        show(root)
        return 0
    if args.command == 'supervise':
        return supervise(root)
    request(root, args.command)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print(str(error), file=sys.stderr)
        # No silent errors from desktop shortcuts/installer's hidden child process.
        if os.name == 'nt':
            import ctypes
            ctypes.windll.user32.MessageBoxW(None, str(error), 'Gateway could not complete the operation', 0x10)
        sys.exit(1)
