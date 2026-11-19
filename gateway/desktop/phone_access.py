"""Local invitation authority and per-installation client certificate grants."""
import base64
from contextlib import contextmanager, closing
from datetime import datetime, timedelta, timezone
import hashlib
import ipaddress
import os
from pathlib import Path
import secrets
import sqlite3
import time
from urllib.parse import urlencode

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID

from desktop.configuration import atomic_json
from v14.state_lock import StateDirectoryLock

DIRECTORY = 'phone-control'


def directory(root):
    return Path(root) / 'data' / DIRECTORY


@contextmanager
def database(root):
    folder = directory(root)
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    with closing(sqlite3.connect(folder / 'devices.sqlite3', timeout=3)) as db:
        db.executescript('''
            CREATE TABLE IF NOT EXISTS invitations(hash TEXT PRIMARY KEY, role TEXT NOT NULL, expires INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS grants(id TEXT PRIMARY KEY, cert_hash TEXT UNIQUE NOT NULL,
                label TEXT NOT NULL, role TEXT NOT NULL, revoked INTEGER NOT NULL DEFAULT 0, data_cert TEXT UNIQUE NOT NULL);
        ''')
        with db:
            yield db


def digest(data):
    return hashlib.sha256(data).hexdigest()


def _cert(name, issuer, public, signer, ca=False, usages=(), host=None):
    now = datetime.now(timezone.utc)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, name)])
    builder = (x509.CertificateBuilder().subject_name(subject).issuer_name(issuer or subject)
               .public_key(public).serial_number(x509.random_serial_number())
               .not_valid_before(now - timedelta(minutes=5)).not_valid_after(now + timedelta(days=3650 if ca else 730))
               .add_extension(x509.BasicConstraints(ca=ca, path_length=0 if ca else None), critical=True)
               .add_extension(x509.KeyUsage(digital_signature=True, content_commitment=False, key_encipherment=False,
                                            data_encipherment=False, key_agreement=False, key_cert_sign=ca, crl_sign=ca,
                                            encipher_only=None, decipher_only=None), critical=True)
               .add_extension(x509.SubjectKeyIdentifier.from_public_key(public), critical=False)
               .add_extension(x509.AuthorityKeyIdentifier.from_issuer_public_key(signer.public_key()), critical=False))
    if usages:
        builder = builder.add_extension(x509.ExtendedKeyUsage(list(usages)), critical=False)
    if host:
        builder = builder.add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address(host))]), critical=False)
    return builder.sign(signer, hashes.SHA256())


def _write_secret(path, data):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), 'wb') as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())


def configure(root, host, port):
    """A concrete interface and a separate port are deliberate Windows-owner choices."""
    import json
    addr = ipaddress.ip_address(host)
    private_ranges = ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16')
    if (not any(addr in ipaddress.ip_network(net) for net in private_ranges) or
            type(port) is not int or not 1024 <= port <= 65535):
        raise ValueError('Choose an assigned private IPv4 interface and a port from 1024 to 65535.')
    folder = directory(root)
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    lock = StateDirectoryLock(folder / 'pki-lock' / 'owner')
    lock.acquire()
    try:
        ca_path = folder / 'authority.pem'
        if not ca_path.exists():
            signer = ec.generate_private_key(ec.SECP256R1())
            ca = _cert('GPT Mobile device authority', None, signer.public_key(), signer, ca=True)
            key = signer.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
            _write_secret(ca_path, key + ca.public_bytes(serialization.Encoding.PEM))
        ca, signer = _authority(folder)
        leaf_path = folder / ('server-' + str(addr) + '.pem')
        if not leaf_path.exists():
            key = ec.generate_private_key(ec.SECP256R1())
            cert = _cert('GPT Mobile Gateway', ca.subject, key.public_key(), signer,
                         usages=[ExtendedKeyUsageOID.SERVER_AUTH], host=addr)
            _write_secret(leaf_path, key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                          serialization.NoEncryption()) + cert.public_bytes(serialization.Encoding.PEM))
        cert = x509.load_pem_x509_certificate(leaf_path.read_bytes())
        cfg = dict(host=str(addr), port=port, pin=cert.fingerprint(hashes.SHA256()).hex())
        target = folder / 'listener.json'
        if not target.exists() or json.loads(target.read_text()) != cfg:
            with database(root) as db:
                db.execute('DELETE FROM invitations')
            atomic_json(target, cfg)
        return cfg
    finally:
        lock.release()


