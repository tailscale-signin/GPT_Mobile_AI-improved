"""GitHub setup and connection checks for the Windows Settings window."""
import queue
import re
import threading
import tkinter as tk
import webbrowser
from pathlib import Path
from tkinter import ttk
from urllib.parse import quote

import requests

from desktop.addons import load_addons
from desktop.github_credentials import read_token, remove_token, save_token, token_bytes
from v14.engineering_backend import GitHubEngineeringBackend

CREATE_TOKEN_URL = 'https://github.com/settings/personal-access-tokens/new'
GITHUB_ADDONS = {'github-official', 'github-codefirst'}


def configured_connection(root, config):
    saved = read_token(root)
    if saved is not None:
        return 'Windows Credential Manager', saved

    def addons():
        path = Path(config['mcp_config']) if config['mcp_config'] else Path(root) / 'data' / 'mcp.json'
        try:
            settings, _ = load_addons(path)
        except FileNotFoundError:
            return {}
        except (OSError, ValueError):
            raise ValueError('Check the MCP configuration file in Add-ons.') from None
        return {name: value for name, value in settings['mcpServers'].items() if name in GITHUB_ADDONS and not value.get('disabled')}

    return GitHubEngineeringBackend.configured_credential(addons, read_only=True)


def test_connection(token, repository):
    if repository and (not re.fullmatch(r'[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}', repository) or
                       {'.', '..'} & set(repository.split('/'))):
        raise ValueError('Enter a repository as owner/name.')
    if token is None and not repository:
        raise ValueError('Enter a repository to test public access.')
    headers = {'Accept': 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28'}
    if token is not None:
        token_bytes(token)
        headers['Authorization'] = 'Bearer ' + token

    def get(path):
        try:
            with requests.get('https://api.github.com' + path, headers=headers, auth=lambda request: request,
                              allow_redirects=False, stream=True, timeout=(5, 10)) as response:
                status = response.status_code
                if status == 401:
                    raise ValueError('GitHub rejected the token. Check whether it has expired.')
                if status == 429 or (status == 403 and (response.headers.get('X-RateLimit-Remaining') == '0' or 'Retry-After' in response.headers)):
                    raise ValueError('GitHub limited these requests. Try again later.')
                if status in {403, 404}:
                    raise ValueError('GitHub did not grant access. Check the repository name, token access and Contents permission.')
                if status != 200:
                    raise ValueError(f'GitHub returned HTTP {status}.')
                return GitHubEngineeringBackend.read_response(response, False)
        except (requests.RequestException, OSError):
            raise ValueError('Could not reach GitHub from this PC.') from None

    account = get('/user').get('login') if token is not None else None
    if token is not None and (not isinstance(account, str) or not re.fullmatch(r'[A-Za-z0-9-]{1,100}', account)):
        raise ValueError('GitHub did not confirm an account for this token.')
    if not repository:
        return f'GitHub confirmed {account}.'
    path = '/repos/' + repository
    metadata = get(path)
    if metadata.get('full_name', '').lower() != repository.lower() or (token is None and metadata.get('private') is not False):
        raise ValueError('GitHub did not confirm access to this repository.')
    branch = metadata.get('default_branch')
    if not isinstance(branch, str) or not branch:
        raise ValueError('GitHub did not report a default branch for this repository.')
    get(path + '/git/ref/heads/' + quote(branch, safe=''))
    return f'{account} can read {repository}.' if account else f'Public access to {repository} confirmed.'


def build_github_settings(frame, root, config, public_maps, network_engineering):
    ttk.Checkbutton(frame, text='Enable public repository maps', variable=public_maps).grid(row=2, column=0, columnspan=3, sticky='w')
    ttk.Label(frame, text='Public maps work without a token. Save settings and restart to apply this option.',
              style='Muted.TLabel', wraplength=570).grid(row=3, column=0, columnspan=3, sticky='w', pady=(3, 18))
    ttk.Checkbutton(frame, text='Allow network devices to run GitHub engineering tasks',
                    variable=network_engineering).grid(row=4, column=0, columnspan=3, sticky='w')
    ttk.Label(frame, text='This also lets your phone map private repositories using the PC’s GitHub connection.',
              style='Muted.TLabel', wraplength=570).grid(row=5, column=0, columnspan=3, sticky='w', pady=(3, 18))

    status = tk.StringVar()
    ttk.Label(frame, textvariable=status, style='Muted.TLabel', wraplength=570).grid(row=6, column=0, columnspan=3, sticky='w', pady=(0, 7))
    ttk.Label(frame, text='Personal access token').grid(row=7, column=0, sticky='w', padx=(0, 16))
    secret = ttk.Entry(frame, show='*')
    secret.grid(row=7, column=1, columnspan=2, sticky='ew', pady=5)
    ttk.Label(frame, text='Repository to test (optional)').grid(row=8, column=0, sticky='w', padx=(0, 16))
    repository = ttk.Entry(frame)
    repository.grid(row=8, column=1, columnspan=2, sticky='ew', pady=5)
    ttk.Label(frame, text='For maps, grant Contents read access to your selected repositories. Saved tokens go in Windows Credential Manager.',
              style='Muted.TLabel', wraplength=570).grid(row=9, column=0, columnspan=3, sticky='w', pady=(5, 10))

    actions = ttk.Frame(frame)
    actions.grid(row=10, column=0, columnspan=3, sticky='w')
    result_text = tk.StringVar()
    ttk.Label(frame, textvariable=result_text, style='Muted.TLabel', wraplength=570).grid(row=11, column=0, columnspan=3, sticky='w', pady=(9, 0))
    results = queue.Queue()
    pending_token = None
    busy = False

    def refresh_status():
        try:
            source, _ = configured_connection(root, config)
            status.set('GitHub connection configured · ' + source)
        except ValueError:
            status.set('Connect an account for private repositories or saved jobs.')

    def set_busy(value):
        nonlocal busy
        busy = value
        for widget in (secret, repository, connect, check, remove):
            widget.configure(state='disabled' if value else 'normal')

    def start(connecting):
        nonlocal pending_token
        if busy:
            return
        target = repository.get().strip()
        token = secret.get().strip()
        try:
            if connecting:
                token_bytes(token)
            elif not token:
                try:
                    _, token = configured_connection(root, config)
                except ValueError:
                    token = None
        except ValueError as error:
            result_text.set(str(error))
            return
        pending_token = token if connecting else None
        set_busy(True)
        result_text.set('Checking GitHub…')

        def work():
            try:
                results.put((True, test_connection(token, target)))
            except ValueError as error:
                results.put((False, str(error)))
            except Exception:
                results.put((False, 'GitHub returned an unreadable response.'))

        threading.Thread(target=work, daemon=True).start()

    def disconnect():
        try:
            remove_token(root)
            refresh_status()
            result_text.set('Saved Windows token removed.')
        except ValueError as error:
            result_text.set(str(error))

    connect = ttk.Button(actions, text='Connect', command=lambda: start(True))
    check = ttk.Button(actions, text='Test connection', command=lambda: start(False))
    remove = ttk.Button(actions, text='Remove saved token', command=disconnect)
    create = ttk.Button(actions, text='Create GitHub token', command=lambda: webbrowser.open(CREATE_TOKEN_URL))
    for widget in (connect, check, remove, create):
        widget.pack(side='left', padx=(0, 7))
    refresh_status()

    def refresh():
        nonlocal pending_token
        try:
            success, message = results.get_nowait()
        except queue.Empty:
            return
        set_busy(False)
        if success and pending_token is not None:
            try:
                save_token(root, pending_token)
                secret.delete(0, tk.END)
                refresh_status()
                message += ' The token is saved.'
            except ValueError as error:
                message = str(error)
        pending_token = None
        result_text.set(message)

    frame.bind('<Map>', lambda _: refresh_status())
    return refresh
