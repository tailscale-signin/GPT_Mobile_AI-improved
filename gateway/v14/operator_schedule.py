"""Local operator schedules. Due work and its receipt commit in a single transaction."""
import asyncio
from contextlib import asynccontextmanager, closing
from pathlib import Path
import json
from v14.scheduled_publications import KIND, run_watch
from v14.scheduled_validation import KIND as TEST_KIND, admission, run_tests
import sqlite3
import threading
import time
import uuid


class OperatorSchedule:
    def __init__(self, directory, now=time.time):
        self.directory = Path(directory)
        self.path = self.directory / 'operator-schedules.sqlite3'
        self.now = now

    def connect(self):
        self.directory.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(self.path, timeout=2)
        db.row_factory = sqlite3.Row
        db.execute("""CREATE TABLE IF NOT EXISTS schedule (
            id TEXT PRIMARY KEY, title TEXT NOT NULL, kind TEXT NOT NULL, due INTEGER,
            period INTEGER NOT NULL, last_due INTEGER, result TEXT)""")
        db.execute('PRAGMA foreign_keys=ON')
        if 'revision' not in {row[1] for row in db.execute('PRAGMA table_info(schedule)')}:
            # A supervisor and desktop can open a pre-upgrade store simultaneously.
            with db:
                db.execute('BEGIN IMMEDIATE')
                columns = {row[1] for row in db.execute('PRAGMA table_info(schedule)')}
                for name, default in [('enabled', 1), ('revision', 0)]:
                    if name not in columns:
                        db.execute(f'ALTER TABLE schedule ADD COLUMN {name} INTEGER NOT NULL DEFAULT {default}')
        db.execute("""CREATE TABLE IF NOT EXISTS publication_watch (
            id TEXT PRIMARY KEY REFERENCES schedule(id) ON DELETE CASCADE, query TEXT NOT NULL,
            token TEXT, lease_until INTEGER NOT NULL DEFAULT 0, results TEXT, seen TEXT NOT NULL DEFAULT '[]')""")
        db.execute("""CREATE TABLE IF NOT EXISTS repository_test (
            id TEXT PRIMARY KEY REFERENCES schedule(id) ON DELETE CASCADE, repository TEXT NOT NULL,
            policy_hash TEXT NOT NULL, token TEXT, expires INTEGER, head TEXT)""")
        db.execute("""CREATE TABLE IF NOT EXISTS test_receipt (
            id TEXT NOT NULL REFERENCES schedule(id) ON DELETE CASCADE, due INTEGER NOT NULL,
            receipt TEXT NOT NULL, PRIMARY KEY(id,due))""")
        db.execute("""CREATE TABLE IF NOT EXISTS schedule_notice (
            id TEXT NOT NULL REFERENCES schedule(id) ON DELETE CASCADE, due INTEGER NOT NULL,
            message TEXT NOT NULL, PRIMARY KEY(id,due))""")
        return db

    def add(self, title, kind, delay_minutes, period_hours, query=None):
        if not isinstance(title, str) or not 1 <= len(title.strip()) <= 160 or any(ord(c) < 32 for c in title):
            raise ValueError('Enter a title of 1–160 characters.')
        if kind not in ('Reminder', 'Check saved job storage', KIND, TEST_KIND) or type(delay_minutes) is not int or not 1 <= delay_minutes <= 525600:
            raise ValueError('Choose a supported task and a delay of 1–525600 minutes.')
        if type(period_hours) is not int or not 0 <= period_hours <= 8784:
            raise ValueError('Use 0 for one occurrence or a repeat interval in hours.')
        if kind == KIND and (not isinstance(query, str) or not 2 <= len(query.strip()) <= 200 or any(ord(c) < 32 for c in query)):
            raise ValueError('Enter 2–200 public search characters to send to Crossref.')
        test = admission(self.directory.parent, query) if kind == TEST_KIND else None
        key = uuid.uuid4().hex
        with closing(self.connect()) as db, db:
            db.execute('BEGIN IMMEDIATE')
            if db.execute('SELECT COUNT(*) FROM schedule').fetchone()[0] >= 128:
                raise ValueError('Remove an old schedule before adding another.')
            db.execute('INSERT INTO schedule(id,title,kind,due,period) VALUES (?,?,?,?,?)',
                       (key, title.strip(), kind, int(self.now()) + delay_minutes * 60, period_hours * 3600))
            if kind == KIND:
                db.execute('INSERT INTO publication_watch(id,query) VALUES (?,?)', (key, query.strip()))
            if test:
                db.execute('INSERT INTO repository_test(id,repository,policy_hash) VALUES (?,?,?)', (key, *test))
        return key

    def rows(self):
        with closing(self.connect()) as db:
            return [dict(row) for row in db.execute('''SELECT s.*,
                CASE WHEN w.token IS NOT NULL AND w.lease_until > :now THEN 'Running'
                     WHEN t.token IS NOT NULL AND t.expires > :now THEN 'Running'
                     WHEN t.token IS NOT NULL THEN 'Unverified'
                     WHEN s.enabled=0 THEN 'Disabled'
                     WHEN s.result LIKE 'Blocked%' THEN 'Blocked'
                     WHEN s.result LIKE 'Failed%' THEN 'Failed'
                     WHEN s.result LIKE 'Interrupted%' OR s.result LIKE 'Unverified%' THEN 'Unverified'
                     WHEN s.due IS NOT NULL THEN 'Waiting'
                     WHEN s.last_due IS NOT NULL THEN 'Completed'
                     ELSE 'Unverified' END AS state,
                (SELECT COUNT(*) FROM schedule_notice n WHERE n.id=s.id) AS unread
                FROM schedule s LEFT JOIN publication_watch w USING(id) LEFT JOIN repository_test t USING(id)
                ORDER BY s.due IS NULL, s.due, s.id''', {'now': int(self.now())})]

    def change(self, key, revision, *, enabled=None, title=None, period_hours=None):
        if type(revision) is not int or revision < 0:
            raise ValueError('Reload the schedule before editing.')
        if enabled is not None and type(enabled) is not bool:
            raise ValueError('Choose an enabled state.')
        if title is not None and (not isinstance(title, str) or not 1 <= len(title.strip()) <= 160 or any(ord(c) < 32 for c in title)):
            raise ValueError('Enter a title of 1–160 characters.')
        if period_hours is not None and (type(period_hours) is not int or not 0 <= period_hours <= 8784):
            raise ValueError('Enter a repeat interval of 0–8784 hours.')
        with closing(self.connect()) as db, db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT * FROM schedule WHERE id=?', (key,)).fetchone()
            if not row or row['revision'] != revision:
                raise ValueError('Schedule changed. Reload the current revision before applying edits.')
            active = db.execute('SELECT token FROM publication_watch WHERE id=? UNION ALL SELECT token FROM repository_test WHERE id=?', (key, key)).fetchall()
            if period_hours is not None and any(item[0] for item in active):
                raise ValueError('Change the recurrence after the current occurrence finishes.')
            due, now = row['due'], int(self.now())
            period = row['period'] if period_hours is None else period_hours * 3600
            if enabled and not row['enabled'] and due is not None and due <= now and not any(item[0] for item in active):
                due = due + ((now - due) // period + 1) * period if period else None
            db.execute('UPDATE schedule SET enabled=?,title=?,period=?,due=?,revision=revision+1 WHERE id=?',
                       (row['enabled'] if enabled is None else int(enabled), title.strip() if title else row['title'], period, due, key))
            return dict(db.execute('SELECT * FROM schedule WHERE id=?', (key,)).fetchone())

    @staticmethod
    def notify(db, key, due, message):
        # Store the alert in the occurrence transaction, including when the desktop is closed.
        db.execute('INSERT OR IGNORE INTO schedule_notice VALUES (?,?,?)', (key, due, message))
        db.execute('DELETE FROM schedule_notice WHERE id=? AND due NOT IN '
                   '(SELECT due FROM schedule_notice WHERE id=? ORDER BY due DESC LIMIT 32)', (key, key))

    def notices(self, key):
        with closing(self.connect()) as db:
            return [dict(row) for row in db.execute(
                'SELECT due,message FROM schedule_notice WHERE id=? ORDER BY due DESC', (key,))]

    def acknowledge(self, key, through_due):
        with closing(self.connect()) as db, db:
            db.execute('DELETE FROM schedule_notice WHERE id=? AND due<=?', (key, through_due))

    def publications(self, key):
        with closing(self.connect()) as db:
            row = db.execute('SELECT results FROM publication_watch WHERE id=?', (key,)).fetchone()
            return json.loads(row['results']) if row and row['results'] else []

    def test_history(self, key):
        with closing(self.connect()) as db:
            return [(row['due'], json.loads(row['receipt'])) for row in db.execute(
                'SELECT due,receipt FROM test_receipt WHERE id=? ORDER BY due DESC LIMIT 10', (key,))]

    def remove(self, key):
        with closing(self.connect()) as db, db:
            db.execute('DELETE FROM schedule WHERE id=?', (key,))

    def tick(self, jobs_path):
        if not self.path.exists():
            return
        now = int(self.now())
        with closing(self.connect()) as db, db:
            if not db.execute('SELECT 1 FROM schedule WHERE enabled=1 AND due<=? LIMIT 1', (now,)).fetchone():
                return
            db.execute('BEGIN IMMEDIATE')
            for row in db.execute('SELECT * FROM schedule WHERE enabled=1 AND due<=? AND kind NOT IN (?,?) ORDER BY due LIMIT 8', (now, KIND, TEST_KIND)).fetchall():
                result = 'Reminder due'
                if row['kind'] == 'Check saved job storage':
                    result = inspect_jobs(jobs_path)
                period, due = row['period'], row['due']
                # Catch up once after downtime, keeping the original cadence.
                next_due = due + ((now - due) // period + 1) * period if period else None
                db.execute('UPDATE schedule SET last_due=?, result=?, due=? WHERE id=?',
                           (due, result, next_due, row['id']))
                if row['kind'] == 'Reminder' or (result.startswith('Blocked') and result != row['result']):
                    self.notify(db, row['id'], due, result)
        run_watch(self, now)


def inspect_jobs(path):
    try:
        with closing(sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True, timeout=1)) as db:
            # A single bounded schema read never starts an agent or exposes a conversation.
            db.execute('SELECT job_id FROM gateway_jobs LIMIT 1').fetchone()
            return 'Passed · saved job table readable'
    except sqlite3.Error:
        return 'Blocked · saved job table unavailable'


def schedule_lifespan(existing, root, jobs_path):
    @asynccontextmanager
    async def lifespan(app):
        async with existing(app):
            if not root:
                yield
                return
            store = OperatorSchedule(Path(root) / 'data')
            stop = asyncio.Event()

            shutdown = threading.Event()

            async def worker(callback, *args):
                while not stop.is_set():
                    try:
                        await asyncio.to_thread(callback, *args)
                    except (OSError, sqlite3.Error):
                        # An uncommitted occurrence remains due on the next tick.
                        pass
                    try:
                        await asyncio.wait_for(stop.wait(), 15)
                    except asyncio.TimeoutError:
                        pass

            tasks = [asyncio.create_task(worker(store.tick, jobs_path)), asyncio.create_task(worker(run_tests, store, shutdown))]
            try:
                yield
            finally:
                stop.set()
                shutdown.set()
                await asyncio.gather(*tasks)
    return lifespan