def _authority(folder):
    pem = (folder / 'authority.pem').read_bytes()
    return x509.load_pem_x509_certificate(pem), serialization.load_pem_private_key(pem, password=None)


def invite(root, role='owner'):
    import json
    if role not in {'owner', 'viewer'}:
        raise ValueError('Select an owner or viewer role.')
    cfg = json.loads((directory(root) / 'listener.json').read_text())
    code = secrets.token_urlsafe(32)
    with database(root) as db:
        db.execute('DELETE FROM invitations WHERE expires<=?', (time.time(),))
        if db.execute('SELECT COUNT(*) FROM invitations').fetchone()[0] >= 16:
            raise ValueError('Existing invitations expire in five minutes.')
        db.execute('INSERT INTO invitations VALUES (?,?,?)', (digest(code.encode()), role, int(time.time()) + 300))
    return 'gptmobile://gateway-owner?' + urlencode(dict(host=cfg['host'], port=cfg['port'], pin=cfg['pin'], code=code))


def redeem(root, body):
    if not isinstance(body, dict) or set(body) != {'code', 'public_key', 'name'}:
        raise ValueError('Invalid pairing request.')
    code, name = body['code'], body['name']
    if not isinstance(code, str) or len(code) != 43 or not isinstance(name, str) or not 1 <= len(name) <= 80 or not name.isprintable():
        raise ValueError('Invalid pairing fields.')
    key = serialization.load_der_public_key(base64.b64decode(body['public_key'], validate=True))
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(key.curve, ec.SECP256R1):
        raise ValueError('A P-256 device key is required.')
    with database(root) as db:
        db.execute('BEGIN IMMEDIATE')
        ident = digest(code.encode())
        grant = db.execute('SELECT role FROM invitations WHERE hash=? AND expires>?', (ident, time.time())).fetchone()
        if not grant:
            raise PermissionError('Expired or consumed invitation.')
        if db.execute('SELECT COUNT(*) FROM grants WHERE revoked IN (0,2)').fetchone()[0] >= 32:
            raise ValueError('Revoke an unused device before pairing another.')
        ca, signer = _authority(directory(root))
        device = 'phone:' + secrets.token_hex(16)
        cert = _cert(device, ca.subject, key, signer, usages=[ExtendedKeyUsageOID.CLIENT_AUTH])
        data_cert = _cert(device + '-data', ca.subject, key, signer, usages=[ExtendedKeyUsageOID.CLIENT_AUTH])
        db.execute('INSERT INTO grants VALUES (?,?,?,?,2,?)', (device, cert.fingerprint(hashes.SHA256()).hex(), name, grant[0], data_cert.fingerprint(hashes.SHA256()).hex()))
        db.execute('DELETE FROM invitations WHERE hash=?', (ident,))
        return dict(device=device, role=grant[0], certificate=base64.b64encode(cert.public_bytes(serialization.Encoding.DER)).decode(),
                    data_certificate=base64.b64encode(data_cert.public_bytes(serialization.Encoding.DER)).decode())


def authorized(root, device):
    if not root or not device.startswith('phone:') or not (directory(root) / 'devices.sqlite3').exists():
        return False
    with database(root) as db:
        return db.execute("SELECT 1 FROM grants WHERE id=? AND revoked=0 AND role='owner'", (device,)).fetchone() is not None


def approve(root, device):
    """A Windows operator verifies the code displayed on the intended phone."""
    with database(root) as db:
        if db.execute('UPDATE grants SET revoked=0 WHERE id=? AND revoked=2', (device,)).rowcount != 1:
            raise ValueError('This device is no longer waiting for approval.')


def revoke(root, device):
    with database(root) as db:
        db.execute('UPDATE grants SET revoked=1 WHERE id=?', (device,))
