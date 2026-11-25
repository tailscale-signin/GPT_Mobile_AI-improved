"""Join changed runtime lines, JaCoCo and fresh named JUnit results for the tested commit."""
import argparse
from functools import lru_cache
from collections import defaultdict
from datetime import datetime, timezone
from html import escape
import json
import os
from pathlib import Path
import re
import subprocess

from collect_app_audit_qualification import MAX_COVERAGE_REPORT_BYTES, current_coverage_sessions, junit_observations, read_xml, timestamp

RISK = re.compile(r'fallback|retry|retries|cancel|recover|requireVerifiedRetention', re.I)
HUNK = re.compile(r'^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@')
PACKAGE = re.compile(r'^\s*package\s+([\w.]+)', re.M)
APP = 'app/src/main/'
# A green test elsewhere in the feature does not satisfy these reviewed route contracts.
CONTRACTS = {
    'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/privacy/MaskedProviderSession.kt': {
        'if (state.requireVerifiedRetention)': 'dev.chungjungsoo.gptmobile.data.privacy.MaskedProviderCallTest.verifiedRetentionBlocksUnverifiedSessionBeforeOpeningAProvider',
    },
    'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/voice/MediaPipeVoicePhraseEngineFactory.kt': {
        'open(VoiceSpeechBackend.LITERT_KOKORO_CPU, ticket)': 'dev.chungjungsoo.gptmobile.data.voice.MediaPipeSpeechModelStoreTest.acceleratorStartupFallbackKeepsTheModelVoiceRateAndIncrementalMode',
    },
    'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/github/GitHubWorkspaceClient.kt': {
        'val recoveryObservationId = GitHubDispatchAccounting.started': 'dev.chungjungsoo.gptmobile.data.github.GitHubCooldownHttpTest.aRecoveryResponseReplacesAnOrphaned304sRateEvidence',
    },
}


def git(*args):
    return subprocess.check_output(['git', *args]).decode()


def changed_lines(base, head):
    paths = git('diff', '--name-only', '--diff-filter=AMCR', '-z', base, head, '--', APP)
    changed = {}
    for path in paths.split('\0'):
        if not path.endswith(('.kt', '.java')):
            continue
        hunks = git('diff', '--no-ext-diff', '--unified=0', base, head, '--', path)
        indexes = set()
        for match in filter(None, map(HUNK.match, hunks.splitlines())):
            start = int(match[1])
            indexes.update(range(start, start + int(match[2] or 1)))
        if indexes:
            changed[path] = indexes
    return changed


def measured_lines(report):
    measured = {}
    for package in report.findall('./package'):
        for source in package.findall('sourcefile'):
            key = (package.attrib['name'], source.attrib['name'])
            if key in measured:
                raise ValueError('Ambiguous JaCoCo source file: ' + '/'.join(key))
            measured[key] = {int(line.attrib['nr']): (int(line.attrib['ci']), int(line.get('cb', 0)), int(line.get('mb', 0)))
                             for line in source.findall('line')}
    return measured


@lru_cache(maxsize=2048)
def test_owner(candidate):
    if candidate.suffix in ('.kt', '.java') and candidate.is_file():
        package = PACKAGE.search(candidate.read_text())
        if package:
            return package[1] + '.' + candidate.stem
    return None


def summarize(root, changes, measured, cases, features):
    findings, gaps, file_tests = [], [], {}
    cases_by_owner = defaultdict(list)
    for case in cases:
        cases_by_owner[case.rpartition('.')[0]].append(case)
    totals = dict(changed=0, executable=0, hit=0, fully_covered_branches=0, partial_branches=0)
    for path, indexes in sorted(changes.items()):
        source = (root / path).read_text()
        package = PACKAGE.search(source)
        lines = source.splitlines()
        coverage = measured.get((package[1].replace('.', '/'), Path(path).name), {}) if package else {}
        matching = [f for f in features if path in f['paths']]
        test_paths = {test for f in matching for test in f['tests']}
        owners = {owner for test in test_paths if (owner := test_owner(root / test))}
        mapped = sorted(name for owner in owners for name in cases_by_owner.get(owner, ()))
        file_tests[path] = {'features': [f['id'] for f in matching], 'feature_test_paths': sorted(test_paths),
                            'passing_feature_tests': mapped}
        for index in sorted(indexes):
            code = lines[index-1].strip()
            counter = coverage.get(index)
            totals['changed'] += 1
            if counter is not None:
                hits, covered, missed = counter
                totals['executable'] += 1
                totals['hit'] += int(hits > 0)
                totals['fully_covered_branches'] += int(covered > 0 and missed == 0)
                totals['partial_branches'] += int(covered > 0 and missed > 0)
            if code.startswith(('//', '*', '/*', 'import ')):
                continue
            tags = sorted({m.group().lower() for m in RISK.finditer(code)})
            required = [case for needle, case in CONTRACTS.get(path, {}).items() if needle in code]
            if not tags and not required:
                continue
            status = 'unmeasured' if counter is None else 'missed' if counter[0] <= 0 else 'partial-branch' if counter[2] else 'executed'
            receipts = {case: case in cases for case in required}
            if required and (status != 'executed' or not all(receipts.values())):
                gaps.append({'path': path, 'line': index, 'line_status': status, 'required_tests': receipts})
            findings.append({'path': path, 'line': index, 'behavior': tags, 'line_status': status,
                             'covered_branches': None if counter is None else counter[1],
                             'missed_branches': None if counter is None else counter[2],
                             'required_tests': receipts,
                             'review': 'reviewed regression passed' if required and all(receipts.values()) and status == 'executed'
                             else 'reviewed regression missing coverage or test' if required
                             else 'no direct named regression linked'})
    return {'counts': totals, 'files': file_tests, 'changed_behavior': findings, 'missing_required_evidence': gaps}



