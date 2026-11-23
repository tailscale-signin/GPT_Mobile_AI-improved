"""Build an immutable disposable-repository fixture bundle for phone-only Actions.

This command only writes local files. It never creates a repository, pushes a
branch, installs dependencies or chooses a GitHub account. Publish its reviewed
bundle in a disposable repository and select that exact base SHA on the phone.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile

from benchmarks import fixtures
from benchmarks.contracts import digest
from benchmarks.external import environment_receipt, public_task
from benchmarks.settings import validate as validate_settings

SCHEMA = 'gateway.phone-benchmark-catalog.v1'
SUBMISSION_SCHEMA = 'gateway.phone-benchmark-submission.v1'
RESULT_SCHEMA = 'gateway.phone-benchmark-result.v1'
PREFIX = '.benchmark-phone/'
ATTEMPT = re.compile(r'phone_[a-f0-9]{32}')
SHA = re.compile(r'[a-f0-9]{40}')
COHORT = re.compile(r'[A-Za-z0-9_-]{8,96}')
CHECKOUT = '3d3c42e5aac5ba805825da76410c181273ba90b1'
UPLOAD = '043fb46d1a93c77aae656e7c1c64a875d1fc6a0a'


def json_bytes(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True, allow_nan=False).encode('utf-8')


def raw_digest(value):
    return hashlib.sha256(value).hexdigest()


def submission_path(ident):
    if not isinstance(ident, str) or ATTEMPT.fullmatch(ident) is None:
        raise ValueError('Use a fresh bounded phone submission identity')
    return PREFIX + 'submissions/' + ident + '.json'


def workflow_path(ident):
    submission_path(ident)
    return '.github/workflows/phone-benchmark-' + ident + '.yml'


def branch_name(ident, cohort):
    submission_path(ident)
    if not isinstance(cohort, str) or COHORT.fullmatch(cohort) is None:
        raise ValueError('Choose a bounded disposable phone cohort')
    return 'benchmark/phone/' + cohort + '/' + ident


def render_workflow(template, ident, base_sha, branch):
    submission_path(ident)
    if not isinstance(base_sha, str) or SHA.fullmatch(base_sha) is None:
        raise ValueError('Use an immutable fixture base revision')
    if not isinstance(branch, str) or not branch.startswith('benchmark/phone/'):
        raise ValueError('Use the owned disposable branch')
    parts = branch.split('/')
    if len(parts) != 4 or branch != branch_name(ident, parts[2]):
        raise ValueError('The branch must belong to this phone attempt')
    result = template
    for token, value in (('__BENCHMARK_SUBMISSION_ID__', ident), ('__BENCHMARK_BASE_SHA__', base_sha), ('__BENCHMARK_BRANCH__', branch)):
        if token not in result:
            raise ValueError('The trusted workflow template is incomplete')
        result = result.replace(token, value)
    if '__BENCHMARK_' in result:
        raise ValueError('The trusted workflow template has unsupported placeholders')
    return result


def workflow_template(pack, image, native_label='gateway-native-benchmark'):
    if pack not in {'python', 'repository'} or re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', native_label) is None:
        raise ValueError('Invalid phone runtime selection')
    validate_settings({'sandbox_image': image})
    runner = json.dumps(['self-hosted', 'linux', native_label]) if pack == 'repository' else 'ubuntu-24.04'
    preparation = ("      - name: Prepare pinned Python evaluator\n        continue-on-error: true\n        run: docker pull '" + image + "'\n") if pack == 'python' else ''
    return f'''name: Phone benchmark __BENCHMARK_SUBMISSION_ID__
on:
  push:
    branches: ['__BENCHMARK_BRANCH__']
permissions:
  contents: read
concurrency:
  group: phone-benchmark-__BENCHMARK_SUBMISSION_ID__
  cancel-in-progress: false
jobs:
  verify:
    name: Independent phone benchmark
    runs-on: {runner}
    timeout-minutes: 20
    steps:
      - name: Read immutable trusted fixture base
        uses: actions/checkout@{CHECKOUT}
        with:
          ref: '__BENCHMARK_BASE_SHA__'
          path: trusted
          persist-credentials: false
          fetch-depth: 1
      - name: Read submitted proposal revision
        uses: actions/checkout@{CHECKOUT}
        with:
          ref: ${{{{ github.sha }}}}
          path: submitted
          persist-credentials: false
          fetch-depth: 1
      - name: Prepare trusted grader dependencies
        run: |
          python3 -m venv .phone-benchmark-env
          .phone-benchmark-env/bin/python -m pip install --require-hashes -r trusted/.benchmark-phone/runtime/requirements.txt
{preparation}      - name: Grade frozen proposal
        run: |
          .phone-benchmark-env/bin/python trusted/.benchmark-phone/runtime/benchmarks/phone_ci.py --trusted-root trusted --submitted-root submitted --submission-id '__BENCHMARK_SUBMISSION_ID__' --base-sha '__BENCHMARK_BASE_SHA__' --output phone-benchmark-result/result.json
      - name: Upload benchmark result
        if: always()
        uses: actions/upload-artifact@{UPLOAD}
        with:
          name: phone-benchmark-__BENCHMARK_SUBMISSION_ID__
          path: phone-benchmark-result/result.json
          if-no-files-found: error
          retention-days: 7
'''


def runtime_files(root):
    root = Path(root)
    files = {}
    for path in root.rglob('*.py'):
        relative = path.relative_to(root)
        if any(part.startswith('.') or part in {'tests', '__pycache__'} for part in relative.parts):
            continue
        if path.is_symlink():
            raise ValueError('Runtime source cannot be a filesystem link')
        files[relative.as_posix()] = path.read_bytes()
    files['requirements.txt'] = (root / 'requirements.txt').read_bytes()
    if sum(map(len, files.values())) > 16 * 1024 * 1024:
        raise ValueError('The trusted runtime exceeds its provisioning bound')
    return files


def verify_source_revision(repo, revision, observed):
    if SHA.fullmatch(revision or '') is None:
        raise ValueError('A pinned source commit is required')
    archive = subprocess.run(['git', 'archive', revision, 'gateway'], cwd=repo, check=True,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30).stdout
    if len(archive) > 32 * 1024 * 1024:
        raise ValueError('The pinned source archive exceeds its bound')
    expected = {}
    with tarfile.open(fileobj=io.BytesIO(archive)) as bundle:
        for member in bundle:
            parts = Path(member.name).parts
            relative = Path(*parts[1:])
            if (not member.isfile() or parts[0] != 'gateway'
                    or any(part.startswith('.') or part in {'tests', '__pycache__'} for part in relative.parts)
                    or not (relative.suffix == '.py' or relative.as_posix() == 'requirements.txt')):
                continue
            expected[relative.as_posix()] = bundle.extractfile(member).read()
    if expected != observed:
        raise ValueError('Provision from an exact clean source revision; runtime source differs from the pinned commit')


def build_bundle(*, source_commit, python_image, repository_image='', seeds=(17,), seconds=30,
                 native_label='gateway-native-benchmark', sources=None):
    if SHA.fullmatch(source_commit or '') is None or not seeds or len(seeds) > 16 or len(set(seeds)) != len(seeds):
        raise ValueError('Use one pinned source commit and one to sixteen distinct seeds')
    if any(type(seed) is not int or not 0 <= seed <= 2**31 - 1 for seed in seeds):
        raise ValueError('Use bounded fixture seeds')
    settings = validate_settings({'sandbox_image': python_image, 'repository_image': repository_image,
                                  'sandbox_seconds': seconds, 'repair_attempts': 0})
    if not python_image:
        raise ValueError('Choose an immutable Python image before provisioning')
    source_files = runtime_files(Path(__file__).resolve().parents[1]) if sources is None else sources
    files = {PREFIX + 'runtime/' + path: content for path, content in source_files.items()}
    catalog = {'schema': SCHEMA, 'sourceCommit': source_commit, 'seeds': list(seeds), 'packs': {},
               'submissionSchema': SUBMISSION_SCHEMA, 'resultSchema': RESULT_SCHEMA,
               'artifactNamePrefix': 'phone-benchmark-', 'resultFile': 'result.json',
               'requiredJobName': 'Independent phone benchmark',
               'requiredSteps': ['Grade frozen proposal', 'Upload benchmark result'],
               'modelIdentityScope': 'AUTHENTICATED_CLIENT_OBSERVATION'}
    for pack in ('python', 'repository') if repository_image else ('python',):
        if pack == 'repository':
            from benchmarks import repository_tasks
            suite = repository_tasks.task_manifest
        else:
            suite = fixtures.suite
        image = repository_image if pack == 'repository' else python_image
        template_path = PREFIX + 'workflows/' + pack + '.yml'
        template = workflow_template(pack, image, native_label).encode()
        files[template_path] = template
        pack_value = {'presets': {preset: [task['id'] for task in suite(preset, seeds[0])] for preset in ('quick', 'standard', 'deep')},
                      'seeds': list(seeds), 'workflowTemplatePath': template_path,
                      'workflowTemplateDigest': raw_digest(template),
                      'fixturePathTemplate': PREFIX + 'fixtures/' + pack + '/{seed}/{task_id}.json',
                      'tasksBySeed': {}, 'settings': settings}
        for seed in seeds:
            metadata = {}
            for task in suite('deep', seed):
                environment = environment_receipt(settings, pack, task['graderDigest'])
                descriptor = dict(public_task(task, seed), **environment)
                path = pack_value['fixturePathTemplate'].replace('{seed}', str(seed)).replace('{task_id}', task['id'])
                raw = json_bytes(descriptor)
                files[path] = raw
                metadata[task['id']] = {'fixtureDigest': task['taskDigest'], 'descriptorDigest': raw_digest(raw)}
                pack_value.update(graderDigest=task['graderDigest'], **environment)
            pack_value['tasksBySeed'][str(seed)] = metadata
        catalog['packs'][pack] = pack_value
    manifest = {path: raw_digest(raw) for path, raw in files.items()}
    catalog.update(bundleManifest=manifest, bundleDigest=digest(manifest))
    files[PREFIX + 'catalog.json'] = json_bytes(catalog)
    return files, catalog


def write_bundle(destination, files):
    destination = Path(destination)
    if destination.name != '.benchmark-phone' or destination.exists():
        raise ValueError('Choose a new .benchmark-phone directory; existing fixture bundles are immutable')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='phone-benchmark-provision-', dir=destination.parent) as temporary:
        stage = Path(temporary) / '.benchmark-phone'
        stage.mkdir()
        for name, raw in files.items():
            if not name.startswith(PREFIX) or '..' in Path(name).parts:
                raise ValueError('Invalid fixture bundle path')
            path = stage / name[len(PREFIX):]
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)
        os.replace(stage, destination)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True, help='New .benchmark-phone directory in a disposable repository checkout')
    parser.add_argument('--source-revision', required=True, help='Exact clean source commit for this grader checkout')
    parser.add_argument('--python-image', required=True, help='Python image pinned by registry sha256')
    parser.add_argument('--repository-image', default='', help='Optional locally installed Kotlin/SQLite/PyYAML runtime image pinned by sha256')
    parser.add_argument('--native-runner-label', default='gateway-native-benchmark')
    parser.add_argument('--seeds', default='17')
    parser.add_argument('--seconds', type=int, default=30)
    args = parser.parse_args(argv)
    root = Path(__file__).resolve().parents[1]
    sources = runtime_files(root)
    verify_source_revision(root.parent, args.source_revision, sources)
    files, catalog = build_bundle(source_commit=args.source_revision, python_image=args.python_image,
        repository_image=args.repository_image, native_label=args.native_runner_label,
        seeds=tuple(int(seed) for seed in args.seeds.split(',')), seconds=args.seconds, sources=sources)
    write_bundle(args.output, files)
    print(json.dumps({'path': str(Path(args.output).resolve()), 'bundleDigest': catalog['bundleDigest'],
                      'sourceCommit': catalog['sourceCommit'], 'files': len(files),
                      'next': 'Review and commit this bundle in the disposable repository, then select its exact base SHA on the phone.'}))


if __name__ == '__main__':
    main()
