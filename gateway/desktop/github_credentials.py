"""Store the desktop GitHub token in the current Windows user's credential set."""
import ctypes
import hashlib
import os
from ctypes import wintypes
from functools import lru_cache
from pathlib import Path

GENERIC = 1
LOCAL_MACHINE = 2
NOT_FOUND = 1168
MAX_TOKEN_BYTES = 512


class Credential(ctypes.Structure):
    _fields_ = [('Flags', wintypes.DWORD), ('Type', wintypes.DWORD), ('TargetName', wintypes.LPWSTR),
                ('Comment', wintypes.LPWSTR), ('LastWritten', wintypes.FILETIME), ('CredentialBlobSize', wintypes.DWORD),
                ('CredentialBlob', ctypes.POINTER(ctypes.c_ubyte)), ('Persist', wintypes.DWORD),
                ('AttributeCount', wintypes.DWORD), ('Attributes', ctypes.c_void_p),
                ('TargetAlias', wintypes.LPWSTR), ('UserName', wintypes.LPWSTR)]


def credential_target(root):
    scope = os.path.normcase(str(Path(root).resolve()))
    return 'GPT Mobile Gateway/GitHub/' + hashlib.sha256(scope.encode('utf-8')).hexdigest()


@lru_cache(maxsize=1)
def windows_credentials():
    if os.name != 'nt':
        raise ValueError('Windows Credential Manager is unavailable on this platform.')
    library = ctypes.WinDLL('Advapi32.dll', use_last_error=True)
    library.CredReadW.argtypes = (wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD, ctypes.POINTER(ctypes.POINTER(Credential)))
    library.CredReadW.restype = wintypes.BOOL
    library.CredWriteW.argtypes = (ctypes.POINTER(Credential), wintypes.DWORD)
    library.CredWriteW.restype = wintypes.BOOL
    library.CredDeleteW.argtypes = (wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD)
    library.CredDeleteW.restype = wintypes.BOOL
    library.CredFree.argtypes = (ctypes.c_void_p,)
    library.CredFree.restype = None
    return library


def token_bytes(token):
    if not isinstance(token, str) or not 1 <= len(token) <= MAX_TOKEN_BYTES or any(not 33 <= ord(ch) <= 126 for ch in token):
        raise ValueError('Enter a GitHub token without spaces.')
    return token.encode('ascii')


def read_token(root):
    if os.name != 'nt':
        return None
    api = windows_credentials()
    credential = ctypes.POINTER(Credential)()
    if not api.CredReadW(credential_target(root), GENERIC, 0, ctypes.byref(credential)):
        if ctypes.get_last_error() == NOT_FOUND:
            return None
        raise ValueError('Windows Credential Manager could not read the GitHub token.')
    try:
        entry = credential.contents
        if not entry.CredentialBlob or not 1 <= entry.CredentialBlobSize <= MAX_TOKEN_BYTES:
            raise ValueError('The saved GitHub token is invalid.')
        token = ctypes.string_at(entry.CredentialBlob, entry.CredentialBlobSize).decode('ascii')
        token_bytes(token)
        return token
    finally:
        api.CredFree(credential)


def save_token(root, token):
    raw = token_bytes(token)
    buffer = (ctypes.c_ubyte * len(raw)).from_buffer_copy(raw)
    credential = Credential(Type=GENERIC, TargetName=credential_target(root), CredentialBlobSize=len(raw),
                            CredentialBlob=buffer, Persist=LOCAL_MACHINE, UserName='GPT Mobile Gateway')
    try:
        if not windows_credentials().CredWriteW(ctypes.byref(credential), 0):
            raise ValueError('Windows Credential Manager could not save the GitHub token.')
    finally:
        ctypes.memset(buffer, 0, len(buffer))


def remove_token(root):
    api = windows_credentials()
    if not api.CredDeleteW(credential_target(root), GENERIC, 0) and ctypes.get_last_error() != NOT_FOUND:
        raise ValueError('Windows Credential Manager could not remove the GitHub token.')
