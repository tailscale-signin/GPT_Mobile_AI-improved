#!/usr/bin/env python3
"""Measure a real build command and APK composition; never substitute target scores."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import resource
import subprocess
import time
import xml.etree.ElementTree as ET
import zipfile


def apk_metrics(path):
    categories = dict(native=0, dex=0, resources=0, assets=0, other=0)
    with zipfile.ZipFile(path) as archive:
        entries = archive.infolist()
        for entry in entries:
            category = ('native' if entry.filename.startswith('lib/') else 'dex' if entry.filename.endswith('.dex')
                        else 'resources' if entry.filename.startswith('res/') or entry.filename == 'resources.arsc'
                        else 'assets' if entry.filename.startswith('assets/') else 'other')
            categories[category] += entry.compress_size
        largest = [{'path': item.filename, 'compressed_bytes': item.compress_size, 'uncompressed_bytes': item.file_size}
                   for item in sorted(entries, key=lambda item: item.compress_size, reverse=True)[:20]]
    return {'file': path.name, 'apk_bytes': path.stat().st_size, 'compressed_categories': categories, 'largest_entries': largest}


def coverage(path):
    if not path.is_file():
        return None
    result = {}
    for row in ET.parse(path).getroot().findall('counter'):
        covered, missed = int(row.attrib['covered']), int(row.attrib['missed'])
        result[row.attrib['type']] = {'covered': covered, 'missed': missed,
                                    'fraction': covered / (covered + missed) if covered + missed else None}
    return result


def compare(current, baseline):
    # Different machines/tasks/variants cannot establish an improvement percentage.
    if current.get('comparison_key') != baseline.get('comparison_key'):
        return {'comparable': False, 'reason': 'runner, Java, variant or command differs'}
    result = {'comparable': True}
    for key in ('elapsed_seconds', 'child_peak_rss_kb'):
        old, new = baseline.get(key), current.get(key)
        result[key + '_change_percent'] = (new / old - 1) * 100 if old and new is not None else None
    return result


def collect_apks(directory):
    reports, errors = [], []
    for path in sorted(directory.rglob('*.apk')):
        try:
            reports.append(apk_metrics(path))
        except (OSError, zipfile.BadZipFile):
            errors.append({'file': path.name, 'status': 'UNREADABLE_OR_INCOMPLETE'})
    return reports, errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--label', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--baseline')
    parser.add_argument('command', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ['--'] else args.command
    if not command:
        parser.error('A build command is required')
    started = time.monotonic()
    tasks = dict(executed=0, from_cache=0, up_to_date=0, skipped=0, no_source=0)
    child = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace')
    for line in child.stdout:
        print(line, end='', flush=True)
        if re.match(r'^> Task :', line):
            tasks['from_cache' if 'FROM-CACHE' in line else 'up_to_date' if 'UP-TO-DATE' in line else 'no_source' if 'NO-SOURCE' in line else 'skipped' if 'SKIPPED' in line else 'executed'] += 1
    code = child.wait()
    usage = resource.getrusage(resource.RUSAGE_CHILDREN)
    environment = {'runner': os.getenv('RUNNER_ARCH', os.uname().machine), 'os': os.getenv('ImageOS', os.uname().sysname),
                   'image_version': os.getenv('ImageVersion'), 'java_home': os.getenv('JAVA_HOME'), 'label': args.label, 'command': command}
    count = tasks['executed'] + tasks['from_cache'] + tasks['up_to_date']
    trace = {key: os.getenv(env) for key, env in (('repository', 'GITHUB_REPOSITORY'), ('run_id', 'GITHUB_RUN_ID'),
             ('attempt', 'GITHUB_RUN_ATTEMPT'), ('job', 'GITHUB_JOB'), ('ref', 'GITHUB_REF_NAME'))}
    trace['trace_id'] = hashlib.sha256(json.dumps({key: trace[key] for key in ('repository', 'run_id', 'attempt')}, sort_keys=True).encode()).hexdigest()
    trace['span_id'] = hashlib.sha256((trace['trace_id'] + str(trace['job']) + args.label).encode()).hexdigest()[:16]
    apks, apk_errors = collect_apks(Path('app/build/outputs/apk'))
    try:
        test_coverage = coverage(Path('app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml'))
    except (OSError, ET.ParseError, KeyError, ValueError):
        test_coverage = {'status': 'UNREADABLE_OR_INCOMPLETE'}
    report = {'schema_version': 1, 'label': args.label, 'commit_sha': os.getenv('GITHUB_SHA'), 'exit_code': code, 'trace': trace,
              'elapsed_seconds': round(time.monotonic() - started, 3), 'child_peak_rss_kb': usage.ru_maxrss,
              'rss_definition': 'Linux child-process high-water mark, not simultaneous aggregate memory',
              'block_input_operations': usage.ru_inblock, 'block_output_operations': usage.ru_oublock,
              'tasks': tasks, 'task_cache_hit_rate': tasks['from_cache'] / count if count else None,
              'comparison_key': hashlib.sha256(json.dumps(environment, sort_keys=True).encode()).hexdigest(),
              'environment': environment, 'apks': apks, 'apk_errors': apk_errors, 'coverage': test_coverage}
    if args.baseline:
        report['comparison'] = compare(report, json.loads(Path(args.baseline).read_text()))
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + '\n')
    summary = os.getenv('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as target:
            target.write(f"\n### {args.label}\n\nElapsed: {report['elapsed_seconds']} s. Task cache hits: {tasks['from_cache']}/{count}. Exit: {code}.\n")
    return code


if __name__ == '__main__':
    raise SystemExit(main())
