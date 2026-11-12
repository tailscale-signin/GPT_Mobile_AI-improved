"""Presentation helpers for measured desktop observations and bounded history."""
import json
from pathlib import Path
import time

TERMINAL = {'completed', 'failed', 'cancelled', 'canceled', 'interrupted'}


def read_snapshot(root, now=None):
    now = time.time() if now is None else now
    root = Path(root)
    def read(name):
        try:
            path = root / name
            if path.stat().st_size > 2 * 1024 * 1024:
                return {}
            value = json.loads(path.read_text(encoding='utf-8'))
            return value if isinstance(value, dict) else {}
        except (OSError, ValueError):
            return {}
    result = read('dashboard.json')
    status = read('status.json')
    stamp = result.get('capturedAt', 0)
    fresh = isinstance(stamp, (int, float)) and 0 <= now - stamp <= 10 and status.get('state') == 'running'
    return dict(result, manager=status, fresh=fresh)


def rows(snapshot, page, query=''):
    if page == 'Devices':
        candidates = snapshot.get('devices', [])
    elif page == 'Jobs':
        candidates = snapshot.get('jobs', []) + snapshot.get('engineering', [])
    elif page == 'Search jobs':
        candidates = [item for item in snapshot.get('activities', []) if item.get('kind') == 'search']
    elif page == 'GitHub jobs':
        candidates = snapshot.get('engineering', []) + [item for item in snapshot.get('activities', []) if item.get('kind') == 'github']
    else:
        candidates = snapshot.get('jobs', []) + snapshot.get('engineering', []) + snapshot.get('activities', [])
    query = query.strip().casefold()
    def matches(item):
        return not query or query in ' '.join(str(item.get(key) or '') for key in ('id', 'title', 'status', 'address', 'agent', 'detail', 'branch', 'job', 'peer')).casefold()
    def stamp(item):
        value = item.get('updated', item.get('lastSeen', item.get('started', 0)))
        return value if isinstance(value, (int, float)) else 0
    return sorted((item for item in candidates if isinstance(item, dict) and matches(item)), key=stamp, reverse=True)[:300]


def counters(snapshot):
    recent_devices = sum(item.get('state') in {'active', 'recent'} for item in snapshot.get('devices', []))
    jobs = snapshot.get('jobs', []) + snapshot.get('engineering', [])
    active = sum(str(item.get('status', '')).lower() not in TERMINAL for item in jobs)
    return (recent_devices if snapshot.get('fresh') else None, active if snapshot.get('fresh') else None,
            sum(item.get('kind') == 'search' for item in snapshot.get('activities', [])), len(snapshot.get('engineering', [])))


def age(stamp, now=None):
    if not isinstance(stamp, (int, float)) or stamp <= 0:
        return 'Not available'
    seconds = max(0, (time.time() if now is None else now) - stamp)
    if seconds < 60:
        return f'{int(seconds)}s ago'
    if seconds < 3600:
        return f'{int(seconds // 60)}m ago'
    if seconds < 86400:
        return f'{int(seconds // 3600)}h ago'
    return f'{int(seconds // 86400)}d ago'
