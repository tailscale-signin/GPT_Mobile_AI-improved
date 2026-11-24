"""Run/read ten authorized production tasks from a fixed JSON manifest."""
import argparse
import json
from pathlib import Path

from benchmarks.engineering_qualification import CONTINUOUS_SCHEMA, CONTINUOUS_SCENARIOS, PRODUCTION_SCHEMA, SCENARIOS, run_manifest
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
    parser.add_argument('--workspace-profile', choices=('local', 'remote'), default='local', help='Exercise the selected inference route in the workspace template')
    parser.add_argument('--execute', action='store_true', help='Submit listed implementation jobs with their fixed request IDs')
    parser.add_argument('--intelligence-template', metavar='OWNER/REPO', help='Prepare the ten real GitHub engineering scenarios')
    parser.add_argument('--compare-intelligence', nargs=2, metavar=('LEFT', 'RIGHT'), help='Compare two measured production reports')
    args = parser.parse_args()
    if args.intelligence_template or args.compare_intelligence:
        if args.execute or args.manifest or args.workspace_template or args.continuous_template or (args.intelligence_template and args.compare_intelligence):
            parser.error('Choose a single template or comparison')
        if args.compare_intelligence:
            from benchmarks.github_intelligence import compare
            paths = [Path(p) for p in args.compare_intelligence]
            try:
                if any(p.stat().st_size > 4 * 1024 * 1024 for p in paths):
                    parser.error('A qualification report exceeds 4 MiB')
                result = compare(*(json.loads(p.read_bytes()) for p in paths))
            except (OSError, KeyError, TypeError, ValueError) as error:
                parser.error(str(error))
        else:
            result = {'schema': PRODUCTION_SCHEMA, 'test_repository': args.intelligence_template,
                      'disposable': True, 'cohort': '', 'expected_route': {key: '' for key in
                      ('model', 'profile_uid', 'profile_revision', 'binding_id', 'endpoint_identity', 'base_sha', 'settings_digest')},
                      'cases': [{'scenario': case} for case in SCENARIOS]}
        atomic_json(Path(args.output), result)
        return 0
    if args.workspace_template:
        if args.execute or args.manifest or args.continuous_template:
            parser.error('Choose one template or an existing manifest')
        atomic_json(Path(args.output), {'schema': workspace.SCHEMA, 'test_repository': args.workspace_template,
                    'route': dict(executor='gateway', model='', profile_uid='', endpoint_identity='', environment=''),
                    'cases': [{'scenario': case} for case in (workspace.LOCAL_CASES if args.workspace_profile == 'local' else workspace.CASES)]})
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
