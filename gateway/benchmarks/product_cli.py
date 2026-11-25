"""Native comparison commands for the Gateway external grading CLI."""
import json
import secrets
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from cryptography.hazmat.primitives.serialization import load_pem_private_key, load_pem_public_key

from benchmarks import product_trials as trials
from benchmarks.contracts import digest

TARGET_COMMANDS = {'prepare-target-review': 'Prepare a case-bound target observation inventory',
                   'observe-targets': 'Sign the independently captured event inventory',
                   'grade-targets': 'Prepare a label sheet or sign independent judgments',
                   'score-targets': 'Score independently reviewed production targets'}
COMMANDS = ('freeze-native', 'schedule-native', 'observe-native', 'grade-native', 'compare-native',
            'blind-previews', 'score-previews', *TARGET_COMMANDS)


def configure(commands):
    for name in COMMANDS:
        if name in TARGET_COMMANDS:
            cmd = commands.add_parser(name, help=TARGET_COMMANDS[name])
            cmd.add_argument('--qualification', type=Path, required=True)
            if name == 'observe-targets':
                cmd.add_argument('--inventory', type=Path, required=True)
                cmd.add_argument('--observer-key', type=Path, required=True)
            if name in ('grade-targets', 'score-targets'):
                cmd.add_argument('--observation', type=Path, required=True)
                cmd.add_argument('--observer-public', type=Path, required=True)
                cmd.add_argument('--grades', type=Path, required=name == 'score-targets')
                if name == 'grade-targets':
                    cmd.add_argument('--grader-key', type=Path)
                else:
                    cmd.add_argument('--grader-public', type=Path, required=True)
            continue
        cmd = commands.add_parser(name, help={
            'blind-previews': 'Prepare anonymized A/B preview transcripts for registered readers',
            'score-previews': 'Grade factual clauses, corrections, repetition and blind preferences',
            'freeze-native': 'Preregister and sign the common held-out suite and budgets',
            'schedule-native': 'Show the randomized trial order for a verified plan',
            'observe-native': 'Attest measurements captured by the independent lab observer',
            'grade-native': 'Attest the authorized Gateway grader readback for one delivered candidate',
            'compare-native': 'Compare independent, signed observations and hidden grades',
        }[name])
        if name in ('blind-previews', 'score-previews'):
            cmd.add_argument('--study', type=Path, required=True)
            cmd.add_argument('--blinding-key', type=Path, required=True)
            if name == 'score-previews':
                cmd.add_argument('--ballots', type=Path, required=True)
            continue
        if name == 'freeze-native':
            cmd.add_argument('--definition', type=Path, required=True)
        else:
            cmd.add_argument('--plan', type=Path, required=True)
        if name in ('freeze-native', 'observe-native'):
            cmd.add_argument('--observer-key', type=Path, required=True)
        else:
            cmd.add_argument('--observer-public', type=Path, required=True)
        if name == 'observe-native':
            cmd.add_argument('--measurements', type=Path, required=True)
            cmd.add_argument('--capture', type=Path)
            cmd.add_argument('--candidate-out', type=Path)
        elif name == 'grade-native':
            for option in ('root', 'grader-key', 'observation', 'trial'):
                cmd.add_argument('--' + option, type=Path, required=True)
        elif name == 'compare-native':
            for option in ('observations', 'grades', 'grader-public'):
                cmd.add_argument('--' + option, type=Path, required=True)


