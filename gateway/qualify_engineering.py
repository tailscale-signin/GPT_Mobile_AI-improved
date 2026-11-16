"""Run/read ten authorized production tasks from a fixed JSON manifest."""
import argparse
import json
from pathlib import Path

from benchmarks.engineering_qualification import run_manifest
from desktop.configuration import atomic_json
from desktop.conversation_client import Client


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True, help='Installed Gateway desktop configuration directory')
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--execute', action='store_true', help='Submit listed implementation jobs with their fixed request IDs')
    args = parser.parse_args()
    path = Path(args.manifest)
    if path.stat().st_size > 1024 * 1024:
        parser.error('Manifest exceeds 1 MiB')
    manifest = json.loads(path.read_text())
    client = Client(Path(args.root))
    identity = client.identity()
    client.expected_identity = identity
    try:
        report = run_manifest(client, manifest, execute=args.execute, progress=print)
    finally:
        # A later attempt reconciles the same request IDs if a response was lost.
        atomic_json(path, manifest)
    atomic_json(Path(args.output), report)
    print(str(report['passed']) + '/10 scenarios qualified. Re-run to inspect saved jobs after required operator actions.')
    return 0 if report['qualified'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
