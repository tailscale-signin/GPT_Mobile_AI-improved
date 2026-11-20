"""Read a bounded Crossref metadata result for an operator-entered public topic."""
from contextlib import closing
import json
import os
import re
import time
from urllib.parse import quote
import uuid

import requests

KIND = 'Watch Crossref publications'
DOI = re.compile(r'10\.[0-9]{4,9}/[^\s\x00-\x1f\x7f]{1,210}', re.IGNORECASE)


def literature(query):
    params = {'query.bibliographic': query, 'sort': 'published', 'order': 'desc', 'rows': 8,
              'select': 'DOI,title'}
    endpoint = 'https://api.crossref.org/works'
    with requests.Session() as session:
        # Honor the operator's network route and CA bundle without loading
        # credentials from .netrc for a public research query.
        session.trust_env = False
        session.proxies.update({key: value for key, value in requests.utils.get_environ_proxies(endpoint).items()
                                if key in ('https', 'http', 'all')})
        session.verify = os.environ.get('REQUESTS_CA_BUNDLE') or os.environ.get('CURL_CA_BUNDLE') or True
        deadline = time.monotonic() + 45
        with session.get(endpoint, params=params, stream=True, timeout=(12, 18),
                         allow_redirects=False, headers={'User-Agent': 'GPT-Mobile-Gateway/17 public literature watch'}) as reply:
            if reply.status_code != 200:
                raise ValueError('Crossref unavailable')
            body = bytearray()
            for block in reply.iter_content(4096):
                body.extend(block)
                if time.monotonic() > deadline:
                    raise ValueError('Crossref metadata timed out')
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
        row = db.execute('''SELECT s.id,s.due,s.period,s.result,w.query,w.results,w.seen FROM schedule s
                            JOIN publication_watch w ON w.id=s.id WHERE s.enabled=1 AND s.due<=? AND w.lease_until<=?
                            ORDER BY s.due, s.id LIMIT 1''', (now, now)).fetchone()
        if row is None:
            return
        db.execute('UPDATE publication_watch SET token=?,lease_until=? WHERE id=?', (token, now + 180, row['id']))
    alert = None
    try:
        previous = json.loads(row['seen'])
        if not isinstance(previous, list) or not all(isinstance(key, str) for key in previous):
            raise ValueError('Invalid retained watch history')
        found = fetch(row['query'])
        ids = [item['doi'] for item in found]
        fresh = [key for key in ids if key not in previous]
        note = (f'Baseline recorded · {len(found)} results' if row['results'] is None else
                f'{len(fresh)} new publication' + ('s' if len(fresh) > 1 else '') if fresh else 'No new publications in the latest results')
        seen = json.dumps(list(dict.fromkeys(ids + previous))[:256])
        results = json.dumps(found, ensure_ascii=False)
        if row['results'] is not None and fresh:
            alert = note + ' · ' + ', '.join(fresh)
    except (OSError, requests.RequestException, ValueError, RecursionError):
        note, seen, results = 'Blocked · Crossref metadata unavailable', row['seen'], row['results']
        if row['result'] != note:
            alert = note
    with closing(store.connect()) as db, db:
        db.execute('BEGIN IMMEDIATE')
        updated = db.execute('UPDATE publication_watch SET token=NULL,lease_until=0,results=?,seen=? WHERE id=? AND token=?',
                             (results, seen, row['id'], token)).rowcount
        if updated:
            due, period = row['due'], row['period']
            current = max(now, int(store.now()))
            next_due = due + ((current - due) // period + 1) * period if period else None
            db.execute('UPDATE schedule SET due=?,last_due=?,result=? WHERE id=?', (next_due, due, note, row['id']))
            if alert:
                store.notify(db, row['id'], due, alert)
