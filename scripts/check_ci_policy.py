#!/usr/bin/env python3
"""Dependency-free checks for workflow discovery, Action pins and required gate."""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def check_text(text, path):
    errors = []
    for line, action in enumerate(text.splitlines(), 1):
        match = re.search(r'\buses:\s*[\"\']?([^\s\"\']+)', action)
        if match and not match[1].startswith('./') and not re.fullmatch(r'[^@]+@[a-f0-9]{40}', match[1]):
            errors.append(f'{path}:{line}: Action must be pinned to a full commit SHA')
    if path.endswith('.yml') and '/workflows/' in path:
        if not re.search(r'^permissions:', text, re.M):
            errors.append(f'{path}: explicit workflow permissions required')
        if re.search(r'^\s*permissions:\s*write-all', text, re.M):
            errors.append(f'{path}: write-all is prohibited')
        if not re.search(r'^concurrency:', text, re.M):
            errors.append(f'{path}: concurrency policy required')
        for job in re.split(r'^  [\w-]+:\s*$', text.split('\njobs:', 1)[-1], flags=re.M)[1:]:
            if re.search(r'^    runs-on:', job, re.M) and not re.search(r'^    timeout-minutes:', job, re.M):
                errors.append(f'{path}: runner job requires timeout-minutes')
            manual_android = 'uses: android-actions/setup-android@' in job or 'android-device]' in job
            if manual_android:
                provision = job.find('python3 scripts/provision_memory_model.py')
                build = re.search(r'(?m)^\s*(?:-\s*)?(?:run:\s*)?(?:\./gradlew\b|bash scripts/(?:run_gradle|validate)|python3 scripts/build_metrics)', job)
                if provision < 0 or (build and provision > build.start()):
                    errors.append(f'{path}: Android jobs must provision the verified memory model before Gradle')
    return errors


def main():
    errors = []
    for path in sorted((ROOT / '.github').rglob('*.yml')):
        relative = path.relative_to(ROOT).as_posix()
        if '/workflows/' in relative and path.parent != ROOT / '.github/workflows':
            errors.append(f'{relative}: workflow entry point must be directly in .github/workflows')
        if '/workflows/' in relative or '/actions/' in relative:
            errors.extend(check_text(path.read_text(), relative))
    for path in (ROOT / '.github/workflows').rglob('*.yaml'):
        errors.append(f'{path.name}: use .yml consistently so the policy checks cover every workflow')
    canonical = (ROOT / '.github/workflows/pr-validation.yml').read_text()
    for required in ('  merge_group:', '  push:', '    name: Required verification', '    if: always()', 'VERIFICATION_NEEDS: ${{ toJSON(needs) }}'):
        if required not in canonical:
            errors.append(f'pr-validation.yml missing {required.strip()}')
    if 'paths-ignore:' in canonical or re.search(r'^    paths:', canonical, re.M):
        errors.append('Required verification must run for every change')
    print('\n'.join(errors) if errors else 'CI policy passed')
    return bool(errors)


if __name__ == '__main__':
    sys.exit(main())
