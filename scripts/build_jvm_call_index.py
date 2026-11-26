#!/usr/bin/env python3
"""Produce a revision-bound call index from the Gradle compiler outputs, using Gradle's ASM reader."""
import argparse
import collections
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'gateway'))
from v14.code_graph.jvm_index_limits import JSON_LIMIT


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args])


def create(repo, class_dirs, asm, requested=None):
    analyzed = git(repo, 'rev-parse', 'HEAD').decode().strip()
    tree = git(repo, 'rev-parse', 'HEAD^{tree}').decode().strip()
    source_sha = analyzed
    if requested and re.fullmatch('[0-9a-f]{40}', requested):
        result = subprocess.run(['git', '-C', str(repo), 'rev-parse', '--verify', requested + '^{tree}'], capture_output=True)
        if result.returncode == 0 and result.stdout.decode().strip() == tree:
            source_sha = requested
    by_name = collections.defaultdict(list)
    files = []
    for row in git(repo, 'ls-tree', '-rz', analyzed).split(b'\0'):
        if not row:
            continue
        meta, raw = row.split(b'\t', 1)
        path = raw.decode('utf-8', errors='surrogateescape')
        if not path.endswith(('.kt', '.java')) or meta.split()[0] not in (b'100644', b'100755'):
            continue
        data = (repo / path).read_bytes()
        # Build and index must see the committed source, including line numbers.
        sha = meta.split()[2].decode()
        if hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest() != sha:
            raise ValueError('Tracked JVM source differs from selected revision')
        package = re.search(rb'(?m)^\s*package\s+([a-zA-Z_$][a-zA-Z0-9_$.]*)', data)
        pkg = package[1].decode().replace('.', '/') if package else ''
        by_name[pkg, Path(path).name].append(len(files))
        files.append([path, sha, data.count(b'\n') + 1])

    paths = [str(p.resolve()) for p in class_dirs if p.is_dir()]
    if not paths:
        raise ValueError('No compiler output directory was found')
    extracted = {}
    excluded = set()
    with tempfile.TemporaryFile() as stream:
        # JVM source-file mode runs only the trusted reader; application classes are data.
        subprocess.run(['java', '-Xmx512m', '--class-path', str(asm.resolve()), str(Path(__file__).parent / 'jvm/CompilerCalls.java'), *paths],
                       stdout=stream, timeout=180, check=True)
        if stream.tell() > 512 * 1024 * 1024:
            raise ValueError('Compiled call list exceeds the extraction budget')
        stream.seek(0)
        current = None
        for line in stream:
            row = line.decode().rstrip('\r\n').split('\t')
            if row[0] == 'M':
                _, key, owner, filename = row
                matches = by_name[owner.rpartition('/')[0], filename]
                current = None
                if len(matches) == 1 and key not in excluded:
                    if key in extracted:
                        excluded.add(key)
                        del extracted[key]
                    else:
                        current = [matches[0], 0, None]
                        extracted[key] = current
            elif current is not None and row[0] == 'L':
                n = int(row[1])
                current[2] = row[2]
                current[1] = n if 0 < n <= files[current[0]][2] else 0
        ids = {key: n for n, key in enumerate(sorted(extracted))}
        if not ids:
            raise ValueError('No compiled methods matched tracked Java/Kotlin source')
        methods = [[key, *extracted[key]] for key in ids]
        # Resolve targets only after all method IDs are known. CPU and memory stay
        # proportional to repository-internal call edges, including unit-test callers.
        calls = []
        stream.seek(0)
        caller = None
        targets = set()
        for line in stream:
            row = line.decode().rstrip('\r\n').split('\t')
            if row[0] == 'M':
                caller = ids.get(row[1])
                targets.clear()
            elif caller is not None and row[0] == 'C' and row[1] in ids:
                n = int(row[2])
                targets.add((ids[row[1]], n if 0 < n <= files[methods[caller][1]][2] else 0))
            elif caller is not None and row[0] == 'L':
                calls.extend([caller, callee, line] for callee, line in sorted(targets))
    return {'schema': 1, 'commit_sha': source_sha, 'analyzed_sha': analyzed, 'tree_sha': tree,
            'run_id': os.getenv('GITHUB_RUN_ID', ''), 'run_attempt': os.getenv('GITHUB_RUN_ATTEMPT', ''),
            'files': files, 'methods': methods, 'calls': calls}


def write_index(index, output):
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as staging:
        candidate = Path(staging) / output.name
        with candidate.open('w', encoding='utf-8') as stream:
            json.dump(index, stream, separators=(',', ':'))
        size = candidate.stat().st_size
        if size > JSON_LIMIT:
            raise ValueError(f'Compiled JVM call index uses {size} bytes and exceeds the {JSON_LIMIT}-byte Gateway limit')
        candidate.replace(output)
    return size


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repo', type=Path, default=Path.cwd())
    parser.add_argument('--classes', type=Path, nargs='+', required=True)
    parser.add_argument('--asm', type=Path, required=True)
    parser.add_argument('--head', default='')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    index = create(args.repo.resolve(), args.classes, args.asm, args.head)
    size = write_index(index, args.output)
    print(f"Indexed {len(index['methods'])} methods and {len(index['calls'])} source-mapped calls at {index['commit_sha']} ({size} bytes)")
    if os.getenv('GITHUB_OUTPUT'):
        with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as out:
            print('source_sha=' + index['commit_sha'], file=out)
            print('asm=' + str(args.asm.resolve()), file=out)


if __name__ == '__main__':
    main()