def workflow_summary(result):
    rows = result['changed_behavior']
    missing = [row for row in rows if row['review'] != 'reviewed regression passed']
    missing.sort(key=lambda row: (not bool(row['required_tests']), row['line_status'] == 'executed', row['path'], row['line']))
    counts = result['counts']
    lines = ['### Changed Android behavior',
             f"{counts['hit']} of {counts['executable']} mapped executable changed lines ran. "
             f"{len(result['missing_required_evidence'])} reviewed contracts need evidence. "
             f"{len(missing)} changed behavior lines need coverage or a directly linked regression.", '']
    for row in missing[:20]:
        lines.append(f"- <code>{escape(row['path'])}#{row['line']}</code> · {row['line_status']} · {row['review']}")
    lines.extend(['', 'The changed-behavior artifact contains every changed behavior line and its associated test names.', ''])
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', required=True)
    parser.add_argument('--coverage', type=Path, required=True)
    parser.add_argument('--junit-dir', type=Path, action='append', required=True)
    parser.add_argument('--started-at-file', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--enforce', action='store_true')
    args = parser.parse_args()
    head = git('rev-parse', 'HEAD').strip()
    base = args.base or git('rev-parse', 'HEAD^').strip()
    if not re.fullmatch('[0-9a-f]{40}', base):
        parser.error('An exact base commit is required')
    dirty = subprocess.run(['git', 'diff', '--quiet', 'HEAD', '--', ':(glob)app/src/**/*.kt', ':(glob)app/src/**/*.java',
                            'core-domain/src', 'docs/architecture/feature-map.json']).returncode
    if dirty:
        parser.error('Coverage qualification requires the tested source and feature map to match HEAD')
    now, since = datetime.now(timezone.utc), None
    errors, observed, measured = [], {}, {}
    try:
        since = timestamp(args.started_at_file.read_text().strip())
        observed, errors = junit_observations(set(p for d in args.junit_dir for p in d.glob('*.xml')), since, now)
    except (OSError, ValueError) as exc:
        errors.append(str(exc))
    try:
        report, _ = read_xml(args.coverage, allow_doctype=True, max_bytes=MAX_COVERAGE_REPORT_BYTES)
        current_junit = any(case['current_run_timestamp'] for group in observed.values() for case in group)
        current_coverage_sessions(report, since, now, current_junit)
        measured = measured_lines(report)
    except (OSError, ValueError, SyntaxError, KeyError, OverflowError) as exc:
        errors.append(str(exc))
    cases = defaultdict(list)
    for owner, observations in observed.items():
        for record in observations:
            cases[owner + '.' + record['name'].removesuffix('()')].append(record)
    passed = {case for case, values in cases.items() if all(x['outcome'] == 'PASS' and x['current_run_timestamp'] for x in values)}
    features = json.loads(Path('docs/architecture/feature-map.json').read_text())['features']
    result = summarize(Path.cwd(), changed_lines(base, head), measured, passed, features)
    result.update(base=base, tested_commit=head, input_errors=errors, qualified_test_count=len(passed),
                  interpretation='JaCoCo is aggregate. Named passing tests are associated using the feature map or explicit route contracts. A hit cannot identify which individual test executed a line.')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, separators=(',', ':')) + '\n')
    print(json.dumps({**result['counts'], 'behavior_lines': len(result['changed_behavior']),
                      'missing_required_evidence': len(result['missing_required_evidence']), 'input_errors': len(errors)}))
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with Path(os.environ['GITHUB_STEP_SUMMARY']).open('a') as summary:
            summary.write(workflow_summary(result))
    if args.enforce and (errors or result['missing_required_evidence']):
        raise SystemExit('Changed behavior coverage is incomplete. Inspect the retained JSON for paths, lines and test names.')


if __name__ == '__main__':
    main()
