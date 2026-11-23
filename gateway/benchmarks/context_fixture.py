"""Public, revision-bound retrieval inputs for controlled coding context experiments.

Only the broken sources and public contract enter this projection. Reference
solutions, hidden vectors, expected answers and grader internals are never inputs
to the context selector. The manifest identifies a frozen fixture, not a GitHub
commit, and does not claim ground-truth retrieval relevance.
"""
import hashlib
import json
import re

from benchmarks.contracts import digest

SCHEMA = 'benchmark.context-fixture.v1'
VARIANTS = ('CURRENT', 'REFERENCE', 'TASK', 'SEMANTIC')


def blob_sha(content):
    raw = content.encode('utf-8')
    return hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()


def public_context_inputs(task):
    sources = task.get('sources')
    if not isinstance(sources, dict) or not 1 <= len(sources) <= 128:
        raise ValueError('A bounded public source manifest is required')
    public = {}
    for path, source in sorted(sources.items()):
        if (not isinstance(path, str) or not re.fullmatch(r'[A-Za-z0-9_./-]{1,256}', path)
                or path.startswith('/') or any(part in ('', '.', '..') for part in path.split('/'))
                or path in {'docs/architecture/repository-guide.md', 'docs/architecture/feature-map.json'}
                or not isinstance(source, str) or len(source.encode()) > 256 * 1024 or '\0' in source):
            raise ValueError('Invalid public context source')
        public[path] = source
    if sum(len(value.encode()) for value in public.values()) > 1024 * 1024:
        raise ValueError('Public context sources exceed the fixture allowance')
    # Facts are derived solely from the displayed source. This is not the trusted
    # referenceSources solution retained by the independent grader.
    declarations = re.compile(r'^\s*(?:fun|def|class|interface|object|function|struct)\s+([A-Za-z_][A-Za-z0-9_]*)')
    rows = ['# Public fixture code reference', '',
            'This reference describes the supplied starting source. It contains no repair, hidden test or expected answer.', '',
            '## Source entry points', '']
    for path, source in public.items():
        symbols = [match.group(1) for line in source.splitlines() if (match := declarations.match(line))]
        rows.append('- ' + path + (' — declarations: ' + ', '.join(dict.fromkeys(symbols)) if symbols else ''))
    rows += ['', '## Validation', '',
             'Use the existing independent executable grader. Source discovery is not evidence of passing tests.',
             'No public test paths or independent relevant-file labels are supplied by this fixture.']
    public['docs/architecture/repository-guide.md'] = '\n'.join(rows) + '\n'
    feature_map = {
        'schema_version': 1, 'commands_are_authority': False,
        'features': [{'id': 'public-fixture', 'title': str(task.get('title', 'Public fixture')),
                      'terms': sorted(set(re.findall(r'[A-Za-z_][A-Za-z0-9_]{2,}', str(task.get('title', ''))))),
                      'paths': sorted(sources), 'tests': [], 'commands': [],
                      'documents': ['docs/architecture/repository-guide.md'],
                      'invariants': []}],
        'file_blobs': {path: {'blob_sha': blob_sha(source), 'bytes': len(source.encode()),
                              'sha256': hashlib.sha256(source.encode()).hexdigest()}
                       for path, source in sorted(public.items())},
    }
    public['docs/architecture/feature-map.json'] = json.dumps(feature_map, sort_keys=True, separators=(',', ':'), ensure_ascii=True) + '\n'
    result = {
        'schema': SCHEMA,
        'sourceIdentityKind': 'FROZEN_FIXTURE_MANIFEST',
        'query': str(task.get('title', '')) + '\n' + str(task.get('contract', task.get('requirements', ''))),
        'files': [{'path': path, 'blobSha': blob_sha(source), 'content': source}
                  for path, source in sorted(public.items())],
        'declaredSourcePaths': sorted(sources),
        'relevanceScope': 'DECLARED_PUBLIC_SOURCE_COVERAGE_ONLY',
    }
    result['sourceSha'] = digest(result['files'])
    return result


def project_public_context(task):
    inputs = public_context_inputs(task)
    return dict(contextInputs=inputs, contextManifestDigest=digest(inputs))
