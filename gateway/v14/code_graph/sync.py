"""Bounded, content-verified structural index deltas for reconnectable clients.

The client receives source identities and bounded symbol/reference candidates,
not credentials or source bodies. A complete transfer is distinct from complete
repository coverage or compiler/runtime verification.
"""
import base64
import hashlib
import json
import re
import zlib

from . import EXTRACTOR_VERSION
from .engine import encoded, snapshot_metadata
from .extract import identity

MAX_PAYLOAD_BYTES = 32768
MAX_PAGE_BYTES = 60000


def digest(value):
    return hashlib.sha256(value.encode('utf-8')).hexdigest()


def records(snapshot):
    result = {}
    for path, entry in snapshot['manifest'].items():
        fragment = snapshot['fragments'].get(path)
        record = {'path': path, 'blob_sha': entry['sha'], 'size': entry.get('size', 0),
                  'parser_version': EXTRACTOR_VERSION, 'status': (fragment or {}).get('status', 'pending'),
                  'source_level_only': True, 'runtime_verified': False}
        if fragment:
            record.update({name: fragment[name] for name in ('language', 'parser', 'source_bytes') if name in fragment})
            record['nodes'] = [{key: node[key] for key in ('id', 'name', 'kind', 'qualified', 'line', 'end_line') if key in node}
                               for node in fragment.get('nodes', [])[:64]]
            record['references'] = fragment.get('references', [])[:64]
            record['imports'] = fragment.get('imports', [])[:32]
            record['projection_truncated'] = any(len(fragment.get(key, [])) > limit
                                                  for key, limit in (('nodes', 64), ('references', 64), ('imports', 32)))
            while len(encoded(record).encode()) > MAX_PAYLOAD_BYTES:
                longest = max(('nodes', 'references', 'imports'), key=lambda key: len(record[key]))
                if not record[longest]:
                    raise ValueError('Repository synchronization record exceeds its bound')
                record[longest] = record[longest][:len(record[longest]) // 2]
                record['projection_truncated'] = True
        payload = encoded(record)
        result[path] = {'path': path, 'blob_sha': entry['sha'], 'digest': digest(payload),
                        'payload': base64.b64encode(zlib.compress(payload.encode())).decode(),
                        'encoding': 'zlib-base64'}
    return result


def generation(rows):
    # UTF-16 ordering matches the Android String comparator, including paths
    # containing non-BMP characters. Hash unambiguous source/record identities.
    paths = sorted(rows, key=lambda value: value.encode('utf-16-be'))
    return digest(''.join(path + '\0' + rows[path]['blob_sha'] + '\0' + rows[path]['digest'] + '\n' for path in paths))


def synchronize(store, namespace, request, observed_head):
    offset = request.get('sync_offset', 0)
    if type(offset) is not int or offset < 0:
        raise ValueError('Invalid repository synchronization offset')
    for name in ('sync_generation', 'base_generation'):
        if name in request and not re.fullmatch('[a-f0-9]{64}', request[name]):
            raise ValueError('Invalid repository synchronization generation')
    if 'base_sha' in request and not re.fullmatch('[a-f0-9]{40}', request['base_sha']):
        raise ValueError('Invalid repository synchronization base revision')
    snapshot = store.load(namespace, request['commit_sha'])
    if snapshot is None:
        return {'status': 'NOT_INDEXED', 'graph': {'repository': request['repository'], 'branch': request['branch'],
                'commit_sha': request['commit_sha'], 'coverage_complete': False}, 'observed_branch_head': observed_head}
    current = records(snapshot)
    target = generation(current)
    if request.get('sync_generation', target) != target:
        raise ValueError('Repository synchronization generation changed; restart from the last verified snapshot')
    previous = store.load(namespace, request['base_sha']) if request.get('base_sha') else None
    previous_rows = records(previous) if previous else {}
    delta = bool(previous and request.get('base_generation') == generation(previous_rows))
    rows = [current[path] for path in sorted(current) if not delta or current[path]['digest'] != previous_rows.get(path, {}).get('digest')]
    if delta:
        rows += [{'path': path, 'deleted': True} for path in sorted(previous_rows.keys() - current.keys())]
    if offset > len(rows):
        raise ValueError('Repository synchronization offset is beyond this delta')
    transfer = identity('repository-sync-v1', namespace, target, request.get('base_generation') if delta else None)
    if request.get('transfer_id', transfer) != transfer:
        raise ValueError('Repository synchronization belongs to another account, branch or base')
    page = []
    charged = 4000
    for row in rows[offset:offset + 100]:
        size = len(encoded(row).encode())
        if charged + size > MAX_PAGE_BYTES:
            break
        page.append(row)
        charged += size
    if offset < len(rows) and not page:
        raise ValueError('Repository synchronization record cannot fit a page')
    next_offset = offset + len(page)
    return {'schema_version': 1, 'access_scope': namespace, 'status': 'SYNCHRONIZED' if next_offset == len(rows) else 'UPDATING',
            'graph': snapshot_metadata(snapshot), 'observed_branch_head': observed_head,
            'stale': observed_head != snapshot['sha'], 'generation': target, 'transfer_id': transfer,
            'delta': delta, 'base_generation': request.get('base_generation') if delta else None,
            'record_count': len(current), 'change_count': len(rows), 'offset': offset,
            'next_offset': next_offset if next_offset < len(rows) else None, 'records': page}
