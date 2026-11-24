"""Grade an operator-supplied answer using the Gateway's public task and executable grader."""
import argparse
from contextlib import closing
import json
from pathlib import Path
import re
import sqlite3
import uuid

from benchmarks.contracts import content_digest, digest
from desktop.conversation_client import Client, ClientError, scope_key


API = '/gateway/benchmarks'
TASK_KEYS = ('id', 'seed', 'taskPack', 'prompt', 'outputSchema', 'taskDigest', 'graderDigest', 'environmentDigest')
LABELS = ('ChatGPT', 'Codex')


def public_task(client, task_id, seed, pack):
    if not re.fullmatch(r'[a-z0-9-]{1,100}', task_id):
        raise ValueError('Invalid task ID')
    row = client.request('GET', API + '/fixtures/' + task_id, params={'seed': seed, 'task_pack': pack})
    if (row.get('schema') != 'gateway.coding-task-public.v2' or
            (row.get('id'), row.get('seed'), row.get('taskPack')) != (task_id, seed, pack)):
        raise ValueError('Gateway returned a different task')
    return {key: row[key] for key in TASK_KEYS}


def read_file(path, maximum):
    with path.open('rb') as stream:
        raw = stream.read(maximum + 1)
    if len(raw) > maximum:
        raise ValueError(f'{path.name} exceeds the input limit')
    return raw.decode('utf-8')


def reference(source):
    if source not in LABELS:
        raise ValueError('Unknown external reference')
    return {'model': f'{source} (unverified external reference)', 'provider_kind': 'MANUAL_EXTERNAL_REFERENCE',
            'profile_uid': 'external-reference:' + source, 'profile_revision': 'unobserved',
            'settings_digest': 'unobserved', 'route_identity': 'manual-import', 'request_ids': [],
            'identity_verified': False, 'settings_confirmed': False, 'usage_complete': False}


def prepare(trial, task, source, proposal):
    fields = {'request_id': 'extref_' + uuid.uuid4().hex, 'task_id': task['id'], 'seed': task['seed'],
              'task_pack': task['taskPack'], 'fixture_digest': task['taskDigest'], 'grader_digest': task['graderDigest'],
              'environment_digest': task['environmentDigest'], 'proposal_digest': content_digest(proposal),
              'evidence_lane': 'EXECUTABLE', 'generation_receipt': reference(source)}
    saved = {'owner': task['gatewayOwnerDigest'], 'publicTaskDigest': digest({key: task[key] for key in TASK_KEYS}), 'request': fields}
    # This immutable trial keeps only the answer digest. A repeated prepare cannot reset its single admission.
    with trial.open('xb'):
        pass
    with closing(sqlite3.connect(trial)) as db, db:
        db.execute('CREATE TABLE trial (id INTEGER PRIMARY KEY CHECK(id=1), plan TEXT NOT NULL, attempted INTEGER NOT NULL)')
        db.execute('INSERT INTO trial VALUES(1,?,0)', (json.dumps(saved),))
    return fields['request_id']


