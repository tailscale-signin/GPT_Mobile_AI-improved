#!/usr/bin/env python3
"""Generate a small path-based index; no private source content or credentials."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GROUPS = {
    'github': ('app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/github', 'app/src/test/kotlin/dev/chungjungsoo/gptmobile/data/github'),
    'engineering': ('app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/agent/tool',),
    'workspace_ui': ('app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/workspace',),
    'gateway': ('gateway', 'authentication'),
    'local_inference': ('app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/localruntime',),
    'workflows': ('.github/workflows',),
}


def document():
    return {'schema_version': 1, 'generated_by': 'python3 scripts/generate_architecture_index.py',
            'validation': {'fast': 'bash scripts/validate-local.sh fast', 'full': 'bash scripts/validate-local.sh full'},
            'subsystems': {name: {'roots': list(paths), 'entry_files': sorted(str(p.relative_to(ROOT)) for path in paths for p in (ROOT / path).glob('*') if p.is_file() and p.suffix in {'.kt', '.py', '.yml'})} for name, paths in GROUPS.items()}}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    path = ROOT / 'docs/architecture/repository-index.json'
    expected = json.dumps(document(), indent=2) + '\n'
    if args.check:
        if not path.exists() or path.read_text() != expected:
            raise SystemExit('Architecture index stale: run python3 scripts/generate_architecture_index.py')
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(expected)
