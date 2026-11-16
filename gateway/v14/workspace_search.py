"""Bounded repository-wide retrieval from the verified mirror and code graph.

The host constructs every Git argument. No worktree, hooks, external diff driver,
model-provided command, repository code, or unpinned revision is executed.
"""
import fnmatch
import hashlib
import io
import json
import re

import requests

from v14.engineering_validation import cancellation_scope, load_policy, run_bounded

PAGE_FILES = 64
PAGE_MATCHES = 40


class WorkspaceSearchService:
    def __init__(self, backend, job):
        self.backend, self.job = backend, job
        self.head = job['data']['head_sha']
        self.tree = job['data']['tree']

    def paths(self, action):
        query, pattern = action.get('query', ''), action.get('glob', '*')
        if not isinstance(query, str) or len(query) > 200 or not isinstance(pattern, str) or not 1 <= len(pattern) <= 200:
            raise ValueError('Use a bounded query and repository-relative glob')
        return sorted(path for path, entry in self.tree.items() if entry['mode'] in {'100644', '100755'}
                      and query.casefold() in path.casefold() and fnmatch.fnmatchcase(path, pattern))

    def graph(self, action):
        query = action.get('query')
        if not isinstance(query, str) or not 1 <= len(query) <= 200:
            raise ValueError('Use a symbol or relationship query of 1–200 characters')
        graph = self.backend.recall_graph(self.job, query, role='coding agent', paths=[])
        from v14.graph_context import graph_receipt, project_graph
        receipt = graph_receipt(graph, self.job, 'coding agent')
        if not receipt['revision_match']:
            return {'status': 'UNAVAILABLE', 'reason': 'INDEX_REVISION_MISMATCH', 'matches': [], 'complete': False}
        nodes = [node for node in graph.get('nodes', []) if isinstance(node, dict)
                 and node.get('path') in self.tree and node.get('blob_sha') == self.tree[node['path']]['sha']
                 and node.get('commit_sha', self.head) == self.head]
        nodes.sort(key=lambda node: (node.get('name') != query, node.get('path', ''), node.get('line', 0)))
        graph = dict(graph, nodes=nodes)
        return {'status': receipt['status'], 'graph': project_graph(graph, 8000),
                'complete': receipt['coverage_complete'], 'source_level_only': True}

    def search(self, action):
        query, mode = action.get('query'), action.get('mode', 'literal')
        if not isinstance(query, str) or not 1 <= len(query) <= 200 or '\0' in query or '\n' in query or mode not in {'literal', 'regex'}:
            raise ValueError('Search needs a 1–200 character literal or POSIX extended regex')
        if not re.fullmatch('[0-9a-f]{40}', self.head):
            raise ValueError('Search requires an exact source SHA')
        paths = self.paths(dict(action, query=''))
        selected = action.get('paths')
        if selected is not None:
            if not isinstance(selected, list) or not selected or len(selected) > 200 or any(path not in paths for path in selected):
                raise ValueError('Search paths must be regular files in the pinned tree and glob')
            paths = sorted(set(selected))
        binding = hashlib.sha256(json.dumps([self.head, query, mode, paths], sort_keys=True).encode()).hexdigest()
        cursor = action.get('cursor') or {'binding': binding, 'file_offset': 0, 'match_offset': 0}
        if (not isinstance(cursor, dict) or set(cursor) != {'binding', 'file_offset', 'match_offset'}
                or cursor['binding'] != binding or any(type(cursor[k]) is not int or cursor[k] < 0 for k in ('file_offset', 'match_offset'))
                or cursor['file_offset'] > len(paths)):
            raise ValueError('Search cursor belongs to a different query or source revision')
        offset, match_offset = cursor['file_offset'], cursor['match_offset']
        selected = paths[offset:offset + PAGE_FILES]
        if not selected:
            return {'matches': [], 'next_cursor': None, 'complete': True, 'matching_files': len(paths)}
        from v14.engineering_source import prepare_source
        from desktop.repository_validation import git_prefix
        from v14.validation_host import executable
        parent = self.backend.api(self.backend.root(self.job) + '/git/commits/' + self.head, job=self.job)
        cancel = self.backend.cancellation(self.job)
        prepare_source(self.job, self.backend.token(), parent['tree']['sha'], cancel)
        _, mirror, _, _, _ = load_policy(self.job['request']['repository'])
        output = io.BytesIO()
        # --literal-pathspecs and -e prevent paths/patterns from becoming options.
        patterns = [query]
        if mode == 'regex' and query.endswith('$'):
            slashes = len(query[:-1]) - len(query[:-1].rstrip('\\'))
            if slashes % 2 == 0:
                # Git for Windows can retain CRLF in immutable blobs. Treat the
                # carriage return as part of the line ending without changing
                # the caller's query binding or Linux/LF result.
                patterns.append(query[:-1] + '\r$')
        args = git_prefix(executable('git')) + ['--literal-pathspecs', '--git-dir=' + str(mirror),
            'grep', '--no-textconv', '--no-ext-grep', '-I', '-n', '-z', '-i', '-E' if mode == 'regex' else '-F']
        for pattern in patterns:
            args += ['-e', pattern]
        args += [self.head, '--', *selected]
        with cancellation_scope(cancel, self.job['deadline']):
            result = run_bounded(args, mirror.parent, 20, maximum=4 * 1024 * 1024, destination=output)
        if result['exit_code'] not in (0, 1):
            raise ValueError('Search failed: check the POSIX regex and verified source mirror')
        # Format is revision:path NUL line NUL text LF. Split only the text on LF;
        # repository filenames can contain LF, and source text can contain colons.
        raw, matches = output.getvalue(), []
        while raw:
            name, sep, rest = raw.partition(b'\0')
            number, sep2, rest = rest.partition(b'\0')
            line, _, raw = rest.partition(b'\n')
            path = name.decode('utf-8', 'replace').removeprefix(self.head + ':')
            if not sep or not sep2 or path not in selected or not number.isdigit():
                raise ValueError('Search returned an invalid source reference')
            matches.append({'path': path, 'blob_sha': self.tree[path]['sha'], 'line': int(number),
                            'text': line[:600].decode('utf-8', 'replace')})
        page = matches[match_offset:match_offset + PAGE_MATCHES]
        if match_offset > len(matches):
            raise ValueError('Search cursor exceeds the current immutable result page')
        if match_offset + PAGE_MATCHES < len(matches):
            next_cursor = dict(cursor, match_offset=match_offset + PAGE_MATCHES)
        elif offset + PAGE_FILES < len(paths):
            next_cursor = dict(cursor, file_offset=offset + PAGE_FILES, match_offset=0)
        else:
            next_cursor = None
        # Ranking is local to this stable page; pagination never drops matches.
        page.sort(key=lambda row: (query.casefold() not in row['path'].casefold(), row['path'], row['line']))
        return {'matches': page, 'next_cursor': next_cursor, 'complete': next_cursor is None,
                'matching_files': len(paths), 'scanned_files': len(selected), 'mode': mode,
                'binary_files': 'EXCLUDED', 'search_binding': binding}

    def act(self, action):
        kind = action['action']
        if kind in {'symbols', 'relationships'}:
            return self.graph(action)
        if kind == 'explore':
            try:
                graph = self.graph(action)
            except (InterruptedError, PermissionError):
                # Never reinterpret cancellation or revoked authority as an index outage.
                raise
            except requests.exceptions.HTTPError as error:
                # Retry/fallback here is only for observed graph-service 5xx responses.
                # Authentication, 404 and rate limits retain their original failures.
                response = error.response
                if response is None or not 500 <= response.status_code <= 599:
                    raise
                graph = {'status': 'UNAVAILABLE', 'reason': 'GRAPH_HTTP_5XX', 'complete': False}
            except (requests.exceptions.ConnectionError, requests.exceptions.Timeout, OSError):
                # Source exploration is read-only: the pinned mirror can still answer.
                graph = {'status': 'UNAVAILABLE', 'reason': 'GRAPH_TRANSPORT_INTERRUPTED', 'complete': False}
            nodes = graph.get('graph', {}).get('nodes') if isinstance(graph.get('graph'), dict) else None
            if nodes:
                return dict(graph, method='graph')
            return dict(self.search(action), method='text', graph_status=graph['status'],
                        graph_reason=graph.get('reason'))
        if kind == 'glob':
            paths = self.paths(action)
            offset = action.get('offset', 0)
            if type(offset) is not int or offset < 0:
                raise ValueError('Invalid path page')
            return {'paths': paths[offset:offset + 80], 'total': len(paths),
                    'next_offset': offset + 80 if offset + 80 < len(paths) else None}
        return self.search(action)
