"""Opt-in CI integration: real Docker and recommended source commands, not mocked tests."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from threading import Event
import xml.etree.ElementTree as ET
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from benchmarks.settings import load_settings, save_settings
from desktop import repository_validation as setup
from desktop.analysis_export import NS, export_result
from desktop.analysis_panel import analyze
from v14.operator_schedule import OperatorSchedule
from v14.scheduled_validation import KIND, run_tests
from desktop.configuration import environment, validate as validate_configuration
from desktop.validation_policy import SETTING
from v14.engineering_validation import validate


def verify_operator_work(root, pinned_image, head):
    initial = load_settings(root)
    save_settings(root, dict(initial, sandbox_image=pinned_image), expected=initial)
    first = analyze(root, 'def handle(data):\n    return [[name, value * 2] for name, value in data]', '[["Review",3]]', Event())
    revised = analyze(root, 'def handle(data):\n    return [[name, value + 1] for name, value in data]', first, Event())
    if json.loads(revised) != [['Review', 7]]:
        raise ValueError('Revising the container result changed the wrong value')
    sheet = export_result(revised, '.ods')
    with zipfile.ZipFile(io.BytesIO(sheet)) as archive:
        rows = ET.fromstring(archive.read('content.xml')).findall('.//table:table-cell', NS)
        if len(rows) != 2 or rows[1].get('{%s}value' % NS['office']) != '7':
            raise ValueError('Spreadsheet export did not preserve the calculated number')
    clock = [100]
    store = OperatorSchedule(root / 'data', lambda: clock[0])
    key = store.add('Managed repository qualification', KIND, 1, 0, setup.DEFAULT_REPOSITORY)
    clock[0] = 160
    run_tests(store, Event())
    # Reopen the actual schedule store and exercise a second service tick.
    reopened = OperatorSchedule(root / 'data', lambda: clock[0])
    run_tests(reopened, Event())
    history = reopened.test_history(key)
    if len(history) != 1 or history[0][1]['status'] != 'PASSED' or history[0][1]['source_sha'] != head:
        raise ValueError('Scheduled validation did not save one passing exact-revision receipt')
    return {'status': 'PASSED', 'image': pinned_image, 'scheduled_validation': history[0][1],
            'analysis_revisions': 2, 'spreadsheet_sha256': hashlib.sha256(sheet).hexdigest()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    checkout = Path(__file__).resolve().parents[2]
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=checkout, text=True).strip()
    receipt = {'source_sha': head, 'status': 'BLOCKED', 'scope': 'recommended source checks, not feature acceptance'}
    try:
        with tempfile.TemporaryDirectory(prefix='gateway-managed-smoke-') as directory:
            root = Path(directory)
            mirror = root / 'source.git'
            # A local mirror preserves the exact synthetic PR merge commit, not a moving remote branch.
            subprocess.run(['git', 'clone', '--quiet', '--mirror', '--no-hardlinks', str(checkout), str(mirror)], check=True)
            subprocess.run(['git', '--git-dir=' + str(mirror), 'remote', 'set-url', 'origin',
                            'https://github.com/' + setup.DEFAULT_REPOSITORY + '.git'], check=True)
            options = setup.defaults(root)
            options['mirror'] = str(mirror)
            docker = setup.executable('docker')
            setup.check_docker(docker)
            options['image'] = setup.pin_image(docker, options['image_source'], prepare=True)
            revision = setup.read_settings(root)[1]
            # Check uses the real mirror, Docker daemon and exact locally installed digest.
            readiness = setup.setup(root, options, revision, prepare=False)
            setup.save_snapshot(root, setup.DEFAULT_REPOSITORY, {
                'mirror': str(mirror), 'image': options['image'], 'commands': options['commands'],
                'timeout_seconds': options['timeout_seconds'], 'allowed_checks': options['allowed_checks'],
                'acceptance_map': []}, revision)
            first = environment(root, validate_configuration({}))
            previous = os.environ.get(SETTING)
            os.environ[SETTING] = first[SETTING]
            try:
                result = validate({'request': {'repository': setup.DEFAULT_REPOSITORY}, 'data': {'head_sha': head}}, [])
                receipt.update(readiness=readiness, validation=result)
                if result['status'] == 'PASSED':
                    receipt['operator_work'] = verify_operator_work(root, options['image'], head)
            finally:
                if previous is None:
                    os.environ.pop(SETTING, None)
                else:
                    os.environ[SETTING] = previous
            receipt.update(status=result['status'], readiness=readiness, validation=result)
            if result['status'] != 'PASSED':
                raise SystemExit('Managed source validation failed; inspect the retained command receipts.')
    except Exception as error:
        # Never serialize exception messages from host/network tooling.
        receipt['error_type'] = type(error).__name__
        raise
    finally:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    print('Managed source validation passed for ' + head)


if __name__ == '__main__':
    main()