def grade(client, trial, proposal=None):
    # Opening a missing or mistyped path must never create a replacement trial.
    with closing(sqlite3.connect(trial.resolve().as_uri() + '?mode=rw', uri=True)) as db:
        raw, attempted = db.execute('SELECT plan,attempted FROM trial WHERE id=1').fetchone()
        saved = json.loads(raw)
        request = saved['request']
        if (set(request) != {'request_id', 'task_id', 'task_pack', 'seed', 'fixture_digest', 'grader_digest', 'environment_digest',
                             'proposal_digest', 'generation_receipt', 'evidence_lane'} or request['evidence_lane'] != 'EXECUTABLE' or
                request['generation_receipt'] not in [reference(source) for source in LABELS] or
                not re.fullmatch(r'extref_[a-f0-9]{32}', request['request_id'])):
            raise ValueError('Invalid unverified executable reference trial')
        if digest(scope_key(client.identity())) != saved['owner']:
            raise ValueError('Restore the original Gateway and owner')
        if proposal is not None and content_digest(proposal) != request['proposal_digest']:
            raise ValueError('The candidate differs from the frozen trial')
        query = {'request_id': request['request_id']}
        found = client.request('GET', API, params=query)
        if proposal is not None and found.get('submissionState') == 'NOT_OBSERVED' and not attempted:
            task = public_task(client, request['task_id'], request['seed'], request['task_pack'])
            if digest(task) != saved['publicTaskDigest']:
                raise ValueError('The public task, grader or environment changed')
            # Serialize concurrent callers and commit the intent before opening the network connection.
            with db:
                first_attempt = db.execute('UPDATE trial SET attempted=1 WHERE id=1 AND attempted=0').rowcount == 1
            if first_attempt:
                try:
                    run = client.request('POST', API + '/evaluate', payload=dict(request, proposal=proposal))
                except ClientError as error:
                    found = client.request('GET', API, params=query)
                    if found.get('submissionState') == 'NOT_OBSERVED':
                        raise ValueError(f'{error} Admission is unconfirmed. Use status with this trial.') from None
                else:
                    found = {'submissionState': 'OBSERVED', 'run': run}
            else:
                found = client.request('GET', API, params=query)
        result = {'generationSource': request['generation_receipt']['model'], 'providerIdentityVerified': False,
                  'scope': 'ONE_EXECUTABLE_FIXTURE', 'submissionState': found['submissionState'],
                  'submissionAttemptRecorded': bool(db.execute('SELECT attempted FROM trial WHERE id=1').fetchone()[0]),
                  **{key: request[key] for key in ('request_id', 'task_id', 'task_pack', 'seed', 'fixture_digest',
                                                     'grader_digest', 'environment_digest', 'proposal_digest')}}
        if found['submissionState'] == 'OBSERVED':
            run = found['run']
            if (run['request'].get('suite') != 'coding_submission' or
                    any(run['request'].get(key) != value for key, value in request.items())):
                raise ValueError('Gateway readback differs from the frozen trial')
            summary = run.get('summary') or {}
            result.update(runId=run['id'], state=run['state'], qualified=summary.get('qualified'),
                          resolved=summary.get('resolved') if summary.get('outcomeComplete') else None, outcomeComplete=summary.get('outcomeComplete'),
                          score=summary.get('score') if summary.get('qualified') is True else None, maximum=summary.get('maximum'))
        elif found['submissionState'] not in {'NOT_OBSERVED', 'REMOVED'}:
            raise ValueError('Unknown submission state')
        if digest(scope_key(client.identity())) != saved['owner']:
            raise ValueError('Gateway owner changed during readback')
        return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    fetch = commands.add_parser('task', help='Export one public task from the installed Gateway')
    fetch.add_argument('--root', type=Path, required=True)
    fetch.add_argument('--id', required=True)
    fetch.add_argument('--seed', type=int, default=17)
    fetch.add_argument('--pack', choices=('python', 'repository'), default='python')
    fetch.add_argument('--output', type=Path, required=True)
    freeze = commands.add_parser('prepare', help='Freeze a candidate digest and create a local trial file')
    freeze.add_argument('--task', type=Path, required=True)
    freeze.add_argument('--source', choices=LABELS, required=True)
    freeze.add_argument('--candidate', type=Path, required=True)
    freeze.add_argument('--trial', type=Path, required=True)
    for name in ('submit', 'status'):
        command = commands.add_parser(name)
        command.add_argument('--root', type=Path, required=True)
        command.add_argument('--trial', type=Path, required=True)
        if name == 'submit':
            command.add_argument('--candidate', type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == 'task':
            client = Client(args.root)
            owner = digest(scope_key(client.identity()))
            value = public_task(client, args.id, args.seed, args.pack)
            with args.output.open('x', encoding='utf-8') as stream:
                json.dump(dict(value, gatewayOwnerDigest=owner), stream, indent=2, ensure_ascii=False)
            print(args.output)
        elif args.command == 'prepare':
            print(prepare(args.trial, json.loads(read_file(args.task, 2 * 1024 * 1024)), args.source, read_file(args.candidate, 65536)))
        else:
            answer = read_file(args.candidate, 65536) if args.command == 'submit' else None
            print(json.dumps(grade(Client(args.root), args.trial, answer), indent=2))
    except (KeyError, OSError, sqlite3.Error, TypeError, ValueError) as error:
        parser.exit(1, f'{error}\n')


if __name__ == '__main__':
    main()
