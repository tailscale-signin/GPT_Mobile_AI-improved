"""Versioned authenticated encryption for private engineering records."""
import base64
import json
import os
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import AESGCM


class EngineeringCipher:
    @staticmethod
    def key_path(db):
        path = next((row[2] for row in db.execute('PRAGMA database_list') if row[1] == 'main'), '')
        if not path:
            raise ValueError('Engineering encryption needs a persistent database')
        return Path(path).with_suffix('.engineering.key')

    @classmethod
    def key(cls, db, create=False):
        path = cls.key_path(db)
        if path.is_symlink():
            raise ValueError('Engineering key must not be a symbolic link')
        if not path.exists():
            # Never replace a lost key when ciphertext already exists.
            for table in ('engineering_memory', 'engineering_evidence'):
                if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone() and db.execute('SELECT COUNT(*) FROM ' + table).fetchone()[0]:
                    raise ValueError('Engineering key is missing; restore the protected key before reading or appending')
            if not create:
                raise ValueError('Engineering key is unavailable')
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'wb') as stream:
                stream.write(AESGCM.generate_key(bit_length=256))
                stream.flush()
                os.fsync(stream.fileno())
        key = path.read_bytes()
        if len(key) != 32:
            raise ValueError('Invalid engineering encryption key')
        return key

    @classmethod
    def encrypt(cls, db, identity, text):
        nonce = os.urandom(12)
        ciphertext = AESGCM(cls.key(db, True)).encrypt(nonce, text.encode(), ('engineering-v1:' + identity).encode())
        return json.dumps({'encryption_version': 1, 'algorithm': 'AES-256-GCM', 'nonce': base64.b64encode(nonce).decode(), 'ciphertext': base64.b64encode(ciphertext).decode()}, separators=(',', ':'))

    @classmethod
    def decrypt(cls, db, identity, envelope):
        value = json.loads(envelope)
        if value.get('encryption_version') != 1 or value.get('algorithm') != 'AES-256-GCM':
            raise ValueError('Unsupported engineering encryption metadata')
        return AESGCM(cls.key(db)).decrypt(base64.b64decode(value['nonce'], validate=True), base64.b64decode(value['ciphertext'], validate=True), ('engineering-v1:' + identity).encode()).decode()
