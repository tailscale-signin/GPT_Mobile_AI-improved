"""GPT Mobile's default Material palette, shared by the Windows dashboard/settings."""
from pathlib import Path
import os
import tkinter as tk
from tkinter import ttk

BG = '#001219'
SIDEBAR = '#061C24'
PANEL = '#0B2028'
RAISED = '#103239'
CYAN = '#00FFDE'
PURPLE = '#CC00FF'
TEXT = '#E0F4F7'
MUTED = '#B8DDE2'
LINE = '#294D53'
ERROR = '#FFB4AB'
ASSETS = Path(__file__).with_name('assets')
APP_ID = 'GPTMobile.Gateway'
FONT = 'Segoe UI'


def apply(window):
    window.configure(background=BG)
    if os.name == 'nt':
        import ctypes
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID(APP_ID)
        try:
            window.iconbitmap(str(ASSETS / 'gateway.ico'))
        except tk.TclError:
            pass
    try:
        window._brand_icon = tk.PhotoImage(file=str(ASSETS / 'gateway.png'))
        window.iconphoto(True, window._brand_icon)
    except tk.TclError:
        pass
    window.option_add('*Font', (FONT, 10))
    window.option_add('*TCombobox*Listbox.background', PANEL)
    window.option_add('*TCombobox*Listbox.foreground', TEXT)
    style = ttk.Style(window)
    style.theme_use('clam')
    style.configure('.', background=BG, foreground=TEXT, fieldbackground=PANEL, bordercolor=LINE, lightcolor=LINE, darkcolor=LINE)
    style.configure('TFrame', background=BG)
    style.configure('TLabel', background=BG, foreground=TEXT)
    style.configure('TNotebook', background=BG, borderwidth=0)
    style.configure('TNotebook.Tab', background=PANEL, foreground=MUTED, padding=(14, 9))
    style.map('TNotebook.Tab', background=[('selected', RAISED)], foreground=[('selected', CYAN)])
    style.configure('TPanedwindow', background=BG)
    style.configure('TButton', background=RAISED, foreground=TEXT, borderwidth=0, padding=(14, 9), focusthickness=1, focuscolor=CYAN)
    style.map('TButton', background=[('active', '#19505A'), ('disabled', PANEL)], foreground=[('disabled', MUTED)])
    style.configure('Accent.TButton', background=CYAN, foreground=BG)
    style.map('Accent.TButton', background=[('disabled', PANEL), ('active', '#8CFFF0')], foreground=[('disabled', MUTED)])
    style.configure('TEntry', fieldbackground=PANEL, foreground=TEXT, insertcolor=CYAN, padding=8)
    style.configure('TCombobox', fieldbackground=PANEL, foreground=TEXT, arrowcolor=CYAN, padding=6)
    style.map('TCombobox', fieldbackground=[('readonly', PANEL)], foreground=[('readonly', TEXT)])
    style.configure('TCheckbutton', background=BG, foreground=TEXT, indicatorbackground=PANEL, indicatorforeground=CYAN)
    style.map('TCheckbutton', background=[('active', BG)])
    style.configure('Treeview', background=PANEL, fieldbackground=PANEL, foreground=TEXT, borderwidth=0, rowheight=38, font=('Segoe UI', 10))
    style.configure('Treeview.Heading', background=RAISED, foreground=MUTED, relief='flat', font=('Segoe UI', 9, 'bold'), padding=(10, 10))
    style.map('Treeview', background=[('selected', '#164852')], foreground=[('selected', CYAN)])
    style.map('Treeview.Heading', background=[('active', RAISED)])
    style.configure('Vertical.TScrollbar', background=RAISED, troughcolor=PANEL, arrowcolor=MUTED, borderwidth=0)
    return style


def label(parent, content, size=10, color=TEXT, bold=False, bg=BG, **kwargs):
    return tk.Label(parent, text=content, foreground=color, background=bg,
                    font=(FONT, size, 'bold' if bold else 'normal'), **kwargs)
