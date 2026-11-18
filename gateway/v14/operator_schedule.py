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
        db.execute("""CREATE TABLE IF NOT EXISTS publication_watch (
            id TEXT PRIMARY KEY REFERENCES schedule(id) ON DELETE CASCADE, query TEXT NOT NULL,
            token TEXT, lease_until INTEGER NOT NULL DEFAULT 0, results TEXT, seen TEXT NOT NULL DEFAULT '[]')""")
        db.execute("""CREATE TABLE IF NOT EXISTS repository_test (
            id TEXT PRIMARY KEY REFERENCES schedule(id) ON DELETE CASCADE, repository TEXT NOT NULL,
            policy_hash TEXT NOT NULL, token TEXT, expires INTEGER, head TEXT)""")
        db.execute("""CREATE TABLE IF NOT EXISTS test_receipt (
            id TEXT NOT NULL REFERENCES schedule(id) ON DELETE CASCADE, due INTEGER NOT NULL,
            receipt TEXT NOT NULL, PRIMARY KEY(id,due))""")
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
            db.execute('INSERT INTO schedule VALUES (?,?,?,?,?,NULL,NULL)',
                       (key, title.strip(), kind, int(self.now()) + delay_minutes * 60, period_hours * 3600))
            if kind == KIND:
                db.execute('INSERT INTO publication_watch(id,query) VALUES (?,?)', (key, query.strip()))
            if test:
                db.execute('INSERT INTO repository_test(id,repository,policy_hash) VALUES (?,?,?)', (key, *test))
        return key

    def rows(self):
        with closing(self.connect()) as db:
            return [dict(row) for row in db.execute('SELECT * FROM schedule ORDER BY due IS NULL, due, id')]

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
            if not db.execute('SELECT 1 FROM schedule WHERE due<=? LIMIT 1', (now,)).fetchone():
                return
            db.execute('BEGIN IMMEDIATE')
            for row in db.execute('SELECT * FROM schedule WHERE due<=? AND kind NOT IN (?,?) ORDER BY due LIMIT 8', (now, KIND, TEST_KIND)).fetchall():
                result = 'Reminder due'
                if row['kind'] == 'Check saved job storage':
                    result = inspect_jobs(jobs_path)
                period, due = row['period'], row['due']
                # Catch up once after downtime, keeping the original cadence.
                next_due = due + ((now - due) // period + 1) * period if period else None
                db.execute('UPDATE schedule SET last_due=?, result=?, due=? WHERE id=?',
                           (due, result, next_due, row['id']))
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
