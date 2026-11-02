"""Private installation usage: provider measurements only, never prompt contents."""
import datetime as dt
import math
from pathlib import Path
import sqlite3
import threading
import time


class UsageStore:
    def __init__(self, path, now=time.time):
        self.path, self.now = Path(path), now
        self.lock = threading.RLock()

    def connect(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        db = sqlite3.connect(self.path, timeout=2)
        db.execute('CREATE TABLE IF NOT EXISTS daily_usage ('
                   'day TEXT PRIMARY KEY, output INTEGER NOT NULL, input INTEGER NOT NULL, '
                   'measured INTEGER NOT NULL, missing INTEGER NOT NULL, timed_tokens INTEGER NOT NULL, '
                   'generation_ms REAL NOT NULL)')
        return db

    def record(self, result):
        usage = result.get('usage') or {} if isinstance(result, dict) else {}
        timings = result.get('timings') or {} if isinstance(result, dict) else {}
        usage = usage if isinstance(usage, dict) else {}
        timings = timings if isinstance(timings, dict) else {}
        valid = lambda value: type(value) is int and 0 <= value <= 2**53 - 1
        output = usage.get('completion_tokens')
        if not valid(output):
            output = timings.get('predicted_n')
        measured = valid(output)
        input_tokens = usage.get('prompt_tokens')
        milliseconds, timed = timings.get('predicted_ms'), timings.get('predicted_n')
        has_time = valid(timed) and type(milliseconds) in (int, float) and math.isfinite(milliseconds) and milliseconds > 0
        # Local calendar days match the desktop's clock, with no inferred device attribution.
        day = dt.datetime.fromtimestamp(self.now()).date().isoformat()
        values = (day, output if measured else 0, input_tokens if valid(input_tokens) else 0,
                  int(measured), int(not measured), timed if has_time else 0, milliseconds if has_time else 0)
        with self.lock:
            db = self.connect()
            try:
                with db:
                    db.execute('INSERT INTO daily_usage VALUES(?,?,?,?,?,?,?) ON CONFLICT(day) DO UPDATE SET '
                               'output=output+excluded.output, input=input+excluded.input, measured=measured+excluded.measured, '
                               'missing=missing+excluded.missing, timed_tokens=timed_tokens+excluded.timed_tokens, '
                               'generation_ms=generation_ms+excluded.generation_ms', values)
            finally:
                db.close()

    def snapshot(self):
        today = dt.datetime.fromtimestamp(self.now()).date()
        with self.lock:
            db = self.connect()
            try:
                totals = db.execute('SELECT COALESCE(SUM(output),0), COALESCE(SUM(measured),0), '
                                    'COALESCE(SUM(missing),0), COALESCE(SUM(timed_tokens),0), COALESCE(SUM(generation_ms),0) FROM daily_usage').fetchone()
                rows = {row[0]: row[1:] for row in db.execute('SELECT day,output,measured,missing FROM daily_usage WHERE day>=?', ((today - dt.timedelta(days=6)).isoformat(),))}
            finally:
                db.close()
        days = []
        for offset in range(6, -1, -1):
            day = (today - dt.timedelta(days=offset)).isoformat()
            output, measured, missing = rows.get(day, (0, 0, 0))
            days.append({'day': day, 'outputTokens': output if measured or not missing else None, 'missingMeasurements': missing})
        return {'scope': 'this_installation', 'outputTokens': totals[0] if totals[1] or not totals[2] else None,
                'measuredRounds': totals[1], 'unmeasuredRounds': totals[2], 'days': days,
                'tokensPerSecond': totals[3] * 1000 / totals[4] if totals[4] > 0 else None,
                'measurement': 'provider_reported_output_including_reasoning', 'available': True}


def install_usage(runtime):
    store = UsageStore(Path(runtime.GATEWAY_JOB_DB_PATH).with_name('usage.sqlite3'))
    runtime.gateway_usage = store
    original = runtime.post_llama_model_round

    def record(result):
        try:
            store.record(result)
        except (OSError, sqlite3.Error):
            # Optional statistics must never fail a model/tool request.
            runtime.logger.warning('Usage statistics could not be saved')

    def measured(*args, **kwargs):
        try:
            response, elapsed = original(*args, **kwargs)
        except Exception:
            record({})
            raise
        try:
            data = response.json() if response.status_code == 200 else {}
        except (ValueError, TypeError):
            data = {}
        record(data)
        return response, elapsed

    runtime.post_llama_model_round = measured


def dashboard_snapshot(runtime):
    try:
        usage = runtime.gateway_usage.snapshot()
    except (AttributeError, OSError, sqlite3.Error):
        usage = {'available': False, 'outputTokens': None, 'tokensPerSecond': None, 'days': []}
    with runtime.job_registry_lock:
        jobs = sorted(runtime.job_registry.values(), key=lambda job: job.get('updated_at') or 0, reverse=True)[:8]
        # Never expose prompts, tool arguments, credentials or generated text here.
        recent = [{'id': job['job_id'], 'model': job.get('model'), 'status': job.get('status'),
                   'kind': job.get('mode', 'chat'), 'updatedAt': job.get('updated_at')} for job in jobs]
    return {'usage': usage, 'recentJobs': recent}
