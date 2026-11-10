#!/usr/bin/env python3
"""Bind named source-harness observations to real JUnit reports and this checkout.

This is a separate qualification artifact. It never imports results into an audit,
executes a target tool, or converts source inventory into execution coverage.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
CATALOG = Path('app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/audit/AppAuditHarnessCatalog.kt')
INVENTORY = Path('app/src/main/assets/diagnostics/app-code-inventory.json')
APPLICATION_PREFIX = 'dev/chungjungsoo/gptmobile/'
MAX_REPORT_BYTES = 16 * 1024 * 1024
# JaCoCo aggregates the whole application. Keep an independent, bounded
# allowance for that report without widening per-suite JUnit input.
MAX_COVERAGE_REPORT_BYTES = 64 * 1024 * 1024


def digest(data):
    return hashlib.sha256(data).hexdigest()


def timestamp(value):
    parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
    return parsed.replace(tzinfo=timezone.utc) if parsed.tzinfo is None else parsed.astimezone(timezone.utc)


def catalog(root):
    source = (root / CATALOG).read_text()
    entries = re.findall(r'"([a-z_]+)" to listOf\((.*?)\n    \)', source, re.S)
    mapping = {area: re.findall(r'"([^"\n]+)"', paths) for area, paths in entries}
    areas = {area['id'] for area in json.loads((root / INVENTORY).read_text())['areas']}
    if set(mapping) != areas or len(mapping) != len(entries):
        raise ValueError('Harness catalog does not match the source inventory')
    for paths in mapping.values():
        for path in paths:
            candidate = Path(path)
            if candidate.is_absolute() or '..' in candidate.parts or not (root / candidate).is_file():
                raise ValueError('Harness catalog contains an unavailable source')
    return mapping


def read_xml(path, allow_doctype=False, max_bytes=MAX_REPORT_BYTES):
    if path.stat().st_size > max_bytes:
        raise ValueError('REPORT_TOO_LARGE')
    with path.open('rb') as source:
        data = source.read(max_bytes + 1)
    if len(data) > max_bytes:
        raise ValueError('REPORT_TOO_LARGE')
    # Gradle and JaCoCo emit UTF-8. Reject NUL bytes before parsing so
    # Expat cannot auto-detect UTF-16 and bypass the byte-level checks.
    if b'\x00' in data:
        raise ValueError('UNSUPPORTED_XML_ENCODING')
    if b'<!ENTITY' in data or not allow_doctype and b'<!DOCTYPE' in data:
        raise ValueError('UNSUPPORTED_XML_DECLARATION')
    return ET.fromstring(data), digest(data)


def junit_observations(paths, since, now):
    """Inspect leaf test cases, rather than trusting a suite's green count."""
    classes = {}
    errors = []
    for path in sorted(paths):
        try:
            report, report_hash = read_xml(path)
            if report.tag not in ('testsuite', 'testsuites'):
                raise ValueError('NOT_A_JUNIT_REPORT')
            # Validate aggregates as well as leaves. A passing child cannot
            # erase a failure declared by its parent report.
            for node in report.iter():
                if node.tag not in ('testsuite', 'testsuites'):
                    continue
                descendants = list(node.iter('testcase'))
                if not descendants and any(int(node.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')):
                    raise ValueError('MISSING_TEST_CASES')
                if 'tests' in node.attrib and int(node.attrib['tests']) != len(descendants):
                    raise ValueError('TEST_COUNT_MISMATCH')
                for attribute, terminal in (('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')):
                    if attribute in node.attrib and int(node.attrib[attribute]) != sum(case.find(terminal) is not None for case in descendants):
                        raise ValueError('TERMINAL_COUNT_MISMATCH')
            suites = list(report.iter('testsuite'))
            for suite in suites:
                cases = suite.findall('testcase')
                if not cases:
                    if not suite.findall('testsuite') and any(int(suite.get(key, '0')) for key in ('tests', 'failures', 'errors', 'skipped')):
                        raise ValueError('MISSING_TEST_CASES')
                    continue
                if not suite.findall('testsuite') and int(suite.get('tests', '-1')) != len(cases):
                    raise ValueError('TEST_COUNT_MISMATCH')
                try:
                    observed_at = timestamp(suite.get('timestamp', ''))
                    fresh = since is not None and since <= observed_at <= now
                except ValueError:
                    fresh = False
                outcomes = []
                for case in cases:
                    name, owner = case.get('name', ''), case.get('classname', '')
                    if not name or not owner or len(name) > 1000 or len(owner) > 500:
                        raise ValueError('MISSING_OR_UNBOUNDED_CASE_IDENTITY')
                    terminal = [node.tag for node in case if node.tag in ('failure', 'error', 'skipped')]
                    if len(terminal) > 1:
                        raise ValueError('CONFLICTING_CASE_OUTCOMES')
                    outcome = terminal[0].upper() if terminal else 'PASS'
                    outcomes.append((owner, name, outcome))
                for owner, name, outcome in outcomes:
                    classes.setdefault(owner, []).append({
                        'class_name': owner, 'name': name, 'outcome': outcome, 'current_run_timestamp': fresh,
                        'report_sha256': report_hash, 'report_name': path.name,
                    })
        except (OSError, ET.ParseError, ValueError) as error:
            # Do not copy a test's exception, stdout, credentials or payload into
            # the qualification report. Its original report remains a separate artifact.
            reason = str(error) if isinstance(error, ValueError) and str(error).isupper() else 'INVALID_JUNIT_REPORT'
            errors.append({'report_name': path.name, 'reason': reason})
    return classes, errors


def harness_observation(root, relative, classes, report_errors):
    source = (root / relative).read_bytes()
    item = {'harness': relative, 'source_sha256': digest(source), 'status': 'NOT_OBSERVED', 'cases': []}
    if not relative.endswith('Test.kt'):
        item['reason'] = 'EXTERNAL_COMMAND_OR_DEVICE_REPORT_REQUIRED'
        return item
    package = re.search(r'^package\s+([\w.]+)', source.decode(), re.M)
    if package is None:
        item['reason'] = 'TEST_CLASS_IDENTITY_UNAVAILABLE'
        return item
    owner = package[1] + '.' + Path(relative).stem
    item['test_class'] = owner
    expected_instrumented = '/androidTest/' in relative
    item['environment'] = 'ANDROID_INSTRUMENTATION' if expected_instrumented else 'JVM_TEST'
    # A local JVM report cannot stand in for a named device/instrumented test.
    if expected_instrumented:
        item['reason'] = 'DEVICE_HARNESS_REPORT_REQUIRED'
        return item
    cases = [case for name, values in classes.items() if name == owner or name.startswith(owner + '$') for case in values]
    item['cases'] = cases
    if not cases:
        item['reason'] = 'NAMED_TEST_CASES_NOT_REPORTED'
    elif report_errors:
        item['status'], item['reason'] = 'UNVERIFIED', 'REPORT_SET_HAS_PARSE_ERRORS'
    elif len({(case['class_name'], case['name']) for case in cases}) != len(cases):
        item['status'], item['reason'] = 'UNVERIFIED', 'DUPLICATE_TEST_CASES'
    elif any(not case['current_run_timestamp'] for case in cases):
        item['status'], item['reason'] = 'UNVERIFIED', 'CURRENT_RUN_EXECUTION_NOT_ESTABLISHED'
    elif any(case['outcome'] in ('FAILURE', 'ERROR') for case in cases):
        item['status'] = 'FAIL'
    elif all(case['outcome'] == 'SKIPPED' for case in cases):
        item['status'] = 'SKIPPED'
    elif any(case['outcome'] == 'SKIPPED' for case in cases):
        item['status'] = 'PARTIAL'
    else:
        item['status'] = 'PASS'
    return item


def instrumented_coverage(path, since, now, junit_current):
    if path is None or not path.is_file():
        return {'available': False, 'reason': 'INSTRUMENTED_REPORT_NOT_AVAILABLE'}
    try:
        report, report_hash = read_xml(path, allow_doctype=True, max_bytes=MAX_COVERAGE_REPORT_BYTES)
        if report.tag != 'report':
            raise ValueError('NOT_A_JACOCO_REPORT')
        sessions = report.findall('sessioninfo')
        if since is None or not junit_current or not sessions:
            raise ValueError('CURRENT_EXECUTION_NOT_ESTABLISHED')
        for session in sessions:
            started = datetime.fromtimestamp(int(session.attrib['start']) / 1000, timezone.utc)
            dumped = datetime.fromtimestamp(int(session.attrib['dump']) / 1000, timezone.utc)
            if not since <= started <= dumped <= now:
                raise ValueError('COVERAGE_SESSION_OUTSIDE_EXECUTION_WINDOW')
        totals = {kind: {'covered': 0, 'missed': 0} for kind in ('LINE', 'BRANCH')}
        source_files = set()
        for package in report.findall('package'):
            name = package.get('name', '')
            if name != APPLICATION_PREFIX.rstrip('/') and not name.startswith(APPLICATION_PREFIX):
                continue
            for source in package.findall('sourcefile'):
                key = (package.get('name'), source.get('name'))
                if key in source_files:
                    raise ValueError('DUPLICATE_COVERAGE_SOURCE')
                source_files.add(key)
                for kind, total in totals.items():
                    counters = [counter for counter in source.findall('counter') if counter.get('type') == kind]
                    if len(counters) > 1:
                        raise ValueError('DUPLICATE_COVERAGE_COUNTER')
                    for counter in counters:
                        for field in total:
                            value = int(counter.attrib[field])
                            if value < 0:
                                raise ValueError('NEGATIVE_COVERAGE_COUNTER')
                            total[field] += value
        if not source_files or totals['LINE']['covered'] == 0:
            raise ValueError('NO_EXECUTED_APPLICATION_LINES')
        for total in totals.values():
            denominator = total['covered'] + total['missed']
            total['percent'] = 100 * total['covered'] / denominator if denominator else None
        return {'available': True, 'report_sha256': report_hash, 'source_files': len(source_files), 'counters': totals, 'current_run_sessions': len(sessions),
                'scope': 'Application source files present in this JaCoCo report; excludes native, script and resource inventory.'}
    except (OSError, ET.ParseError, KeyError, ValueError, OverflowError) as error:
        if isinstance(error, ValueError) and str(error) == 'REPORT_TOO_LARGE':
            return {'available': False, 'reason': 'INSTRUMENTED_REPORT_TOO_LARGE', 'report_limit_bytes': MAX_COVERAGE_REPORT_BYTES}
        return {'available': False, 'reason': 'INVALID_OR_UNEXECUTED_INSTRUMENTED_REPORT'}


def collect(root, reports, since, now, coverage_path=None):
    mapping = catalog(root)
    classes, errors = junit_observations(reports, since, now)
    areas = []
    for area, paths in mapping.items():
        harnesses = [harness_observation(root, path, classes, errors) for path in paths]
        statuses = {harness['status'] for harness in harnesses}
        status = 'FAIL' if 'FAIL' in statuses else 'PASS' if statuses == {'PASS'} else 'PARTIAL' if 'PASS' in statuses or 'PARTIAL' in statuses else 'NOT_QUALIFIED'
        areas.append({'source_area': area, 'named_harness_status': status, 'harnesses': harnesses})
    all_cases = [case for cases in classes.values() for case in cases]
    junit_current = (
        bool(all_cases) and not errors
        and all(case['current_run_timestamp'] for case in all_cases)
        and len({(case['class_name'], case['name']) for case in all_cases}) == len(all_cases)
        and any(case['outcome'] != 'SKIPPED' for case in all_cases)
    )
    return {
        'schema_version': 1, 'kind': 'APP_AUDIT_SOURCE_HARNESS_OBSERVATIONS',
        'collected_at': now.isoformat(), 'execution_window_start': since.isoformat() if since else None,
        'source_inventory_sha256': digest((root / INVENTORY).read_bytes()),
        'historical_audit_mutation': False, 'grants_execution': False, 'promotes_audit_score': False,
        'scope': 'Only listed named assertions reported in the current execution window. Area PASS is not complete subsystem, live-service, model or device qualification.',
        'report_errors': errors, 'areas': areas, 'coverage': instrumented_coverage(coverage_path, since, now, junit_current),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--junit-dir', type=Path, default=ROOT / 'app/build/test-results/testDebugUnitTest')
    parser.add_argument('--started-at-file', type=Path)
    parser.add_argument('--coverage', type=Path)
    parser.add_argument('--output', type=Path, default=Path('app-audit-source-qualification.json'))
    args = parser.parse_args()
    try:
        since = timestamp(args.started_at_file.read_text().strip()) if args.started_at_file and args.started_at_file.is_file() else None
        reports = list(args.junit_dir.glob('TEST-*.xml'))
        if len(reports) > 4096:
            raise ValueError('JUnit report count exceeds the collection bound')
        result = collect(ROOT, reports, since, datetime.now(timezone.utc), args.coverage)
        try:
            runtime = subprocess.run(['java', '-version'], capture_output=True, text=True, timeout=10, check=True)
            java_version = (runtime.stderr or runtime.stdout).splitlines()[0][:200]
        except (OSError, subprocess.SubprocessError, IndexError):
            java_version = None
        result['execution_environment'] = {
            'os': platform.system(), 'architecture': platform.machine(),
            'collector_python': sys.version.split()[0], 'java_runtime': java_version,
            'github_run_id': os.environ.get('GITHUB_RUN_ID'),
            'github_run_attempt': os.environ.get('GITHUB_RUN_ATTEMPT'),
            'github_job': os.environ.get('GITHUB_JOB'),
            'scope': 'Collector and CI job environment; not Android device or remote-service identity.',
        }
        result['checkout'] = {
            'commit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
            'parents': subprocess.check_output(['git', 'show', '-s', '--format=%P', 'HEAD'], cwd=ROOT, text=True).split(),
            'tracked_files_clean': not subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=no'], cwd=ROOT, text=True).strip(),
            'identity_meaning': 'Observed checkout and source hashes; not independent APK or deployed-service attestation.',
        }
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps({'output': str(args.output), 'source_areas': len(result['areas']), 'reports': len(reports), 'report_errors': len(result['report_errors'])}))
        return bool(result['report_errors'])
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        parser.exit(1, f'Qualification collection failed: {error}\n')


if __name__ == '__main__':
    raise SystemExit(main())
