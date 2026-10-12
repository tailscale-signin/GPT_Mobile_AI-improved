#!/usr/bin/env python3
"""Fail on newly introduced tool definition producers without an audited capture policy."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

def sources():
    base = ROOT / 'app/src/main/kotlin/dev/chungjungsoo/gptmobile'
    result = set()
    for folder in ['data/agent/tool', 'data/marketplace']:
        for path in (base / folder).glob('*.kt'):
            text = path.read_text()
            if 'AgentToolDefinition(' in text or re.search(r'override\s+val\s+definition', text):
                result.add(str(path.relative_to(ROOT)))
    return result

def validate():
    path = ROOT / 'docs/audits/tool-statistics-coverage.json'
    manifest = json.loads(path.read_text())
    expected = {row['source'] for row in manifest['producers']}
    actual = sources()
    if expected != actual:
        raise ValueError('Tool statistics coverage needs review: ' + str(sorted(expected ^ actual)))
    for row in manifest['producers']:
        if not all(row.get(key) for key in ['canonicalOwner', 'capture', 'outcome', 'probe', 'configuration']):
            raise ValueError('Incomplete coverage mapping: ' + row['source'])
    print(f'{len(actual)} tool definition producers have explicit statistics coverage policies. This is not an end-to-end execution test.')

if __name__ == '__main__':
    validate()
