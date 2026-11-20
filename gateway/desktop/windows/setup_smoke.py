"""Exercise the interactive, English Inno wizard in the isolated Windows packaging job."""
import ctypes
from ctypes import wintypes
from pathlib import Path
import subprocess
import time


def check_wizard(installer, root, output):
    user = ctypes.WinDLL('user32', use_last_error=True)
    callback = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    user.EnumWindows.argtypes = (callback, wintypes.LPARAM)
    user.EnumChildWindows.argtypes = (wintypes.HWND, callback, wintypes.LPARAM)
    user.SendMessageW.argtypes = (wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)
    user.SendMessageW.restype = wintypes.LPARAM
    user.PostMessageW.argtypes = (wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM)
    user.GetClassNameW.argtypes = (wintypes.HWND, wintypes.LPWSTR, ctypes.c_int)
    user.IsWindowVisible.argtypes = (wintypes.HWND,)
    user.GetWindow.argtypes = (wintypes.HWND, wintypes.UINT)
    user.GetWindow.restype = wintypes.HWND

    def windows(parent=None):
        result = []
        @callback
        def collect(handle, unused):
            if user.IsWindowVisible(handle):
                result.append(handle)
            return True
        if parent:
            user.EnumChildWindows(parent, collect, 0)
        else:
            user.EnumWindows(collect, 0)
        return result

    def name(handle):
        value = ctypes.create_unicode_buffer(256)
        user.GetClassNameW(handle, value, len(value))
        return value.value

    def label(handle):
        value = ctypes.create_unicode_buffer(1024)
        user.SendMessageW(handle, 0x000D, len(value), ctypes.addressof(value))
        return value.value.replace('&', '').strip()

    def wait(find, description):
        deadline = time.monotonic() + 18
        while time.monotonic() < deadline:
            result = find()
            if result:
                return result
            time.sleep(.1)
        raise RuntimeError('Interactive setup did not show ' + description)

    def child(form, test):
        return next((w for w in windows(form) if test(w)), None)

    def button(form, title):
        item = wait(lambda: child(form, lambda x: 'button' in name(x).lower() and title in label(x)), title)
        user.PostMessageW(item, 0x00F5, 0, 0)  # BM_CLICK: post so a modal validation prompt cannot block the driver.

    def radio(form, index):
        box = wait(lambda: child(form, lambda x: 'checklistbox' in name(x).lower()), 'network choices')
        rect = wintypes.RECT()
        if user.SendMessageW(box, 0x0198, index, ctypes.addressof(rect)) == -1:  # LB_GETITEMRECT
            raise AssertionError('Missing network choice')
        point = (rect.left + 12) | (((rect.top + rect.bottom) // 2) << 16)
        user.SendMessageW(box, 0x0201, 1, point)
        user.SendMessageW(box, 0x0202, 0, point)

    def modal(form):
        return next((w for w in windows() if user.GetWindow(w, 4) == form and name(w) != 'TWizardForm'), None)

    def capture(form, filename):
        rect = wintypes.RECT()
        user.GetWindowRect(wintypes.HWND(form), ctypes.byref(rect))
        import os
        env = dict(os.environ, GATEWAY_UI_CAPTURE=str(Path(output) / filename))
        script = ('Add-Type -AssemblyName System.Drawing; '
                  f'$b = New-Object Drawing.Bitmap({rect.right-rect.left},{rect.bottom-rect.top}); '
                  '$g=[Drawing.Graphics]::FromImage($b); '
                  f'$g.CopyFromScreen({rect.left},{rect.top},0,0,$b.Size); '
                  '$b.Save($env:GATEWAY_UI_CAPTURE,[Drawing.Imaging.ImageFormat]::Png); $g.Dispose(); $b.Dispose()')
        subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', script],
                       env=env, check=True, timeout=20)

    previous = set(windows())
    process = subprocess.Popen([str(installer), '/LANG=english', '/NORESTART', '/SP-', f'/DIR={root}'])
    try:
        form = wait(lambda: next((w for w in windows() if w not in previous and name(w) == 'TWizardForm'), None), 'main window')
        if not child(form, lambda x: label(x) == str(root)):
            button(form, 'Next')
        wait(lambda: child(form, lambda x: label(x) == str(root)), 'install folder')
        button(form, 'Next')
        wait(lambda: child(form, lambda x: label(x) == 'Connect to Gateway'), 'connection page')
        capture(form, 'setup-connection.png')
        radio(form, 2)
        button(form, 'Next')
        port = wait(lambda: child(form, lambda x: 'edit' in name(x).lower() and label(x) == '8090'), 'advanced port')
        invalid = ctypes.create_unicode_buffer('1')
        user.SendMessageW(port, 0x000C, 0, ctypes.addressof(invalid))
        button(form, 'Next')
        warning = wait(lambda: modal(form), 'invalid-port message')
        button(warning, 'OK')
        wait(lambda: child(form, lambda x: label(x) == 'Connection settings'), 'editable settings')
        capture(form, 'setup-advanced.png')
        button(form, 'Back')
        wait(lambda: child(form, lambda x: label(x) == 'Connect to Gateway'), 'connection choices')
        radio(form, 0)
        button(form, 'Next')
        wait(lambda: child(form, lambda x: 'Select Additional Tasks' in label(x)), 'installation options')
        button(form, 'Cancel')
        confirm = wait(lambda: modal(form), 'exit confirmation')
        button(confirm, 'Yes')
        process.wait(timeout=15)
        if (Path(root) / 'current.json').exists():
            raise AssertionError('Cancelling setup wrote an installation')
    finally:
        if process.poll() is None:
            subprocess.run(['taskkill', '/PID', str(process.pid), '/T', '/F'], capture_output=True, timeout=10)
