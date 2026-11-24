"""Bounded, local public observations. Replaying this journal never dispatches work."""
from contextlib import closing
from pathlib import Path
import hashlib
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
        db.execute("""CREATE TABLE IF NOT EXISTS activity_fact(seq INTEGER PRIMARY KEY, identity TEXT NOT NULL,
                    body TEXT NOT NULL, observation_version INTEGER)""")
        db.execute('CREATE INDEX IF NOT EXISTS fact_observations ON activity_fact(identity,seq)')
        db.execute('CREATE INDEX IF NOT EXISTS activity_thread ON activity(gateway,principal,conversation,seq)')
        db.execute("CREATE TABLE IF NOT EXISTS timing(gateway TEXT,principal TEXT,job TEXT,ms REAL, PRIMARY KEY(gateway,principal,job))")
        db.execute("""CREATE TABLE IF NOT EXISTS evicted(gateway TEXT, principal TEXT, conversation TEXT, through INTEGER,
                    PRIMARY KEY(gateway,principal,conversation))""")
        db.execute("""CREATE TABLE IF NOT EXISTS drafts(gateway TEXT, principal TEXT, conversation TEXT, text TEXT, model TEXT,
                    PRIMARY KEY(gateway,principal,conversation))""")
        return db

    @staticmethod
    def public(row):
        result = dict(schema_version=1, gateway_id=row['gateway'], principal_id=row['principal'],
                    conversation_id=row['conversation'], turn_id=row['turn'], job_id=row['job'],
                    sequence=row['seq'], event_id=str(row['seq']), coalescing_key=row['key'], event_type=row['kind'],
                    public_text=row['text'], observed_at=row['observed'], sources=json.loads(row['sources']))
        if row['fact_body']:
            result['fact'] = json.loads(row['fact_body'])
            result['coalescing_key'] = row['fact_identity']
        elif row['kind'] == 'finding' and row['key'] == 'search' and result['sources']:
            # Old search observations retain their actual source list and can be upgraded on replay.
            digest = hashlib.sha256(json.dumps(result['sources'], sort_keys=True).encode()).hexdigest()
            result['fact'] = dict(scope_id=row['turn'], criterion_id='search', subject_id='retrieved-sources',
                                  revision_id=digest, value=str(len(result['sources'])), evidence_id=digest)
        return result

    _select = ('SELECT a.*,f.body AS fact_body,f.identity AS fact_identity,f.observation_version '
               'FROM activity a LEFT JOIN activity_fact f ON a.seq=f.seq ')

    def append(self, turn, key, kind, text, sources=(), conversation=None, job=None, observed=None,
               *, fact=None, observation_version=None):
        # Callers pass a projection of public facts only, never model reasoning or raw tool arguments.
        with closing(self.connect()) as db, db:
            db.execute('BEGIN IMMEDIATE')
            if conversation:
                db.execute('UPDATE activity SET conversation=?,job=coalesce(job,?) WHERE gateway=? AND principal=? AND turn=? '
                           'AND (conversation IS NULL OR (job IS NULL AND ? IS NOT NULL))',
                           (conversation, job, *self.owner, turn, job))
            identity = None
            if fact is not None:
                required = {'scope_id', 'criterion_id', 'subject_id', 'revision_id', 'value', 'evidence_id'}
                if (kind != 'finding' or not isinstance(fact, dict) or set(fact) != required or fact['scope_id'] != turn
                        or any(not isinstance(v, str) or not v.strip() or len(v) > 256 for v in fact.values())):
                    raise ValueError('A public finding requires a scoped observation.')
                if observation_version is not None and (type(observation_version) is not int or observation_version < 0):
                    raise ValueError('Invalid public observation revision.')
                subject = [*self.owner, turn, fact['criterion_id'], fact['subject_id']]
                identity = hashlib.sha256(json.dumps(subject).encode()).hexdigest()
                previous = db.execute(self._select + 'WHERE f.identity=? ORDER BY a.seq DESC LIMIT 1', (identity,)).fetchone()
                if previous:
                    prior, version = json.loads(previous['fact_body']), previous['observation_version']
                    if observation_version is not None and version is not None and observation_version < version:
                        return self.public(previous)
                    if (prior['revision_id'], prior['value']) == (fact['revision_id'], fact['value']):
                        if observation_version is not None and (version is None or observation_version > version):
                            db.execute('UPDATE activity_fact SET observation_version=? WHERE seq=?', (observation_version, previous['seq']))
                        return self.public(previous)
                # A later legitimate transition back to the same value is a new event.
                key = 'fact-' + identity + '-' + str(previous['seq'] if previous else 0)
            fields = (*self.owner, turn, key, text[:600])
            db.execute('INSERT OR IGNORE INTO activity(gateway,principal,turn,key,text,kind,conversation,job,observed,sources)'
                       ' VALUES(?,?,?,?,?,?,?,?,?,?)', (*fields, kind, conversation, job, time.time() if observed is None else observed, json.dumps(list(sources)[:20])))
            seq = db.execute('SELECT seq FROM activity WHERE gateway=? AND principal=? AND turn=? AND key=? AND text=?', fields).fetchone()[0]
            if fact is not None:
                db.execute('INSERT INTO activity_fact(seq,identity,body,observation_version) VALUES(?,?,?,?)',
                           (seq, identity, json.dumps(fact), observation_version))
            row = db.execute(self._select + 'WHERE a.seq=?', (seq,)).fetchone()
            cutoff = db.execute('SELECT seq FROM activity ORDER BY seq DESC LIMIT 1 OFFSET 4096').fetchone()
            if cutoff:
                db.execute('INSERT INTO evicted SELECT gateway,principal,conversation,max(seq) FROM activity '
                           'WHERE seq<=? AND conversation IS NOT NULL GROUP BY gateway,principal,conversation '
                           'ON CONFLICT(gateway,principal,conversation) DO UPDATE SET through=max(through,excluded.through)', (cutoff[0],))
                db.execute('DELETE FROM activity_fact WHERE seq<=?', (cutoff[0],))
                db.execute('DELETE FROM activity WHERE seq<=?', (cutoff[0],))
            return self.public(row)

    def draft(self, conversation):
        with closing(self.connect()) as db:
            row = db.execute('SELECT text AS draft,model FROM drafts WHERE gateway=? AND principal=? AND conversation=?',
                             (*self.owner, conversation)).fetchone()
            return dict(row) if row else None

    def save_draft(self, conversation, text, model):
        with closing(self.connect()) as db, db:
            if text:
                db.execute('INSERT INTO drafts VALUES(?,?,?,?,?) ON CONFLICT(gateway,principal,conversation) '
                           'DO UPDATE SET text=excluded.text,model=excluded.model', (*self.owner, conversation, text, model))
            else:
                db.execute('DELETE FROM drafts WHERE gateway=? AND principal=? AND conversation=?', (*self.owner, conversation))

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

    def engine_position(self, job):
        with closing(self.connect()) as db:
            row = db.execute("SELECT key,kind FROM activity WHERE gateway=? AND principal=? AND job=? AND key LIKE 'revision-%' ORDER BY seq DESC LIMIT 1",
                             (*self.owner, job)).fetchone()
            return (int(row['key'].split('-')[-1]), row['kind']) if row else (-1, None)

    def replay(self, conversation, after=0):
        if type(after) is not int or after < 0:
            raise ValueError('Invalid activity cursor.')
        with closing(self.connect()) as db:
            rows = db.execute(self._select + 'WHERE a.gateway=? AND a.principal=? AND a.conversation=? AND a.seq>? ORDER BY a.seq LIMIT 201',
                              (*self.owner, conversation, after)).fetchall()
            expired = db.execute('SELECT through FROM evicted WHERE gateway=? AND principal=? AND conversation=?',
                                 (*self.owner, conversation)).fetchone()
            return {'items': [self.public(row) for row in rows[:200]], 'has_more': len(rows) > 200,
                    'gap': bool(expired and after < expired[0])}
