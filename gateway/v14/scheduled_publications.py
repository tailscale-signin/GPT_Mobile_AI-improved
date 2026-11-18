"""Read a bounded Crossref metadata result for an operator-entered public topic."""
from contextlib import closing
import json
import re
from urllib.parse import quote
import uuid

import requests

KIND = 'Watch Crossref publications'
DOI = re.compile(r'10\.[0-9]{4,9}/[^\s\x00-\x1f\x7f]{1,210}', re.IGNORECASE)


def literature(query):
    params = {'query.bibliographic': query, 'sort': 'published', 'order': 'desc', 'rows': 8,
              'select': 'DOI,title'}
    with requests.Session() as session:
        session.trust_env = False
        with session.get('https://api.crossref.org/works', params=params, stream=True, timeout=(3, 8),
                         allow_redirects=False, headers={'User-Agent': 'GPT-Mobile-Gateway/17 public literature watch'}) as reply:
            if reply.status_code != 200:
                raise ValueError('Crossref unavailable')
            body = bytearray()
            for block in reply.iter_content(4096):
                body.extend(block)
                if len(body) > 65536:
                    raise ValueError('Crossref metadata exceeded limit')
    value = json.loads(body)
    message = value.get('message') if isinstance(value, dict) and value.get('status') == 'ok' else None
    items = message.get('items') if isinstance(message, dict) else None
    if not isinstance(items, list) or len(items) > 8:
        raise ValueError('Missing Crossref results')
    rows, seen = [], set()
    for item in items:
        doi = item.get('DOI') if isinstance(item, dict) else None
        titles = item.get('title') if isinstance(item, dict) else None
        if not isinstance(doi, str) or not DOI.fullmatch(doi) or not isinstance(titles, list) or not titles or not isinstance(titles[0], str):
            continue
        label = ' '.join(titles[0].split())[:320]
        if label and doi.lower() not in seen:
            rows.append({'title': label, 'doi': doi.lower()})
            seen.add(doi.lower())
    if items and not rows:
        raise ValueError('No usable records')
    return rows


def source_url(doi):
    return 'https://doi.org/' + quote(doi, safe='/') if DOI.fullmatch(doi) else None


def run_watch(store, now, fetch=literature):
    # Acquire one durable occurrence. Network I/O never holds a database lock.
    token = uuid.uuid4().hex
    with closing(store.connect()) as db, db:
        db.execute('BEGIN IMMEDIATE')
        row = db.execute('''SELECT s.id,s.due,s.period,w.query,w.results,w.seen FROM schedule s
                            JOIN publication_watch w ON w.id=s.id WHERE s.due<=? AND w.lease_until<=?
                            ORDER BY s.due, s.id LIMIT 1''', (now, now)).fetchone()
        if row is None:
            return
        db.execute('UPDATE publication_watch SET token=?,lease_until=? WHERE id=?', (token, now + 180, row['id']))
    previous = json.loads(row['seen'])
    try:
        found = fetch(row['query'])
        ids = [item['doi'] for item in found]
        fresh = [key for key in ids if key not in previous]
        note = (f'Baseline recorded · {len(found)} results' if row['results'] is None else
                f'{len(fresh)} new publication' + ('s' if len(fresh) > 1 else '') if fresh else 'No new publications in the latest results')
        seen = json.dumps(list(dict.fromkeys(ids + previous))[:256])
        results = json.dumps(found, ensure_ascii=False)
    except (OSError, requests.RequestException, ValueError, RecursionError):
        note, seen, results = 'Blocked · Crossref metadata unavailable', row['seen'], row['results']
    with closing(store.connect()) as db, db:
        db.execute('BEGIN IMMEDIATE')
        updated = db.execute('UPDATE publication_watch SET token=NULL,lease_until=0,results=?,seen=? WHERE id=? AND token=?',
                             (results, seen, row['id'], token)).rowcount
        if updated:
            due, period = row['due'], row['period']
            current = max(now, int(store.now()))
            next_due = due + ((current - due) // period + 1) * period if period else None
            db.execute('UPDATE schedule SET due=?,last_due=?,result=? WHERE id=?', (next_due, due, note, row['id']))
