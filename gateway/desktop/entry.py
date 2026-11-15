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
    parser.add_argument('command', choices=['supervise', 'settings', 'github-settings', 'engineering-setup', 'validate-ui', 'configure', 'validate-settings', 'serve-engine', 'validate-engine', 'stop', 'restart', 'check', 'install', 'firewall', 'remove-firewall', 'dashboard'])
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--version')
    parser.add_argument('--quiet', action='store_true')
    parser.add_argument('--error-file', type=Path)
    parser.add_argument('--settings-file', type=Path)
    parser.add_argument('--smoke-ui', action='store_true')
    args = parser.parse_args()
    root = args.root.resolve()
    if args.command in {'firewall', 'remove-firewall'}:
        from desktop.firewall import apply
        if args.command == 'firewall' or (root / 'firewall.json').exists():
            apply(root, remove=args.command == 'remove-firewall')
        return 0
    if args.command in {'serve-engine', 'validate-engine'}:
        if args.command == 'validate-engine':
            # Run inside the candidate executable so missing frozen native grammars fail update preflight.
            from v14.code_graph.extract import extract
            samples = {'kt': 'fun probe(): Int { return 1 }', 'java': 'class Probe { int probe() { return 1; } }',
                       'js': 'function probe() { return 1; }', 'ts': 'function probe(): number { return 1; }',
                       'tsx': 'function probe(): number { return 1; }'}
            for extension, source in samples.items():
                fragment = extract('probe.' + extension, source)
                if fragment['status'] != 'indexed' or not any(n['name'] == 'probe' and n['kind'] == 'function' for n in fragment['nodes']):
                    raise RuntimeError('Packaged graph parser failed validation: ' + extension)
        token = os.environ.get('GATEWAY_MANAGEMENT_TOKEN', '')
        quiesced = os.environ.get('GATEWAY_START_QUIESCED', '')
        env = environment(root, load(root), token)
        env['GATEWAY_START_QUIESCED'] = quiesced
        os.environ.clear()
        os.environ.update(env)
        sys.argv = ['gateway.py', 'serve' if args.command == 'serve-engine' else 'validate-config']
        runpy.run_path(str(SOURCE / 'gateway.py'), run_name='__main__')
        return 0
    if args.command == 'validate-settings':
        load(root) if (root / 'settings.json').exists() else validate(json.loads(args.settings_file.read_text(encoding='utf-8-sig')))
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
    if args.command == 'dashboard':
        from desktop.dashboard import show
        show(root, smoke=args.smoke_ui)
        return 0
    if args.command == 'engineering-setup':
        from desktop.engineering_setup_panel import show
        show(root)
        return 0
    if args.command in {'settings', 'github-settings'}:
        from desktop.ui import show
        show(root, section='GitHub' if args.command == 'github-settings' else 'General')
        return 0
    if args.command == 'validate-ui':
        from desktop.ui import build_window
        from tkinter import ttk
        window = build_window(root)
        try:
            window.update()
            notebook = next(child for child in window.winfo_children() if isinstance(child, ttk.Notebook))
            assert [notebook.tab(tab, 'text') for tab in notebook.tabs()] == ['General', 'Advanced', 'Add-ons', 'GitHub']
            window.geometry('660x500')
            for tab in notebook.tabs():
                notebook.select(tab)
                window.update()
            assert window.winfo_width() >= 660
            window.lift()
            github_tabs = window.github_tabs
            assert [github_tabs.tab(tab, 'text') for tab in github_tabs.tabs()] == ['Connection', 'Repository Validation']
            for tab in github_tabs.tabs():
                github_tabs.select(tab)
                window.update()
            panel = window.repository_validation_panel
            assert not panel.advanced_visible.get()
            # Child mapping can survive a hidden ancestor; check actual viewability.
            assert not panel.advanced.winfo_viewable()
            assert not panel.commands.winfo_viewable() and not panel.contract.winfo_viewable()

            def visible(control):
                assert control.winfo_viewable(), str(control)
                assert control.winfo_width() > 40 and control.winfo_height() > 12, str(control)
                assert window.winfo_rootx() <= control.winfo_rootx()
                assert window.winfo_rooty() <= control.winfo_rooty()
                assert control.winfo_rootx() + control.winfo_width() <= window.winfo_rootx() + window.winfo_width()
                assert control.winfo_rooty() + control.winfo_height() <= window.winfo_rooty() + window.winfo_height()
                assert window.winfo_containing(control.winfo_rootx() + control.winfo_width() // 2,
                                                control.winfo_rooty() + control.winfo_height() // 2) is control

            def capture(name):
                if os.name != 'nt':
                    return
                import subprocess
                x, y = window.winfo_rootx(), window.winfo_rooty()
                width, height = window.winfo_width(), window.winfo_height()
                script = ('Add-Type -AssemblyName System.Drawing; '
                          f'$b=New-Object System.Drawing.Bitmap({width},{height}); '
                          '$g=[System.Drawing.Graphics]::FromImage($b); '
                          f'$g.CopyFromScreen({x},{y},0,0,$b.Size); '
                          '$b.Save($env:GATEWAY_UI_CAPTURE,[System.Drawing.Imaging.ImageFormat]::Png); '
                          '$g.Dispose(); $b.Dispose()')
                subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', script],
                               env=dict(os.environ, GATEWAY_UI_CAPTURE=str(root / name)),
                               check=True, timeout=30, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))

            for control in (panel.enable_button, panel.repository_picker, panel.profile_box, panel.advanced_toggle,
                            panel.prepare_button, panel.check_button, panel.save_button, panel.reload_button):
                visible(control)
            capture('settings-repository-validation-basic-compact.png')
            panel.advanced_toggle.invoke()
            window.update()
            assert panel.advanced.winfo_viewable() and panel.commands.winfo_viewable()
            panel.reveal(panel.contract)
            window.update()
            assert panel.contract.winfo_viewable()
            assert window.winfo_containing(panel.contract.winfo_rootx() + panel.contract.winfo_width() // 2,
                                            panel.contract.winfo_rooty() + panel.contract.winfo_height() // 2) is panel.contract
            capture('settings-repository-validation-advanced-compact.png')
            panel.advanced_toggle.invoke()
            window.update()
            assert not panel.advanced.winfo_viewable()
            assert not panel.commands.winfo_viewable() and not panel.contract.winfo_viewable()
        finally:
            window.destroy()
        return 0
    if args.command == 'supervise':
        return supervise(root)
    request(root, args.command)
    if args.command == 'stop':
        from desktop.window_lifecycle import close_windows
        close_windows(root)
    return 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print(str(error), file=sys.stderr)
        # No silent errors from desktop shortcuts/installer's hidden child process.
        if '--error-file' in sys.argv:
            Path(sys.argv[sys.argv.index('--error-file') + 1]).write_text(str(error), encoding='utf-8-sig')
        if os.name == 'nt' and '--quiet' not in sys.argv:
            import ctypes
            ctypes.windll.user32.MessageBoxW(None, str(error), 'Gateway could not complete the operation', 0x10)
        sys.exit(1)
