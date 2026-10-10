"""Versioned journal preflight and consistent local SQLite snapshots."""
import sqlite3
import time
import uuid
from pathlib import Path

SCHEMA_VERSION = 1


def schema_version(conn):
    exists = conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='gateway_schema'").fetchone()
    if not exists:
        return 0
    row = conn.execute("SELECT version FROM gateway_schema WHERE id=1").fetchone()
    if row is None or not isinstance(row[0], int) or row[0] != SCHEMA_VERSION:
        raise ValueError("Unsupported gateway journal version; use its matching release")
    return row[0]


def backup_database(source, destination, timeout=30):
    """Publish a verified snapshot, refusing to overwrite any existing file."""
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    # Exclusive creation is also safe against accidentally targeting the live DB.
    with destination.open('xb'):
        pass
    deadline = time.monotonic() + timeout
    def progress(status, remaining, total):
        if time.monotonic() >= deadline:
            raise TimeoutError("Journal backup exceeded its deadline")
    try:
        target = sqlite3.connect(destination)
        try:
            source.backup(target, pages=256, progress=progress, sleep=0.05)
            if target.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
                raise ValueError('Journal backup integrity check failed')
        finally:
            target.close()
        return destination
    except BaseException:
        destination.unlink(missing_ok=True)
        raise


def preflight(conn, database_path):
    if conn.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
        raise ValueError('Journal integrity check failed; restore a verified snapshot')
    version = schema_version(conn)
    existing = conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='gateway_jobs'").fetchone()
    if not version and existing:
        database_path = Path(database_path)
        destination = database_path.parent / 'backups' / (database_path.name + '.pre-v14.1-' + uuid.uuid4().hex + '.sqlite3')
        return backup_database(conn, destination)
    return None


def record_schema(conn):
    conn.execute('CREATE TABLE IF NOT EXISTS gateway_schema (id INTEGER PRIMARY KEY CHECK(id=1), version INTEGER NOT NULL)')
    conn.execute('INSERT OR IGNORE INTO gateway_schema(id,version) VALUES(1,?)', (SCHEMA_VERSION,))
