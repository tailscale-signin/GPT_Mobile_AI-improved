"""Password-encrypted offline backup. Restore into a NEW directory, then start the gateway."""
import argparse
import base64
import getpass
import json
import os
from pathlib import Path
import sqlite3
import tempfile

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt
from v14.engineering_crypto import EngineeringCipher

AAD = b'github-engineering-backup-v1'
LIMIT = 512 * 1024 * 1024


def derive(password, salt):
    if not isinstance(password, str) or len(password) < 12:
        raise ValueError('Use a backup password of at least 12 characters')
    return Scrypt(salt=salt, length=32, n=32768, r=8, p=1).derive(password.encode())


def backup(database, destination, password):
    database, destination = Path(database).absolute(), Path(destination)
    if not database.is_file() or database.is_symlink():
        raise ValueError('Select an existing gateway database')
    with sqlite3.connect(database.as_uri() + '?mode=ro', uri=True) as source, sqlite3.connect(':memory:') as snapshot:
        source.backup(snapshot)
        if snapshot.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
            raise ValueError('Database integrity check failed')
        binary = snapshot.serialize()
        if len(binary) > LIMIT:
            raise ValueError('Backup exceeds the in-memory bound')
        key = EngineeringCipher.key(source)
        payload = json.dumps({'schema_version': 1, 'database': base64.b64encode(binary).decode(),
                              'engineering_key': base64.b64encode(key).decode(), 'encryption_version': 1}).encode()
    salt, nonce = os.urandom(16), os.urandom(12)
    encrypted = AESGCM(derive(password, salt)).encrypt(nonce, payload, AAD)
    # O_EXCL avoids silently replacing a known-good backup. Password and key never enter arguments or logs.
    descriptor = os.open(destination, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(AAD + salt + nonce + encrypted)
            stream.flush()
            os.fsync(stream.fileno())
    except BaseException:
        destination.unlink(missing_ok=True)
        raise


def restore(archive, directory, password):
    archive, directory = Path(archive), Path(directory)
    if directory.exists() or directory.is_symlink():
        raise ValueError('Restore requires a new destination directory and a stopped gateway')
    if archive.stat().st_size > LIMIT * 2:
        raise ValueError('Backup exceeds the restore bound')
    raw = archive.read_bytes()
    if not raw.startswith(AAD) or len(raw) < len(AAD) + 44:
        raise ValueError('Unsupported backup format')
    offset = len(AAD)
    salt, nonce = raw[offset:offset + 16], raw[offset + 16:offset + 28]
    payload = json.loads(AESGCM(derive(password, salt)).decrypt(nonce, raw[offset + 28:], AAD))
    if payload.get('schema_version') != 1 or payload.get('encryption_version') != 1:
        raise ValueError('Unsupported engineering schema or encryption metadata')
    binary, key = base64.b64decode(payload['database'], validate=True), base64.b64decode(payload['engineering_key'], validate=True)
    if len(binary) > LIMIT or len(key) != 32:
        raise ValueError('Invalid backup contents')
    with sqlite3.connect(':memory:') as test:
        test.deserialize(binary)
        if test.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
            raise ValueError('Restored database integrity check failed')
    # Complete DB/key pairing is exposed only after both private files have been written.
    with tempfile.TemporaryDirectory(prefix='.engineering-restore-', dir=directory.parent) as staging:
        for name, value in (('jobs.db', binary), ('jobs.engineering.key', key)):
            descriptor = os.open(Path(staging) / name, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            with os.fdopen(descriptor, 'wb') as stream:
                stream.write(value)
                stream.flush()
                os.fsync(stream.fileno())
        os.rename(staging, directory)
    return directory / 'jobs.db'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('backup', 'restore'))
    parser.add_argument('source', type=Path)
    parser.add_argument('destination', type=Path)
    args = parser.parse_args()
    password = getpass.getpass('Backup password: ')
    if args.action == 'backup':
        if password != getpass.getpass('Confirm backup password: '):
            raise ValueError('Passwords did not match')
        backup(args.source.resolve(), args.destination, password)
    else:
        restore(args.source, args.destination, password)


if __name__ == '__main__':
    main()
