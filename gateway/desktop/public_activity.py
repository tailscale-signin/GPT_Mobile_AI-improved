"""Bounded, local public observations. Replaying this journal never dispatches work."""
from contextlib import closing
from pathlib import Path
import json
import sqlite3
import time


class PublicActivity:
    def __init__(self, root, identity):
        self.path = Path(root) / 'data' / 'desktop-public-activity.sqlite3'
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.owner = (identity['gateway_id'], identity['principal_id'])

    def connect(self):
        db = sqlite3.connect(self.path, timeout=5)
        db.row_factory = sqlite3.Row
        db.execute("""CREATE TABLE IF NOT EXISTS activity(seq INTEGER PRIMARY KEY AUTOINCREMENT,
            gateway TEXT, principal TEXT, turn TEXT, conversation TEXT, job TEXT, key TEXT, kind TEXT,
            text TEXT, observed REAL, sources TEXT, UNIQUE(gateway,principal,turn,key,text))""")
        db.execute('CREATE INDEX IF NOT EXISTS activity_thread ON activity(gateway,principal,conversation,seq)')
        db.execute("CREATE TABLE IF NOT EXISTS timing(gateway TEXT,principal TEXT,job TEXT,ms REAL, PRIMARY KEY(gateway,principal,job))")
        return db

    @staticmethod
    def public(row):
        return dict(schema_version=1, gateway_id=row['gateway'], principal_id=row['principal'],
                    conversation_id=row['conversation'], turn_id=row['turn'], job_id=row['job'],
                    sequence=row['seq'], event_id=str(row['seq']), coalescing_key=row['key'], event_type=row['kind'],
                    public_text=row['text'], observed_at=row['observed'], sources=json.loads(row['sources']))

    def append(self, turn, key, kind, text, sources=(), conversation=None, job=None, observed=None):
        # Callers pass a projection of public facts only, never model reasoning or raw tool arguments.
        with closing(self.connect()) as db, db:
            db.execute('BEGIN IMMEDIATE')
            if conversation:
                db.execute('UPDATE activity SET conversation=?,job=? WHERE gateway=? AND principal=? AND turn=?',
                           (conversation, job, *self.owner, turn))
            fields = (*self.owner, turn, key, text[:600])
            db.execute('INSERT OR IGNORE INTO activity(gateway,principal,turn,key,text,kind,conversation,job,observed,sources)'
                       ' VALUES(?,?,?,?,?,?,?,?,?,?)', (*fields, kind, conversation, job, time.time() if observed is None else observed, json.dumps(list(sources)[:20])))
            row = db.execute('SELECT * FROM activity WHERE gateway=? AND principal=? AND turn=? AND key=? AND text=?', fields).fetchone()
            db.execute('DELETE FROM activity WHERE seq <= (SELECT seq FROM activity ORDER BY seq DESC LIMIT 1 OFFSET 4096)')
            return self.public(row)

    def timing(self, job, milliseconds):
        with closing(self.connect()) as db, db:
            db.execute('INSERT OR IGNORE INTO timing VALUES(?,?,?,?)', (*self.owner, job, milliseconds))
            db.execute('DELETE FROM timing WHERE rowid <= (SELECT rowid FROM timing ORDER BY rowid DESC LIMIT 1 OFFSET 4096)')

    def attach_timings(self, items):
        ids = [item['job_id'] for item in items]
        if ids:
            with closing(self.connect()) as db:
                query = 'SELECT job,ms FROM timing WHERE gateway=? AND principal=? AND job IN (' + ','.join('?' for _ in ids) + ')'
                measured = dict(db.execute(query, (*self.owner, *ids)))
                for item in items:
                    item['first_text_ms'] = measured.get(item['job_id'])

    def engine_cursor(self, job):
        with closing(self.connect()) as db:
            row = db.execute("SELECT key FROM activity WHERE gateway=? AND principal=? AND job=? AND key LIKE 'revision-%' ORDER BY seq DESC LIMIT 1",
                             (*self.owner, job)).fetchone()
            return int(row['key'].split('-')[-1]) if row else -1

    def replay(self, conversation, after=0):
        if type(after) is not int or after < 0:
            raise ValueError('Invalid activity cursor.')
        with closing(self.connect()) as db:
            rows = db.execute('SELECT * FROM activity WHERE gateway=? AND principal=? AND conversation=? AND seq>? ORDER BY seq LIMIT 201',
                              (*self.owner, conversation, after)).fetchall()
            first = db.execute('SELECT min(seq) FROM activity').fetchone()[0]
            return {'items': [self.public(row) for row in rows[:200]], 'has_more': len(rows) > 200,
                    'gap': bool(first and first > 1 and after < first - 1)}
