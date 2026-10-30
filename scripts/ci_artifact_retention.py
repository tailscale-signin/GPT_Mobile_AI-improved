#!/usr/bin/env python3
"""Preview first. Delete only reviewed, revalidated artifacts on explicit --apply."""
import argparse
import json
import os
import time
from pathlib import Path
from ci_observability import GitHub, timestamp

POLICY = {'app-debug': 14, 'pr-validation-logs': 7, 'android-lint-reports': 14,
          'jacoco-coverage-report': 14, 'release-performance': 14,
          'unsigned-release-performance': 14, 'ci-observability': 7,
          'acceptance-0950-emulator': 14}


def plan(artifacts, now, quota_bytes=5_000_000_000):
    newest = {}
    for row in artifacts:
        if row['name'] in POLICY and not row.get('expired'):
            key = (row['name'], row.get('workflow_run', {}).get('head_branch'))
            if key not in newest or row['created_at'] > newest[key]['created_at']:
                newest[key] = row
    protected = {row['id'] for row in newest.values()}
    total = sum(row['size_in_bytes'] for row in artifacts)
    candidates = []
    for row in sorted(artifacts, key=lambda item: item['created_at']):
        if row['name'] not in POLICY or row['id'] in protected:
            continue
        age = (now - timestamp(row['created_at'])) / 86400
        reason = ('expired' if row.get('expired') else 'retention' if age >= POLICY[row['name']]
                  else 'quota' if total > quota_bytes and age >= 7 else None)
        if reason:
            candidates.append({key: row[key] for key in ('id', 'name', 'created_at', 'size_in_bytes')} | {'reason': reason})
            total -= row['size_in_bytes']
    return {'candidates': candidates, 'projected_bytes': total, 'quota_bytes': quota_bytes,
            'quota_satisfied': total <= quota_bytes, 'protected_latest_artifact_ids': sorted(protected)}


def apply(api, proposal, checkpoint=lambda: None):
    deleted = []
    proposal['deleted'] = deleted
    for row in proposal['candidates']:
        current = api.request('/actions/artifacts/' + str(int(row['id'])))
        if any(current.get(key) != row[key] for key in ('id', 'name', 'created_at', 'size_in_bytes')):
            raise ValueError('Artifact identity changed; generate a fresh plan')
        proposal['pending_delete'] = row['id']
        checkpoint()
        api.request('/actions/artifacts/' + str(int(row['id'])), method='DELETE')
        deleted.append(row['id'])
        proposal.pop('pending_delete', None)
        checkpoint()
    return deleted


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--output', default='artifact-retention-plan.json')
    args = parser.parse_args()
    api = GitHub(os.environ['GITHUB_REPOSITORY'], os.environ['GH_TOKEN'])
    artifacts, complete = api.pages('/actions/artifacts', 'artifacts', limit=10)
    proposal = plan(artifacts, time.time())
    proposal['inventory_complete'] = complete
    proposal['repository'] = api.repository
    Path(args.output).write_text(json.dumps(proposal, indent=2) + '\n')
    if args.apply:
        if not complete:
            raise ValueError('Incomplete inventory cannot authorize cleanup')
        proposal['deleted'] = apply(api, proposal, lambda: Path(args.output).write_text(json.dumps(proposal, indent=2) + '\n'))
        Path(args.output).write_text(json.dumps(proposal, indent=2) + '\n')
    print(f"Retention plan: {len(proposal['candidates'])} candidates; applied={args.apply}")


if __name__ == '__main__':
    try:
        main()
    except Exception:
        raise SystemExit('Artifact retention failed. No automatic delete retry was attempted; reconcile the plan before retrying.')
