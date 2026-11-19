"""Pinned TLS identity and proof-of-possession for the separate control listener."""
import base64
import ctypes
from ctypes import wintypes
from datetime import datetime, timedelta, timezone
import hashlib
import ipaddress
import os
from pathlib import Path
import secrets

from cryptography import x509
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import NameOID, ExtendedKeyUsageOID

from desktop.control_store import ControlError


def _dpapi(data, protect):
    if not 0 < len(data) <= 8192:
        raise ValueError('Invalid protected control identity')
    class Blob(ctypes.Structure):
        _fields_ = [('length', wintypes.DWORD), ('data', ctypes.POINTER(ctypes.c_ubyte))]
    crypt = ctypes.WinDLL('crypt32', use_last_error=True)
    function = crypt.CryptProtectData if protect else crypt.CryptUnprotectData
    function.argtypes = (ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.POINTER(Blob),
                         ctypes.c_void_p, ctypes.c_void_p, wintypes.DWORD, ctypes.POINTER(Blob))
    function.restype = wintypes.BOOL
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.LocalFree.argtypes = (ctypes.c_void_p,)
    kernel.LocalFree.restype = ctypes.c_void_p
    source = (ctypes.c_ubyte * len(data)).from_buffer_copy(data)
    incoming, outgoing = Blob(len(data), source), Blob()
    try:
        # CRYPTPROTECT_UI_FORBIDDEN; no LOCAL_MACHINE flag, so the identity
        # remains bound to the Windows account that owns this installation.
        if not function(ctypes.byref(incoming), None, None, None, None, 1, ctypes.byref(outgoing)):
            raise OSError('Windows could not protect the Gateway control identity')
        return ctypes.string_at(outgoing.data, outgoing.length)
    finally:
        ctypes.memset(source, 0, len(source))
        if outgoing.data:
            ctypes.memset(outgoing.data, 0, outgoing.length)
            kernel.LocalFree(outgoing.data)


def private_write(path, value):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'wb') as stream:
        stream.write(value)
        stream.flush()
        os.fsync(stream.fileno())


def server_secret(root):
    path = Path(root) / 'data' / 'control-identity.secret'
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_symlink():
        raise ValueError('Control identity cannot be a symbolic link')
    if not path.exists():
        # A missing existing key must never create a different identity behind
        # an old paired device or silently invalidate its delivery receipt.
        if (path.parent / 'control-certificate.pem').exists():
            raise ValueError('Restore the protected control identity before pairing')
        value = secrets.token_bytes(32)
        private_write(path, b'DPAPI1' + _dpapi(value, True) if os.name == 'nt' else b'LOCAL1' + value)
    raw = path.read_bytes()
    value = _dpapi(raw[6:], False) if raw.startswith(b'DPAPI1') and os.name == 'nt' else raw[6:] if raw.startswith(b'LOCAL1') and os.name != 'nt' else b''
    if len(value) != 32:
        raise ValueError('Protected control identity is unavailable')
    return value


def tls_identity(root, host, secret):
    address = ipaddress.ip_address(host)
    if address.is_unspecified or address.is_multicast:
        raise ValueError('Choose one reachable interface IP for phone controls')
    directory = Path(root) / 'data'
    certificate, key_path = directory / 'control-certificate.pem', directory / 'control-private-key.pem'
    if certificate.exists():
        cert = x509.load_pem_x509_certificate(certificate.read_bytes())
        sans = cert.extensions.get_extension_for_class(x509.SubjectAlternativeName).value
        if address not in sans.get_values_for_type(x509.IPAddress):
            raise ValueError('This control certificate is pinned to another interface; retain that listener or pair a new installation')
        if cert.not_valid_after_utc <= datetime.now(timezone.utc):
            raise ValueError('The pinned control certificate expired; renew it on the PC and pair again')
        serialization.load_pem_private_key(key_path.read_bytes(), password=secret.hex().encode())
    else:
        key = ec.generate_private_key(ec.SECP256R1())
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'GPT Mobile Gateway control')])
        now = datetime.now(timezone.utc)
        addresses = list(dict.fromkeys([address, ipaddress.ip_address('127.0.0.1'), ipaddress.ip_address('::1')]))
        cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
                .serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(minutes=5))
                .not_valid_after(now + timedelta(days=3650))
                .add_extension(x509.SubjectAlternativeName([x509.DNSName('localhost')] + [x509.IPAddress(x) for x in addresses]), critical=False)
                .add_extension(x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False)
                .sign(key, hashes.SHA256()))
        private_write(key_path, key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                                  serialization.BestAvailableEncryption(secret.hex().encode())))
        private_write(certificate, cert.public_bytes(serialization.Encoding.PEM))
    return {'certificate': str(certificate), 'key': str(key_path),
            'fingerprint': cert.fingerprint(hashes.SHA256()).hex(), 'password': secret.hex()}


def verify_signature(store, headers, method, path, body, public_key, identity):
    try:
        timestamp_text = headers['x-gateway-device-time']
        timestamp = int(timestamp_text)
        nonce = headers['x-gateway-device-nonce']
        supplied_id = headers['x-gateway-installation-id']
        if supplied_id != identity:
            raise ControlError('installation_changed', 409)
        if len(public_key) > 1024 or len(headers['x-gateway-device-signature']) > 256:
            raise ValueError()
        key = serialization.load_der_public_key(base64.b64decode(public_key, validate=True))
        if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
            raise ValueError()
        message = '\n'.join(('gptmobile-control-v1', identity, method.upper(), path, timestamp_text,
                             nonce, hashlib.sha256(body).hexdigest())).encode('utf-8')
        signature = base64.b64decode(headers['x-gateway-device-signature'], validate=True)
        key.verify(signature, message, ec.ECDSA(hashes.SHA256()))
        store.nonce(hashlib.sha256(key.public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).hexdigest(), nonce, timestamp)
    except (KeyError, ValueError, TypeError, InvalidSignature):
        raise ControlError('device_signature_required', 401) from None
