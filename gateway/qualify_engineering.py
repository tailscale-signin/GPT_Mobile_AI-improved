"""Run/read ten authorized production tasks from a fixed JSON manifest."""
import argparse
import json
from pathlib import Path

from benchmarks.engineering_qualification import CONTINUOUS_SCHEMA, CONTINUOUS_SCENARIOS, run_manifest
from benchmarks import workspace_qualification as workspace
from desktop.configuration import atomic_json
from desktop.conversation_client import Client


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', help='Installed Gateway desktop configuration directory')
    parser.add_argument('--manifest')
    parser.add_argument('--output', required=True)
    parser.add_argument('--continuous-template', metavar='OWNER/REPO', help='Write an empty continuous engineering manifest')
    parser.add_argument('--workspace-template', metavar='OWNER/REPO', help='Create a coding qualification manifest for one model and executor')
    parser.add_argument('--execute', action='store_true', help='Submit listed implementation jobs with their fixed request IDs')
    args = parser.parse_args()
    if args.workspace_template:
        if args.execute or args.manifest or args.continuous_template:
            parser.error('Choose one template or an existing manifest')
        atomic_json(Path(args.output), {'schema': workspace.SCHEMA, 'test_repository': args.workspace_template,
                    'route': dict(executor='gateway', model='', profile_uid='', endpoint_identity='', environment=''),
                    'cases': [{'scenario': case} for case in workspace.CASES]})
        return 0
    if args.continuous_template:
        if args.execute or args.manifest:
            parser.error('Template generation cannot read or execute jobs')
        atomic_json(Path(args.output), {'schema': CONTINUOUS_SCHEMA, 'test_repository': args.continuous_template,
                    'cases': [{'scenario': case} for case in CONTINUOUS_SCENARIOS]})
        return 0
    if not args.root or not args.manifest:
        parser.error('Qualification requires --root and --manifest')
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
    print(f"{report['passed']}/{report['total']} scenarios qualified. Re-run to inspect saved jobs after required operator actions.")
    return 0 if report['qualified'] else 2


if __name__ == '__main__':
    raise SystemExit(main())
