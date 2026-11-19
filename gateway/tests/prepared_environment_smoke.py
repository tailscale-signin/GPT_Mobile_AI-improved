"""Prepare an actual profile image and execute offline against a fresh pinned checkout."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time

GATEWAY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(GATEWAY))
from desktop import development_profiles as profiles
from desktop import repository_validation as setup
from v14.engineering_validation import checkout_container_user, materialize_checkout


def git(cwd, *args):
    return subprocess.check_output(['git', *args], cwd=cwd, text=True).strip()


def inputs(profile, root):
    if profile == profiles.ANDROID:
        return GATEWAY.parent, [['gateway-gradle', ':app:testDebugUnitTest', '--tests',
                                 'dev.chungjungsoo.gptmobile.data.github.GatewayGraphTest'],
                                ['gateway-gradle', ':app:assembleDebug']]
    repo = root / 'fixture'
    repo.mkdir()
    if profile == profiles.PYTHON:
        (repo / 'test_answer.py').write_text('def test_answer():\n    assert 6 * 7 == 42\n')
    else:
        (repo / 'package.json').write_text(json.dumps({'name':'fixture', 'version':'1.0.0', 'scripts':{'test':'node test.js'}}))
        (repo / 'package-lock.json').write_text(json.dumps({'name':'fixture','version':'1.0.0','lockfileVersion':3,
            'packages':{'':{'name':'fixture','version':'1.0.0'}}}))
        (repo / 'test.js').write_text('require("node:assert/strict").equal(6 * 7, 42)\n')
    git(repo, 'init', '-q')
    git(repo, 'add', '.')
    git(repo, '-c', 'user.name=Qualification', '-c', 'user.email=qualification@localhost', 'commit', '-qm', 'Profile fixture')
    return repo, [['python3','-m','pytest','-q']] if profile == profiles.PYTHON else [['gateway-node','test']]


def build_smoke_image(arguments, *, cancel, timeout, label):
    # Print the failing Dockerfile step for the CI checkout. Use the same local
    # daemon and empty credential context as the desktop image builder.
    arguments = ['--progress=plain' if arg == '--quiet' else arg for arg in arguments]
    with setup.host_command(['docker', *arguments]) as command:
        subprocess.run(command, env=setup.host_environment(Path.cwd()), timeout=timeout,
                       stdin=subprocess.DEVNULL, check=True)


def run(profile, report):
    receipt = dict(profile=profile, status='STARTED', commands=[])
    try:
        with tempfile.TemporaryDirectory(prefix='qualification-') as scratch:
            root = Path(scratch)
            source, commands = inputs(profile, root)
            head = git(source, 'rev-parse', 'HEAD')
            mirror = root / 'mirror'
            git(root, 'clone', '-q', '--bare', str(source), str(mirror))
            base = setup.pin_image(setup.executable('docker'), profiles.IMAGES[profile], prepare=True)
            receipt.update(source_sha=head, base_image=base)
            prepared = profiles.prepare_image(profile, base, mirror, head, None, run_build=build_smoke_image)
            receipt['image'] = prepared['image_id']
            checkout = materialize_checkout({'data':{'head_sha':head}, 'deadline':time.time()+1800}, mirror, [], root, None)
            for path in [checkout, *checkout.rglob('*')]:
                path.chmod(0o777 if path.is_dir() or path.stat().st_mode & 0o111 else 0o666)
            for command in commands:
                result = subprocess.run(['docker','run','--rm','--network=none','--cap-drop=ALL','--read-only',
                    '--security-opt=no-new-privileges','--user=' + checkout_container_user(),'--env=HOME=/tmp','--env=GITHUB_SHA=' + head,
                    '--tmpfs=/tmp:rw,nosuid,nodev,size=3g','--mount=type=bind,source='+str(checkout)+',target=/workspace',
                    '--workdir=/workspace',prepared['image_id'],*command], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                    timeout=2400)
                receipt['commands'].append(dict(argv=command, exit_code=result.returncode, output_sha256=hashlib.sha256(result.stdout).hexdigest()))
                print(result.stdout[-8000:].decode(errors='replace'), flush=True)
                result.check_returncode()
            if profile == profiles.ANDROID:
                files = list(checkout.glob('app/build/outputs/apk/debug/*.apk'))
                tests = list(checkout.glob('app/build/test-results/testDebugUnitTest/TEST-*GatewayGraphTest.xml'))
                if not files or not tests:
                    raise ValueError('The requested APK or targeted test report is missing from the candidate checkout')
                import xml.etree.ElementTree as ET
                cases = [c for test in tests for c in ET.parse(test).getroot().iter('testcase')]
                if not cases or any(list(case.findall('skipped')) + list(case.findall('error')) + list(case.findall('failure')) for case in cases):
                    raise ValueError('Targeted Android regression must run and pass')
                receipt['artifacts'] = {}
                for artifact in files + tests:
                    with artifact.open('rb') as body:
                        receipt['artifacts'][str(artifact.relative_to(checkout))] = hashlib.file_digest(body, 'sha256').hexdigest()
                receipt['passed_test_cases'] = len(cases)
            receipt['status'] = 'PASSED'
    except Exception as error:
        receipt.update(status='FAILED', error_type=type(error).__name__, reason=str(error)[:800])
        raise
    finally:
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(receipt, indent=2)+'\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('profile', choices=['android','python','node'])
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    run({'android':profiles.ANDROID, 'python':profiles.PYTHON, 'node':profiles.NODE}[args.profile], args.output)
