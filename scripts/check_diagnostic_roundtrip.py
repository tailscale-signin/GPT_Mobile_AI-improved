#!/usr/bin/env python3
"""Real Kotlin spool -> Python ingestion/restart/export -> Kotlin import/export.

Pass a classpath containing the compiled scripts/diagnostic-roundtrip entrypoint,
production gateway diagnostic sources and their Kotlin runtime dependencies.
The regular fast validation also checks that both schemas match their source.
"""
import argparse
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'gateway'))
from v14.diagnostic_events import DiagnosticSessionJournal, event_identity
from v14.diagnostic_schema import SCHEMA_DIGEST


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--kotlin-classpath', required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp)
        def kotlin(mode):
            subprocess.run(['java', '-Xmx256m', '-cp', args.kotlin_classpath,
                            'dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDiagnosticRoundTripKt', mode, tmp], check=True)
        kotlin('emit')
        expected = json.loads((path / 'expected.json').read_text())
        batches = sorted(path.glob('batch-*.json'), key=lambda p: int(p.stem.split('-')[1]))
        journal = DiagnosticSessionJournal(path/'gateway.db', now=lambda: 1800000000)
        session = None
        for file in batches:
            batch = json.loads(file.read_text())
            session = batch['session_id']
            journal.ingest('owner', batch)
        journal.close()
        journal = DiagnosticSessionJournal(path/'gateway.db', now=lambda: 1800000000)
        try:
            with zipfile.ZipFile(io.BytesIO(journal.export('owner', session))) as archive:
                rows = [json.loads(line) for line in archive.read('events.jsonl').splitlines()]
                assert json.loads(archive.read('manifest.json'))['schema_digest'] == SCHEMA_DIGEST
        finally:
            journal.close()
        assert [row['bounded_attributes'] for row in rows] == expected, 'Android -> gateway lost fields'
        # Replay the gateway's sanitized output as a distinct remote source so import
        # exercises its full sanitizer instead of the own-origin echo suppression.
        for index, row in enumerate(rows, 1):
            row.update(owner='gateway', origin_device_pseudonym='c'*32, origin_boot_id='d'*32,
                       origin_sequence=index, event_id=event_identity('c'*32, 'd'*32, index), gateway_ingest_sequence=index)
        for index in range(0, len(rows), 64):
            page = rows[index:index+64]
            (path/f'page-{index}.json').write_text(json.dumps(dict(schema_version=2, session_id=session,
                events=page, next_cursor=page[-1]['gateway_ingest_sequence'], last_ingest_sequence=len(rows))))
        kotlin('import')
        with zipfile.ZipFile(path/'phone-export.zip') as archive:
            actual = [json.loads(line) for line in archive.read('events.jsonl').splitlines()]
            manifest = json.loads(archive.read('manifest.json'))
        assert manifest['schema_digest'] == SCHEMA_DIGEST
        for owner in ('android', 'gateway'):
            assert [row['bounded_attributes'] for row in actual if row['owner'] == owner] == expected, owner + ' phone export lost fields'
        print(f'PASS: {len(expected)} approved values through Android -> gateway restart/export -> Android import/export; schema {SCHEMA_DIGEST}')


if __name__ == '__main__':
    main()
