"""Protect the installed phone listener's PEM passwords with user-scoped Windows DPAPI."""
import ctypes
from ctypes import wintypes
import os
from pathlib import Path
import secrets
import tempfile

from cryptography import x509
from cryptography.hazmat.primitives import serialization


class Blob(ctypes.Structure):
    _fields_ = [('size', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_ubyte))]


def _dpapi(value, *, encrypt):
    if not 0 < len(value) <= 8192:
        raise ValueError('Invalid phone listener key material.')
    lib = ctypes.WinDLL('crypt32', use_last_error=True)
    operation = lib.CryptProtectData if encrypt else lib.CryptUnprotectData
    operation.argtypes = (ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.POINTER(Blob), ctypes.c_void_p,
                          ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob))
    operation.restype = wintypes.BOOL
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.LocalFree.argtypes = (ctypes.c_void_p,)
    kernel.LocalFree.restype = ctypes.c_void_p
    raw = (ctypes.c_ubyte * len(value)).from_buffer_copy(value)
    source, dest = Blob(len(value), raw), Blob()
    try:
        # CRYPTPROTECT_UI_FORBIDDEN. Omitting LOCAL_MACHINE binds the result to this user.
        if not operation(ctypes.byref(source), None, None, None, None, 1, ctypes.byref(dest)):
            raise OSError('Windows could not unlock the phone listener for the current account.')
        return ctypes.string_at(dest.data, dest.size)
    finally:
        ctypes.memset(raw, 0, len(raw))
        if dest.data:
            ctypes.memset(dest.data, 0, dest.size)
            kernel.LocalFree(dest.data)


def atomic_bytes(path, contents):
    handle, name = tempfile.mkstemp(dir=path.parent, prefix='.phone-key-')
    try:
        with os.fdopen(handle, 'wb') as stream:
            stream.write(contents)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, path)
    finally:
        Path(name).unlink(missing_ok=True)


def password(folder, *, create=False):
    if os.name != 'nt':
        return None
    path = folder / 'tls-password.dpapi'
    if not path.exists() and create:
        # The caller holds the per-installation PKI lock. Do not overwrite another authority.
        if any(b'-----BEGIN ENCRYPTED PRIVATE KEY-----' in pem.read_bytes() for pem in folder.glob('*.pem')):
            raise ValueError('The existing phone listener credential is missing.')
        atomic_bytes(path, _dpapi(secrets.token_urlsafe(48).encode('ascii'), encrypt=True))
    value = _dpapi(path.read_bytes(), encrypt=False)
    if len(value) != 64:
        raise ValueError('Invalid phone listener credential.')
    return value


def pem(key, certificate, secret):
    protection = serialization.BestAvailableEncryption(secret) if secret else serialization.NoEncryption()
    return (key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, protection)
            + certificate.public_bytes(serialization.Encoding.PEM))


def protect_existing(folder, secret):
    """Keep certificate IDs and enrolled phones intact when upgrading an earlier installation."""
    if not secret:
        return
    for path in (folder / 'authority.pem', *folder.glob('server-*.pem')):
        if not path.exists():
            continue
        data = path.read_bytes()
        try:
            key = serialization.load_pem_private_key(data, password=None)
        except TypeError:
            # An encrypted key must also open with this installation's DPAPI password.
            serialization.load_pem_private_key(data, password=secret)
            continue
        atomic_bytes(path, pem(key, x509.load_pem_x509_certificate(data), secret))