def execute(args, client_factory, grade_trial, read_file):
    def load(path):
        return json.loads(read_file(path, 8 * 1024 * 1024))

    def key(path, private):
        raw = read_file(path, 8192).encode()
        parsed = load_pem_private_key(raw, password=None) if private else load_pem_public_key(raw)
        if not isinstance(parsed, Ed25519PrivateKey if private else Ed25519PublicKey):
            raise ValueError('An Ed25519 proctor or grader key is required')
        return parsed

    def attest(payload, private):
        return {'payload': payload, 'signature': private.sign(trials.signed_bytes(payload)).hex()}

    if args.command in TARGET_COMMANDS:
        from benchmarks import target_review
        report = load(args.qualification)
        if args.command == 'prepare-target-review':
            return target_review.inventory_template(report)
        if args.command == 'observe-targets':
            inventory = load(args.inventory)
            target_review.grade_template(report, inventory)
            return attest(inventory, key(args.observer_key, True))
        observation = load(args.observation)
        observer = key(args.observer_public, False)
        if args.command == 'score-targets':
            return target_review.score_signed(report, observation, load(args.grades), observer, key(args.grader_public, False))
        inventory = trials.verified(observation, observer, target_review.OBSERVATION)
        if not args.grades and not args.grader_key:
            return target_review.grade_template(report, inventory)
        if not args.grades or not args.grader_key:
            raise ValueError('Signing a grade requires both the completed label sheet and the grader key')
        signer = key(args.grader_key, True)
        if signer.public_key().public_bytes_raw() == observer.public_bytes_raw():
            raise ValueError('The grader must have its own independent key')
        labels = load(args.grades)
        target_review.reviewed_metrics(report, inventory, labels)
        return attest(labels, signer)

    if args.command in ('blind-previews', 'score-previews'):
        from benchmarks import preview_study
        if args.command == 'blind-previews' and not args.blinding_key.exists():
            import os
            with os.fdopen(os.open(args.blinding_key, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600), 'w') as stream:
                stream.write(secrets.token_hex(32))
        secret = bytes.fromhex(read_file(args.blinding_key, 128).strip())
        study = load(args.study)
        if args.command == 'blind-previews':
            return preview_study.packet(study, secret)[0]
        return preview_study.score(study, secret, load(args.ballots))
    if args.command == 'freeze-native':
        plan = load(args.definition)
        plan.update(schema=trials.PLAN)
        trials.validate_plan(plan)
        return attest(plan, key(args.observer_key, True))
    signed_plan = load(args.plan)
    observer_private = key(args.observer_key, True) if args.command == 'observe-native' else None
    observer = observer_private.public_key() if observer_private else key(args.observer_public, False)
    plan = trials.verified(signed_plan, observer, trials.PLAN)
    slots = trials.schedule(plan)
    if args.command == 'schedule-native':
        return slots
    if args.command == 'compare-native':
        return trials.compare_signed(signed_plan, load(args.observations), load(args.grades), observer.public_bytes_raw(),
                                     key(args.grader_public, False).public_bytes_raw())
    if args.command == 'observe-native':
        row = load(args.measurements)
        if type(row.get('scheduleIndex')) is not int or not 0 <= row['scheduleIndex'] < len(slots):
            raise ValueError('Missing schedule position')
        if row.get('planDigest') != digest(plan) or row.get('controlsDigest') != plan['controlsDigest']:
            raise ValueError('Observer must record the plan and actual controls used at dispatch')
        if any(row.get(k) != v for k, v in slots[row['scheduleIndex']].items()) or row.get('version') != plan['versions'][row['product']]:
            raise ValueError('Observed product, version, task or order differs from preregistration')
        row['schema'] = trials.OBSERVATION
        if row.get('state') == 'DELIVERED':
            if not args.capture or not args.candidate_out:
                raise ValueError('A delivered trial needs its native capture and a new candidate output path')
            received = trials.native_answer(row['product'], read_file(args.capture, 8 * 1024 * 1024))
            row.update(runId=received['runId'], artifactDigest=received['artifactDigest'])
            with args.candidate_out.open('xb') as out:
                out.write(received['answer'].encode())
        elif args.capture or args.candidate_out:
            raise ValueError('Only delivered trials have an answer')
        return attest(row, observer_private)
    row = trials.verified(load(args.observation), observer, trials.OBSERVATION)
    if row.get('planDigest') != digest(plan) or row.get('state') != 'DELIVERED':
        raise ValueError('This observed delivery belongs to a different plan or has no answer')
    graded = grade_trial(client_factory(args.root), args.trial)
    case = next((c for c in plan['cases'] if c['taskDigest'] == row.get('taskDigest')), None)
    if (not case or graded.get('fixture_digest') != case['taskDigest'] or graded.get('grader_digest') != case['graderDigest']
            or graded.get('environment_digest') != case.get('environmentDigest')
            or graded.get('proposal_digest') != row.get('artifactDigest') or graded.get('submissionState') != 'OBSERVED'
            or graded.get('state') not in ('COMPLETED', 'FAILED', 'BLOCKED', 'INTERRUPTED', 'CANCELLED')):
        raise ValueError('A terminal independent grade matching this task, environment and candidate is required')
    result = 'GRADER_UNAVAILABLE'
    if graded.get('state') == 'COMPLETED' and graded.get('outcomeComplete') is True and graded.get('resolved') in (0, 1):
        if graded['resolved'] == 0:
            result = 'FAIL'
        elif graded.get('qualified') is True:
            result = 'PASS'
    signer = key(args.grader_key, True)
    if signer.public_key().public_bytes_raw() == observer.public_bytes_raw():
        raise ValueError('The grader must have its own independent key')
    return attest({'schema': trials.GRADE, 'planDigest': digest(plan), 'taskDigest': case['taskDigest'],
                   'graderDigest': graded['grader_digest'], 'environmentDigest': graded['environment_digest'],
                   'artifactDigest': graded['proposal_digest'],
                   'runId': row['runId'], 'graderRunId': graded['runId'], 'result': result}, signer)
