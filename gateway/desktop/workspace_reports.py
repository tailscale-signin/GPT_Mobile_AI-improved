"""Prepare one immutable local report and export exactly the reviewed bytes."""
from datetime import datetime
from pathlib import Path
import hashlib
import json
import os
import re
import tempfile
import time
import zipfile

from desktop.manager import request


def matches(row, since, job):
    raw = row.get('timestampMs') or row.get('occurred_at_utc') or row.get('occurredAtUtc')
    try:
        stamp = raw / 1000 if isinstance(raw, (int, float)) else datetime.fromisoformat(str(raw).replace('Z', '+00:00')).timestamp()
    except (ValueError, OverflowError):
        stamp = None
    attrs = row.get('attributes') or {}
    ids = [row.get(k) for k in ('job_id', 'jobId', 'job_reference')] + [attrs.get('job_id'), attrs.get('job_reference')]
    return (not since or stamp is not None and stamp >= since) and (not job or job in ids or row.get('jobDigest') == hashlib.sha256(job.encode()).hexdigest())


def report(root, action, args):
    if action == 'report-events':
        from v14.debug import DebugLogStore
        path = root / 'logs' / 'gateway-debug.jsonl'
        if not path.is_file():
            return {'items': [], 'available': False}
        rows = [json.loads(line) for line in DebugLogStore(path).export().splitlines()]
        return {'items': rows[-1000:], 'available': True, 'retained': len(rows), 'sampled': True,
                'observed_at': path.stat().st_mtime, 'display_limit': 1000}
    if action != 'report-preview':
        raise ValueError('Unknown report operation.')
    hours, job = args.get('hours', 0), args.get('job', '')
    if hours not in (0, 1, 24, 168) or not isinstance(job, str) or job and not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', job):
        raise ValueError('Choose an available time window and a valid job ID.')
    request(root, 'export-debug')
    directory = root / 'data' / 'report-previews'
    directory.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(root / 'logs' / 'gateway-debug.zip') as archive:
        original = json.loads(archive.read('manifest.json'))
        with tempfile.NamedTemporaryFile(dir=directory, suffix='.tmp', delete=False) as temp:
            path = Path(temp.name)
        try:
            selected = dict(original, files=[], parentCoverage={'journal': original.get('journal'), 'transport': original.get('transport')},
                            selection={'hours': hours, 'job': job, 'clock': 'UTC', 'unattributedIncluded': not job},
                            missingSources=[], scope='local_admin_retained_diagnostics')
            cutoff = time.time() - hours * 3600 if hours else 0
            with zipfile.ZipFile(path, 'w', zipfile.ZIP_DEFLATED) as out:
                for name in ('gateway-debug.jsonl', 'diagnostic-events.jsonl'):
                    if name not in archive.namelist():
                        selected['missingSources'].append(name)
                        continue
                    digest, count, size = hashlib.sha256(), 0, 0
                    with archive.open(name) as source, out.open(name, 'w') as target:
                        for line in source:
                            if (hours or job) and not matches(json.loads(line), cutoff, job):
                                continue
                            target.write(line)
                            digest.update(line)
                            size += len(line)
                            count += 1
                    selected['files'].append({'path': name, 'bytes': size, 'records': count, 'sha256': digest.hexdigest()})
                if hours or job:
                    selected['scope'] = 'selected_retained_records'
                    selected.pop('transport', None)
                    selected.pop('journal', None)
                out.writestr('manifest.json', json.dumps(selected, ensure_ascii=False))
            with path.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            final = directory / (digest + '.zip')
            os.replace(path, final)
            for old in directory.glob('*.zip'):
                if old.stat().st_mtime < time.time() - 86400:
                    old.unlink(missing_ok=True)
            return {'manifest': selected, 'sha256': digest, 'bytes': final.stat().st_size}
        finally:
            path.unlink(missing_ok=True)
