"""Windows notification-area icon using the OS API; no extra runtime dependency."""
import os
from pathlib import Path
import threading


class TrayIcon:
    def __init__(self, tooltip, actions):
        self.tooltip, self.actions = tooltip, actions
        self.window = None
        self.available = False
        self.error = None
        self.ready = threading.Event()
        self.thread = None

    def start(self):
        if os.name == 'nt':
            self.thread = threading.Thread(target=self._run, name='gateway-tray', daemon=True)
            self.thread.start()
        return self

    def close(self):
        if self.window:
            import ctypes
            from ctypes import wintypes as wt
            post = ctypes.windll.user32.PostMessageW
            post.argtypes = [wt.HWND, wt.UINT, wt.WPARAM, wt.LPARAM]
            post(self.window, 0x0010, 0, 0)
        if self.thread:
            self.thread.join(timeout=5)

    def _run(self):
        try:
            self._windows_loop()
        except Exception as exc:
            self.error = str(exc)
        finally:
            self.available = False
            self.window = None
            self.ready.set()

    def _windows_loop(self):
        import ctypes as c
        from ctypes import wintypes as w
        user = c.WinDLL('user32', use_last_error=True)
        shell = c.WinDLL('shell32', use_last_error=True)
        kernel = c.WinDLL('kernel32', use_last_error=True)
        callback = c.WINFUNCTYPE(c.c_ssize_t, w.HWND, w.UINT, w.WPARAM, w.LPARAM)
        class WindowClass(c.Structure):
            _fields_ = [('style', w.UINT), ('proc', callback), ('classExtra', c.c_int), ('windowExtra', c.c_int),
                        ('instance', w.HINSTANCE), ('icon', w.HICON), ('cursor', w.HANDLE), ('background', w.HBRUSH),
                        ('menuName', w.LPCWSTR), ('className', w.LPCWSTR)]
        class Guid(c.Structure):
            _fields_ = [('a', w.DWORD), ('b', w.WORD), ('c', w.WORD), ('d', c.c_ubyte * 8)]
        class NotifyIcon(c.Structure):
            _fields_ = [('size', w.DWORD), ('window', w.HWND), ('id', w.UINT), ('flags', w.UINT),
                        ('message', w.UINT), ('icon', w.HICON), ('tip', w.WCHAR * 128), ('state', w.DWORD),
                        ('stateMask', w.DWORD), ('info', w.WCHAR * 256), ('version', w.UINT),
                        ('infoTitle', w.WCHAR * 64), ('infoFlags', w.DWORD), ('guid', Guid), ('balloonIcon', w.HICON)]
        def api(dll, name, result, arguments):
            fn = getattr(dll, name)
            fn.restype, fn.argtypes = result, arguments
            return fn
        register = api(user, 'RegisterClassW', w.ATOM, [c.POINTER(WindowClass)])
        unregister = api(user, 'UnregisterClassW', w.BOOL, [w.LPCWSTR, w.HINSTANCE])
        create = api(user, 'CreateWindowExW', w.HWND, [w.DWORD, w.LPCWSTR, w.LPCWSTR, w.DWORD, c.c_int, c.c_int, c.c_int, c.c_int, w.HWND, w.HMENU, w.HINSTANCE, w.LPVOID])
        default = api(user, 'DefWindowProcW', c.c_ssize_t, [w.HWND, w.UINT, w.WPARAM, w.LPARAM])
        destroy = api(user, 'DestroyWindow', w.BOOL, [w.HWND])
        module = api(kernel, 'GetModuleHandleW', w.HINSTANCE, [w.LPCWSTR])
        load = api(user, 'LoadImageW', w.HANDLE, [w.HINSTANCE, w.LPCWSTR, w.UINT, c.c_int, c.c_int, w.UINT])
        notify = api(shell, 'Shell_NotifyIconW', w.BOOL, [w.DWORD, c.POINTER(NotifyIcon)])
        load_message = api(user, 'RegisterWindowMessageW', w.UINT, [w.LPCWSTR])
        menu_create = api(user, 'CreatePopupMenu', w.HMENU, [])
        menu_append = api(user, 'AppendMenuW', w.BOOL, [w.HMENU, w.UINT, c.c_size_t, w.LPCWSTR])
        menu_destroy = api(user, 'DestroyMenu', w.BOOL, [w.HMENU])
        position = api(user, 'GetCursorPos', w.BOOL, [c.POINTER(w.POINT)])
        foreground = api(user, 'SetForegroundWindow', w.BOOL, [w.HWND])
        menu_show = api(user, 'TrackPopupMenu', w.UINT, [w.HMENU, w.UINT, c.c_int, c.c_int, c.c_int, w.HWND, w.LPVOID])
        timer = api(user, 'SetTimer', c.c_size_t, [w.HWND, c.c_size_t, w.UINT, w.LPVOID])
        get_message = api(user, 'GetMessageW', c.c_int, [c.POINTER(w.MSG), w.HWND, w.UINT, w.UINT])
        dispatch = api(user, 'DispatchMessageW', c.c_ssize_t, [c.POINTER(w.MSG)])
        translate = api(user, 'TranslateMessage', w.BOOL, [c.POINTER(w.MSG)])
        destroy_icon = api(user, 'DestroyIcon', w.BOOL, [w.HICON])
        post_quit = api(user, 'PostQuitMessage', None, [c.c_int])
        instance = module(None)
        class_name = 'GPTMobileGatewayTray-' + str(os.getpid())
        taskbar_created = load_message('TaskbarCreated')
        data = NotifyIcon()
        data.size = c.sizeof(NotifyIcon)
        data.id, data.flags, data.message = 1, 1 | 2 | 4 | 0x80, 0x8001
        icon = load(None, str(Path(__file__).with_name('assets') / 'gateway.ico'), 1, 32, 32, 0x10)
        if not icon:
            raise OSError(c.get_last_error(), 'Could not load Gateway tray icon')
        data.icon = icon
        def add_icon():
            data.tip = self.tooltip()[:127]
            self.available = bool(notify(0, c.byref(data)))
            if self.available:
                data.version = 4
                notify(4, c.byref(data))
        def invoke(name):
            def action():
                try:
                    self.actions[name]()
                except Exception as exc:
                    # The OS dialog remains local and cannot expose API tokens.
                    c.windll.user32.MessageBoxW(None, str(exc), 'Gateway', 0x10)
            threading.Thread(target=action, name='gateway-tray-action', daemon=True).start()
        @callback
        def procedure(hwnd, message, wp, lp):
            try:
                if message == taskbar_created:
                    add_icon()
                    return 0
                if message == 0x0113:  # WM_TIMER
                    data.tip = self.tooltip()[:127]
                    notify(1, c.byref(data))
                    return 0
                if message == data.message:
                    event = lp & 0xFFFF
                    if event in (0x0400, 0x0401, 0x0203):  # select, keyboard select, double-click
                        invoke('dashboard')
                    elif event in (0x007B, 0x0205):
                        popup = menu_create()
                        try:
                            menu_append(popup, 1, 0, self.tooltip()[:100])
                            menu_append(popup, 0x800, 0, None)
                            for command, name in enumerate(('dashboard', 'settings', 'restart', 'stop'), 1):
                                menu_append(popup, 0, command, {'dashboard': 'Open dashboard', 'settings': 'Connection settings', 'restart': 'Restart gateway', 'stop': 'Stop gateway and exit'}[name])
                            point = w.POINT()
                            position(c.byref(point))
                            foreground(hwnd)
                            chosen = menu_show(popup, 0x100 | 0x2, point.x, point.y, 0, hwnd, None)
                            if 1 <= chosen <= 4:
                                invoke(('dashboard', 'settings', 'restart', 'stop')[chosen - 1])
                        finally:
                            menu_destroy(popup)
                    return 0
                if message == 0x0010:
                    notify(2, c.byref(data))
                    destroy(hwnd)
                    return 0
                if message == 0x0002:
                    post_quit(0)
                    return 0
            except Exception as exc:
                self.error = str(exc)
            return default(hwnd, message, wp, lp)
        window_class = WindowClass(0, procedure, 0, 0, instance, icon, None, None, None, class_name)
        registered = register(c.byref(window_class))
        if not registered:
            destroy_icon(icon)
            raise OSError(c.get_last_error(), 'Could not register Gateway tray window')
        try:
            self.window = create(0, class_name, 'GPT Mobile Gateway', 0, 0, 0, 0, 0, None, None, instance, None)
            if not self.window:
                raise OSError(c.get_last_error(), 'Could not create Gateway tray window')
            data.window = self.window
            add_icon()
            timer(self.window, 1, 2000, None)
            self.ready.set()
            message = w.MSG()
            while get_message(c.byref(message), None, 0, 0) > 0:
                translate(c.byref(message))
                dispatch(c.byref(message))
        finally:
            notify(2, c.byref(data))
            if self.window:
                destroy(self.window)
            destroy_icon(icon)
            unregister(class_name, instance)
