#!/usr/bin/env python3
"""Installed Gateway native-product campaigns. Never launches a model.

Uses the canonical product-trial and supplied-answer grading contracts. Campaign
files live outside the installation and source checkout; only public keys and
separately attested observations/grades enter the retained evidence.
"""
from __future__ import annotations

import argparse
import contextlib
import csv
from datetime import datetime, timezone
import hashlib
import html
import io
import json
import math
import os
from pathlib import Path
import re
import secrets
import shutil
import sqlite3
import sys
import tempfile
import time
import uuid

PACKAGE = Path(__file__).resolve().parents[1]
ASSETS = Path(__file__).resolve().parent / 'campaign_assets'
from benchmarks import product_trials as native
from benchmarks.contracts import digest, content_digest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from cryptography.hazmat.primitives.serialization import (Encoding, PrivateFormat, PublicFormat,
    NoEncryption, load_pem_private_key, load_pem_public_key)

VERSION = '1.0.0'
PRODUCTS = native.PRODUCTS
TASK_KEYS = ('id', 'seed', 'taskPack', 'prompt', 'outputSchema', 'taskDigest', 'graderDigest', 'environmentDigest')
REAL = 'NATIVE_OBSERVED'
SYNTHETIC = 'SYNTHETIC_ONLY'
TERMINAL = {'COMPLETED', 'FAILED', 'BLOCKED', 'INTERRUPTED', 'CANCELLED'}


class KitError(ValueError):
    pass


def now():
    return datetime.now(timezone.utc).isoformat(timespec='seconds')


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def read_bytes(path, limit=8 * 1024 * 1024):
    with Path(path).open('rb') as stream:
        raw = stream.read(limit + 1)
    if len(raw) > limit:
        raise KitError('Input exceeds its bounded size limit: ' + Path(path).name)
    return raw


def reject_constant(value):
    raise KitError('Non-finite JSON numbers are not evidence: ' + value)


def unique_object(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise KitError('Duplicate JSON keys are ambiguous evidence')
        result[name] = value
    return result


def parse_json(text):
    return json.loads(text, parse_constant=reject_constant, object_pairs_hook=unique_object)


def read_json(path, limit=8 * 1024 * 1024):
    return parse_json(read_bytes(path, limit).decode('utf-8-sig'))


def json_bytes(value):
    return (json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + '\n').encode('utf-8')


def guard_output(path):
    resolved = Path(path).resolve()
    for parent in (resolved, *resolved.parents):
        if (parent / '.git').exists():
            raise KitError('Benchmark outputs must be outside every Git checkout: ' + str(resolved))
    return resolved


def write_new(path, raw):
    path = guard_output(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('xb') as stream:
        stream.write(raw)
        stream.flush()
        os.fsync(stream.fileno())


def write_json_new(path, value):
    write_new(path, json_bytes(value))


def atomic_write(path, raw):
    path = guard_output(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        write_new(temporary, raw)
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def contained(path, parent):
    try:
        Path(path).resolve().relative_to(Path(parent).resolve())
        return True
    except ValueError:
        return False


def safe_child(root, relative):
    root = Path(root).resolve()
    if not isinstance(relative, str) or Path(relative).is_absolute():
        raise KitError('Expected a relative evidence path')
    path = (root / relative).resolve()
    if not contained(path, root):
        raise KitError('Evidence path escapes the session')
    return path


@contextlib.contextmanager
def session_lock(session):
    path = guard_output(Path(session) / '.controller.lock')
    with path.open('a+b') as stream:
        stream.seek(0, 2)
        if stream.tell() == 0:
            stream.write(b'0')
            stream.flush()
        stream.seek(0)
        try:
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise KitError('Another controller is working on this session; retry after it finishes') from error
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


def validate_audit(session):
    path = Path(session) / 'audit.jsonl'
    previous, count = None, 0
    if path.exists():
        for line in read_bytes(path, 64 * 1024 * 1024).splitlines():
            row = parse_json(line)
            if not isinstance(row, dict) or not native.sha(row.get('entryDigest')):
                raise KitError('Audit log entry is invalid; preserve the session for investigation')
            signature = row.pop('entryDigest')
            if row.get('previousDigest') != previous or row.get('sequence') != count or digest(row) != signature:
                raise KitError('Audit log integrity failed; preserve the session for investigation')
            previous, count = signature, count + 1
    return previous, count


def audit(session, action, **details):
    """Append only; partial/corrupt logs are retained and require operator review."""
    previous, count = validate_audit(session)
    path = guard_output(Path(session) / 'audit.jsonl')
    row = {'sequence': count, 'at': now(), 'action': action, 'previousDigest': previous, 'details': details}
    row['entryDigest'] = digest(row)
    with path.open('ab') as stream:
        stream.write(json.dumps(row, sort_keys=True, allow_nan=False).encode() + b'\n')
        stream.flush()
        os.fsync(stream.fileno())


def _audit_stamp(path):
    try:
        observed = path.stat()
    except FileNotFoundError:
        return None
    return observed.st_size, observed.st_mtime_ns, observed.st_dev, observed.st_ino


def audit_writer(session):
    """Validate once, then append under the caller's existing session lock.

    A different process or direct file edit invalidates the retained tail before
    the next append. Every row still carries the previous digest and is fsynced.
    Public ``audit`` keeps its complete validation for independent actions.
    """
    path = guard_output(Path(session) / 'audit.jsonl')
    observed = _audit_stamp(path)
    previous, count = validate_audit(session)
    if _audit_stamp(path) != observed:
        raise KitError('Audit log changed while validating the session; preserve the original evidence')

    def append(action, **details):
        nonlocal previous, count, observed
        if _audit_stamp(path) != observed:
            raise KitError('Audit log changed during this controller operation; preserve the original evidence')
        row = {'sequence': count, 'at': now(), 'action': action, 'previousDigest': previous, 'details': details}
        row['entryDigest'] = digest(row)
        raw = json.dumps(row, sort_keys=True, allow_nan=False).encode() + b'\n'
        flags = os.O_WRONLY | os.O_APPEND | getattr(os, 'O_BINARY', 0)
        if observed is None:
            flags |= os.O_CREAT | os.O_EXCL
        descriptor = os.open(path, flags, 0o600)
        with os.fdopen(descriptor, 'ab') as stream:
            opened = os.fstat(stream.fileno())
            opened_stamp = opened.st_size, opened.st_mtime_ns, opened.st_dev, opened.st_ino
            if ((observed is not None and opened_stamp != observed) or
                    (observed is None and opened.st_size != 0)):
                raise KitError('Audit log changed before append; preserve the original evidence')
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
            written = os.fstat(stream.fileno())
        # Windows may update last-write time when the file handle closes. Bind
        # the new timestamp only after checking the same file and exact size.
        updated = _audit_stamp(path)
        expected_size = (observed[0] if observed is not None else 0) + len(raw)
        if updated is None or updated[0] != expected_size or updated[2:] != (written.st_dev, written.st_ino):
            raise KitError('Audit log changed while appending; preserve the original evidence')
        previous, count, observed = row['entryDigest'], count + 1, updated

    return append


def key(path, private=False):
    raw = read_bytes(path, 8192)
    value = load_pem_private_key(raw, password=None) if private else load_pem_public_key(raw)
    if not isinstance(value, Ed25519PrivateKey if private else Ed25519PublicKey):
        raise KitError('An Ed25519 PEM key is required')
    return value


def raw_public(value):
    return value.public_bytes(Encoding.Raw, PublicFormat.Raw)


def attest(payload, signer):
    return {'payload': payload, 'signature': signer.sign(native.signed_bytes(payload)).hex()}


def external_secret(path, session=None, repo=None):
    guard_output(path)
    for parent in (PACKAGE, session, repo):
        if parent and contained(path, parent):
            raise KitError('Keep private keys outside the installation, session, repository and candidate workspaces')


def keygen(role, out):
    out = guard_output(out)
    external_secret(out)
    if role not in ('observer', 'grader'):
        raise KitError('Choose observer or grader')
    out.mkdir(parents=True, exist_ok=True)
    private, public = out / (role + '.pem'), out / (role + '.pub.pem')
    if private.exists() or public.exists():
        raise KitError('Key paths already exist; existing keys are never replaced')
    signer = Ed25519PrivateKey.generate()
    write_new(private, signer.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()))
    os.chmod(private, 0o600)
    write_new(public, signer.public_key().public_bytes(Encoding.PEM, PublicFormat.SubjectPublicKeyInfo))
    return {'role': role, 'privateKey': str(private), 'publicKey': str(public),
            'publicKeyFingerprint': sha(raw_public(signer.public_key())),
            'note': 'Separate custody is required; distinct keys alone do not prove independent observation.'}


def nonempty(value, label):
    if not isinstance(value, str) or not value.strip() or value.strip().upper() in {'TODO', 'UNKNOWN', 'REPLACE_ME'}:
        raise KitError(label + ' must be supplied from an actual observation before freezing a real plan')
    return value


def runtime_identity():
    """Bind the installed grading implementation without invoking Git or a model."""
    manifest = read_json(PACKAGE / 'manifest-v14.json', 4 * 1024 * 1024)
    build_info = PACKAGE / 'build-info.json'
    # A packaged Windows build records its exact checkout; a development tree
    # uses the validated package baseline plus the independent byte digest below.
    commit = (read_json(build_info).get('sourceCommit') if build_info.is_file()
              else manifest.get('sourceCommit'))
    if not isinstance(commit, str) or not re.fullmatch(r'[a-f0-9]{40}', commit):
        raise KitError('The installed package has no valid source provenance; repair the installation')
    paths = ('benchmarks/contracts.py', 'benchmarks/product_trials.py',
             'benchmarks/product_cli.py', 'grade_external_reference.py',
             'benchmarks/campaign.py', 'benchmarks/campaign_gateway.py',
             'desktop/conversation_client.py', 'desktop/configuration.py')
    files = {name: sha(read_bytes(PACKAGE / name, 2 * 1024 * 1024)) for name in paths}
    return {'kind': 'installed-gateway', 'commit': commit, 'runtimeDigest': digest(files)}


def lab_config(path, need_source=True):
    cfg = read_json(path)
    if not isinstance(cfg, dict) or cfg.get('schema') != 'gpt-mobile-benchkit.lab.v1':
        raise KitError('Unsupported lab configuration')
    if need_source:
        if cfg.get('source') != runtime_identity():
            raise KitError('The lab belongs to a different installed runtime. Export new templates for a new campaign; preserve previous evidence')
        nonempty(cfg.get('gateway', {}).get('root'), 'gateway.root')
        nonempty(cfg.get('gateway', {}).get('allowedOrigin'), 'gateway.allowedOrigin')
    return cfg


def campaign_config(path):
    campaign = read_json(path)
    if not isinstance(campaign, dict) or campaign.get('schema') != 'gpt-mobile-benchkit.campaign.v1':
        raise KitError('Unsupported campaign configuration')
    selected = campaign.get('products')
    if not isinstance(selected, list) or len(selected) < 2 or len(selected) != len(set(selected)) or 'GPT Mobile AI' not in selected or not set(selected).issubset(PRODUCTS):
        raise KitError('Select distinct supported products including GPT Mobile AI')
    tasks = campaign.get('tasks')
    if not isinstance(tasks, list) or not 1 <= len(tasks) <= 500:
        raise KitError('The campaign must have preregistered tasks')
    if (type(campaign.get('repeats')) is not int or not 1 <= campaign['repeats'] <= 20
            or campaign.get('cache') not in {'cold', 'warm'}
            or not native.finite(campaign.get('maxSeconds'), 1, 86400)
            or not native.finite(campaign.get('maxCostUsd'), 0, 10000)
            or not native.finite(campaign.get('marginPoints'), 0, 99)):
        raise KitError('Freeze valid repeats, cache, time/cost budgets and comparison margin')
    nonempty(campaign.get('name'), 'campaign.name')
    seen = set()
    for task in tasks:
        if not isinstance(task, dict):
            raise KitError('Every campaign task must be an object')
        triple = (task.get('pack'), task.get('id'), task.get('seed'))
        if (triple in seen or triple[0] not in {'python', 'repository'} or
                not isinstance(triple[1], str) or not re.fullmatch(r'[a-z0-9-]{1,100}', triple[1]) or
                type(triple[2]) is not int or not 0 <= triple[2] <= 2**31 - 1 or
                not isinstance(task.get('family'), str) or not task['family'].strip()):
            raise KitError('Invalid or duplicate campaign task')
        seen.add(triple)
    return campaign


def task_filename(task):
    return f"{task['pack']}--{task['id']}--{task['seed']}.json"


def bridge(cfg, operation, body, timeout=180):
    """Use only the installed adapter's existing authenticated, restricted client."""
    from benchmarks.campaign_gateway import execute
    if cfg.get('source') != runtime_identity() and operation != 'status':
        raise KitError('The installed grading runtime changed; preserve the existing trial for review')
    return execute(Path(cfg['gateway']['root']), operation, body,
                   allowed_origin=cfg['gateway']['allowedOrigin'],
                   expected_owner_digest=cfg['gateway'].get('expectedOwnerDigest'))


def export_tasks(cfg_path, campaign_path, out):
    cfg, campaign = lab_config(cfg_path), campaign_config(campaign_path)
    out = guard_output(out)
    if out.exists():
        raise KitError('Choose a new export directory; frozen task files are never overwritten')
    result = bridge(cfg, 'export', {'tasks': campaign['tasks']}, timeout=max(180, 35 * len(campaign['tasks'])))
    rows = result.get('tasks')
    if not isinstance(rows, list) or len(rows) != len(campaign['tasks']):
        raise KitError('The Gateway task export is incomplete')
    stage = out.with_name(out.name + '.' + uuid.uuid4().hex + '.staging')
    stage.mkdir(parents=True)
    entries = []
    for requested, row in zip(campaign['tasks'], rows):
        validate_task(row, requested)
        name = task_filename(requested)
        write_json_new(stage / name, row)
        entries.append({'file': name, 'sha256': sha(read_bytes(stage / name)), **requested})
    write_json_new(stage / 'export-manifest.json', {'schema': 'gpt-mobile-benchkit.task-export.v1',
        'sourceCommit': cfg['source']['commit'], 'runtimeDigest': cfg['source'].get('runtimeDigest'), 'exportedAt': now(), 'evidenceLane': REAL,
        'campaignDigest': digest(campaign), 'files': entries})
    stage.rename(out)
    return {'directory': str(out), 'tasks': len(rows), 'modelRequests': 0}


def validate_task(row, requested):
    if not isinstance(row, dict) or any(k not in row for k in TASK_KEYS):
        raise KitError('An exported public task is incomplete')
    if (row['id'], row['seed'], row['taskPack']) != (requested['id'], requested['seed'], requested['pack']):
        raise KitError('Exported task identity differs from campaign')
    if not all(native.sha(row.get(k)) for k in ('taskDigest', 'graderDigest', 'environmentDigest', 'gatewayOwnerDigest')):
        raise KitError('A public task is missing exact task, environment, grader or owner digests')
    if not isinstance(row['prompt'], str) or not row['prompt'].strip() or not isinstance(row['outputSchema'], dict):
        raise KitError('A public task requires its exact prompt and output schema')
    if row.get('family', requested['family']) != requested['family']:
        raise KitError('Task family differs from preregistration')


def public_packet(row):
    packet = {'id': row['id'], 'seed': row['seed'], 'taskPack': row['taskPack'], 'taskDigest': row['taskDigest'],
              'prompt': row['prompt'], 'outputSchema': row['outputSchema']}
    prompt = row['prompt'] + '\n\nRequired output schema (return your complete proposal unchanged):\n' + json.dumps(row['outputSchema'], indent=2, ensure_ascii=False)
    return json_bytes(packet), prompt.encode('utf-8')


def freeze_controls(cfg, campaign):
    controls = cfg.get('controls')
    if not isinstance(controls, dict):
        raise KitError('Actual matched controls are required')
    for name in ('hardwareClass', 'commonToolPolicy', 'cacheResetProcedure', 'costAccountingBasis', 'timeMeasurementBasis'):
        nonempty(controls.get(name), 'controls.' + name)
    if not re.fullmatch(r'[a-f0-9]{40}', str(controls.get('startingRevision'))):
        raise KitError('Freeze the full starting revision, not a mutable branch name')
    products = {}
    for product in campaign['products']:
        row = cfg.get('products', {}).get(product)
        if not isinstance(row, dict) or not isinstance(row.get('requestedSettings'), dict):
            raise KitError(product + ' requires frozen requestedSettings (separate from provider acknowledgements)')
        nonempty(row.get('version'), product + ' observed product version')
        nonempty(row.get('surface'), product + ' native surface')
        nonempty(row.get('toolAccess'), product + ' allowed tool access')
        products[product] = row
    return {'matchedControls': controls, 'products': products,
            'budgets': {k: campaign[k] for k in ('maxSeconds', 'maxCostUsd', 'cache')},
            'answerPolicy': 'One fresh single-task native session per slot; retain delivered final bytes. No cherry-picked retries.',
            'firstAttemptPolicy': 'Unknown unless separately instrumented; final transcript is not a first-patch receipt.',
            'bestWithinBudgetPolicy': 'Unknown; this campaign controller does not search multiple attempts for a best answer.'}


def initialize(cfg_path, campaign_path, tasks_dir, out, observer_key, *, synthetic=False):
    cfg, campaign = lab_config(cfg_path, need_source=not synthetic), campaign_config(campaign_path)
    out, tasks_dir = guard_output(out), Path(tasks_dir).resolve()
    if out.exists():
        raise KitError('Choose a new campaign directory; a preregistered campaign cannot be reset')
    external_secret(observer_key, out)
    signer = key(observer_key, True)
    observer_public = key(cfg['observerPublicKey'])
    grader_public = key(cfg['graderPublicKey'])
    if raw_public(signer.public_key()) != raw_public(observer_public) or raw_public(observer_public) == raw_public(grader_public):
        raise KitError('Observer must match its registered key; the grader must use a separate key')
    controls = freeze_controls(cfg, campaign)
    exported = read_json(tasks_dir / 'export-manifest.json')
    lane = SYNTHETIC if synthetic else REAL
    if (exported.get('evidenceLane') != lane or exported.get('campaignDigest') != digest(campaign)
            or exported.get('sourceCommit') != cfg['source']['commit']
            or exported.get('runtimeDigest') != cfg['source'].get('runtimeDigest')):
        raise KitError('Task export belongs to a different campaign, source revision or evidence lane')
    entries_by_file = {entry['file']: entry for entry in exported['files']}
    task_rows, files = [], []
    for requested in campaign['tasks']:
        name = task_filename(requested)
        raw = read_bytes(tasks_dir / name, 2 * 1024 * 1024)
        if entries_by_file.get(name, {}).get('sha256') != sha(raw):
            raise KitError('Task export integrity failed: ' + name)
        row = parse_json(raw.decode('utf-8-sig'))
        validate_task(row, requested)
        if row.get('synthetic') is True and not synthetic:
            raise KitError('Synthetic public tasks cannot enter a measured campaign')
        task_rows.append((requested, row, raw))
        files.append({'file': 'tasks/' + name, 'sha256': sha(raw), 'taskDigest': row['taskDigest'], **requested})
    plan = {'schema': native.PLAN, 'versions': {p: controls['products'][p]['version'] for p in campaign['products']},
            'controlsDigest': digest(controls), 'orderSeed': secrets.token_hex(32),
            **{k: campaign[k] for k in ('cache', 'maxSeconds', 'maxCostUsd', 'marginPoints', 'repeats')},
            'cases': [{'family': requested['family'], **{k: row[k] for k in ('taskDigest', 'graderDigest', 'environmentDigest')}}
                      for requested, row, _ in task_rows],
            'kit': {'version': VERSION, 'evidenceLane': lane, 'campaignName': campaign['name'],
                    'campaignDigest': digest(campaign), 'sourceCommit': cfg['source']['commit'],
                    'runtimeDigest': cfg['source'].get('runtimeDigest'), 'tasks': files,
                    'createdAt': now(), 'observerFingerprint': sha(raw_public(observer_public)),
                    'graderFingerprint': sha(raw_public(grader_public))}}
    native.validate_plan(plan)
    stage = out.with_name(out.name + '.' + uuid.uuid4().hex + '.staging')
    stage.mkdir(parents=True)
    for name, value in [('plan.json', attest(plan, signer)), ('controls.json', controls), ('campaign.json', campaign)]:
        write_json_new(stage / name, value)
    for name, public in [('observer', observer_public), ('grader', grader_public)]:
        write_new(stage / 'public' / (name + '.pem'), public.public_bytes(Encoding.PEM, PublicFormat.SubjectPublicKeyInfo))
    for requested, row, raw in task_rows:
        filename = task_filename(requested)
        write_new(stage / 'tasks' / filename, raw)
        packet_json, packet_text = public_packet(row)
        write_new(stage / 'packets' / filename, packet_json)
        write_new(stage / 'packets' / filename.replace('.json', '.txt'), packet_text)
    for dirname in ('slots', 'grader', 'rejected', 'reports'):
        (stage / dirname).mkdir()
    schedule = indexed_schedule(plan)
    write_json_new(stage / 'schedule.json', schedule)
    buf = io.StringIO(newline='')
    writer = csv.DictWriter(buf, fieldnames=['scheduleIndex', 'product', 'taskId', 'taskPack', 'seed', 'repeat', 'taskDigest', 'family', 'cache', 'version'])
    writer.writeheader()
    writer.writerows(schedule)
    write_new(stage / 'schedule.csv', buf.getvalue().encode('utf-8'))
    audit(stage, 'campaign_frozen', planDigest=digest(plan), evidenceLane=lane, slots=len(schedule))
    stage.rename(out)
    make_report(out)
    return {'session': str(out), 'planDigest': digest(plan), 'tasks': len(files), 'slots': len(schedule),
            'families': len({r['family'] for r in plan['cases']}), 'evidenceLane': lane, 'modelRequests': 0}


def indexed_schedule(plan):
    by_task = {row['taskDigest']: row for row in plan['kit']['tasks']}
    return [dict(slot, scheduleIndex=i, taskId=by_task[slot['taskDigest']]['id'],
                 taskPack=by_task[slot['taskDigest']]['pack'], seed=by_task[slot['taskDigest']]['seed'],
                 family=by_task[slot['taskDigest']]['family'], cache=plan['cache'], version=plan['versions'][slot['product']])
            for i, slot in enumerate(native.schedule(plan))]


def load_session(session):
    session = Path(session).resolve()
    validate_audit(session)
    observer, grader = key(session / 'public/observer.pem'), key(session / 'public/grader.pem')
    envelope = read_json(session / 'plan.json')
    plan = native.verified(envelope, observer, native.PLAN)
    native.validate_plan(plan)
    extra = plan.get('kit', {})
    if extra.get('evidenceLane') not in (REAL, SYNTHETIC) or extra.get('observerFingerprint') != sha(raw_public(observer)) or extra.get('graderFingerprint') != sha(raw_public(grader)):
        raise KitError('Signed campaign lane or public-key binding is invalid')
    if digest(read_json(session / 'controls.json')) != plan['controlsDigest'] or digest(read_json(session / 'campaign.json')) != extra['campaignDigest']:
        raise KitError('Frozen controls or campaign changed')
    for row in extra['tasks']:
        raw = read_bytes(safe_child(session, row['file']), 2 * 1024 * 1024)
        if sha(raw) != row['sha256']:
            raise KitError('Frozen task bytes changed')
        packet_json, packet_text = public_packet(parse_json(raw.decode('utf-8-sig')))
        packet_name = Path(row['file']).name
        if (read_bytes(session / 'packets' / packet_name) != packet_json or
                read_bytes(session / 'packets' / packet_name.replace('.json', '.txt')) != packet_text):
            raise KitError('Candidate packet differs from the frozen public task')
    if read_json(session / 'schedule.json') != indexed_schedule(plan):
        raise KitError('The trial order differs from the signed plan')
    return session, envelope, plan, observer, grader


def evidence(session, plan):
    observations, grades = [], []
    max_slots = len(native.schedule(plan))
    for folder in sorted((session / 'slots').iterdir()):
        if not folder.is_dir() or not re.fullmatch(r'\d{6}', folder.name):
            continue
        if int(folder.name) >= max_slots:
            raise KitError('Observation is outside the frozen schedule')
        observation = read_json(folder / 'observation.json')
        if (not isinstance(observation, dict) or not isinstance(observation.get('payload'), dict)
                or observation['payload'].get('scheduleIndex') != int(folder.name)):
            raise KitError('Observation is stored under a different schedule position')
        row = observation['payload']
        if row['state'] == 'DELIVERED':
            if sha(read_bytes(folder / 'candidate.txt', 65536)) != row['artifactDigest']:
                raise KitError('Candidate changed after observation')
            if sha(read_bytes(folder / 'capture.raw')) != row.get('captureDigest'):
                raise KitError('Native capture changed after observation')
        observations.append(observation)
        grade = session / 'grader' / folder.name / 'grade.json'
        if grade.exists():
            grades.append(read_json(grade))
    # Never silently omit a stale/orphan grade file.
    for grade in (session / 'grader').glob('*/grade.json'):
        if not (session / 'slots' / grade.parent.name / 'observation.json').exists():
            raise KitError('A grade has no matching native observation')
    return observations, grades


def comparison(session):
    session, envelope, plan, observer, grader = load_session(session)
    observations, grades = evidence(session, plan)
    result = native.compare_signed(envelope, observations, grades, raw_public(observer), raw_public(grader))
    return session, plan, result, observations, grades


def next_slot(session):
    session, _, plan, _, _ = load_session(session)
    for slot in indexed_schedule(plan):
        if not (session / 'slots' / f"{slot['scheduleIndex']:06d}" / 'observation.json').exists():
            selected = next(r for r in plan['kit']['tasks'] if r['taskDigest'] == slot['taskDigest'])
            template = {k: slot[k] for k in ('scheduleIndex', 'product', 'taskDigest', 'repeat', 'cache', 'version')}
            template.update(planDigest=digest(plan), controlsDigest=plan['controlsDigest'], seconds=None,
                            costUsd=None, interventions=None, state='DELIVERED', runId=None,
                            originEvidence=None, actualControlsConfirmed=False)
            return {'status': 'AWAITING_NATIVE_TRIAL', 'slot': slot, 'measurementsTemplate': template,
                    'promptFile': str(session / 'packets' / Path(selected['file']).name.replace('.json', '.txt')),
                    'note': 'Run manually on the native surface; this campaign controller does not initiate model requests. Record actual cost, or null.'}
    return {'status': 'ALL_OBSERVATIONS_PRESENT', 'nextAction': 'grade/recover, then report'}


def import_capture(session, slot_index, measurements_path, observer_key, capture_path=None, *, attest_origin=False,
                   _report=True):
    session = Path(session).resolve()
    with session_lock(session):
        session, envelope, plan, observer, grader = load_session(session)
        external_secret(observer_key, session)
        signer = key(observer_key, True)
        if raw_public(signer.public_key()) != raw_public(observer):
            raise KitError('Observer key differs from the frozen plan')
        if not attest_origin:
            raise KitError('An independent observer must explicitly attest origin and matched controls')
        schedule = indexed_schedule(plan)
        if type(slot_index) is not int or not 0 <= slot_index < len(schedule):
            raise KitError('Invalid schedule position')
        target = session / 'slots' / f'{slot_index:06d}'
        if target.exists():
            raise KitError('This slot is immutable and already observed; a new attempt must not replace it')
        # Importing a later row would obscure whether the randomized order was actually followed.
        if any(not (session / 'slots' / f'{i:06d}' / 'observation.json').exists() for i in range(slot_index)):
            raise KitError('Import the next scheduled slot; mark unsupported/harness/product failures explicitly')
        raw_capture = read_bytes(capture_path) if capture_path else None
        measurements_raw = read_bytes(measurements_path)
        stage = session / 'slots' / ('.staging-' + uuid.uuid4().hex)
        stage.mkdir()
        try:
            row = parse_json(measurements_raw.decode('utf-8-sig'))
            if not isinstance(row, dict):
                raise KitError('Measurements must be a JSON object')
            if row.get('actualControlsConfirmed') is not True or not isinstance(row.get('originEvidence'), str) or not row['originEvidence'].strip():
                raise KitError('Observer must record origin evidence and confirm the actual matched controls')
            expected = schedule[slot_index]
            for name in ('scheduleIndex', 'product', 'taskDigest', 'repeat', 'cache', 'version'):
                if row.get(name) != expected[name]:
                    raise KitError('Measured ' + name + ' differs from the frozen slot')
            if row.get('planDigest') != digest(plan) or row.get('controlsDigest') != plan['controlsDigest']:
                raise KitError('Measured plan or controls differ from preregistration')
            row = dict(row, schema=native.OBSERVATION)
            if row.get('state') == 'DELIVERED':
                if raw_capture is None:
                    raise KitError('A delivered slot requires the original native capture')
                capture_text = raw_capture.decode('utf-8-sig')
                # Decode an optional outer-container BOM only. The capture bytes and the
                # extracted answer, including an answer's own BOM/whitespace, remain intact.
                for text in (capture_text.splitlines() if row['product'] == 'Codex' else [capture_text]):
                    if text.strip():
                        parse_json(text)
                try:
                    received = native.native_answer(row['product'], capture_text)
                except (AttributeError, IndexError) as error:
                    raise KitError('Native capture has an invalid structure; no product outcome is inferred') from error
                row.update(runId=received['runId'], artifactDigest=received['artifactDigest'], captureDigest=sha(raw_capture))
                write_new(stage / 'capture.raw', raw_capture)
                write_new(stage / 'candidate.txt', received['answer'].encode('utf-8'))
            else:
                if raw_capture is not None:
                    raise KitError('Only delivered slots carry a candidate capture')
                if not native.sha(row.get('runId')):
                    raise KitError('A failure/unsupported slot requires the SHA-256 of a unique observed dispatch identity')
                if row.get('artifactDigest') is not None:
                    raise KitError('A non-delivery cannot carry an artifact digest')
            signed = attest(row, signer)
            observations, grades = evidence(session, plan)
            native.compare_signed(envelope, observations + [signed], grades, raw_public(observer), raw_public(grader))
            write_new(stage / 'measurements.json', measurements_raw)
            write_json_new(stage / 'observation.json', signed)
            stage.rename(target)
            audit(session, 'observation_imported', scheduleIndex=slot_index, runId=row['runId'],
                  state=row['state'], artifactDigest=row.get('artifactDigest'))
        except (ValueError, KeyError, TypeError, OSError, UnicodeDecodeError) as error:
            rejected = session / 'rejected' / (now().replace(':', '-') + '-' + uuid.uuid4().hex[:8])
            if stage.exists():
                stage.rename(rejected)
                if raw_capture is not None and not (rejected / 'capture.raw').exists():
                    write_new(rejected / 'capture.raw', raw_capture)
                if not (rejected / 'measurements.json').exists():
                    write_new(rejected / 'measurements.json', measurements_raw)
                write_json_new(rejected / 'rejection.json', {'scheduleIndex': slot_index, 'reason': str(error),
                    'classification': 'INVALID_IMPORT_NOT_A_PRODUCT_OUTCOME'})
                audit(session, 'import_rejected', scheduleIndex=slot_index, evidenceDirectory=str(rejected.relative_to(session)), reason=str(error))
            raise
    if _report:
        make_report(session)
    return {'scheduleIndex': slot_index, 'state': row['state'], 'runId': row['runId'], 'candidateDigest': row.get('artifactDigest')}


def verify_cfg_session(cfg, plan, session, grader_key=None):
    if plan['kit']['evidenceLane'] != REAL:
        raise KitError('Synthetic sessions are permanently barred from Gateway submission')
    if (cfg['source']['commit'] != plan['kit']['sourceCommit']
            or cfg['source'].get('runtimeDigest') != plan['kit'].get('runtimeDigest')):
        raise KitError('Installed grading runtime differs from the frozen session')
    if sha(raw_public(key(cfg['observerPublicKey']))) != plan['kit']['observerFingerprint'] or sha(raw_public(key(cfg['graderPublicKey']))) != plan['kit']['graderFingerprint']:
        raise KitError('Configured public keys differ from the independently archived plan')
    if grader_key:
        external_secret(grader_key, session)
        if sha(raw_public(key(grader_key, True).public_key())) != plan['kit']['graderFingerprint']:
            raise KitError('Grader key differs from preregistration')


def import_inbox(session, inbox, observer_key, *, attest_origin=False):
    inbox = Path(inbox).resolve()
    if not inbox.is_dir():
        raise KitError('An existing local inbox directory is required')
    if not attest_origin:
        raise KitError('The independent observer must explicitly attest each prepared inbox row')
    imported, committed = [], False
    try:
        while True:
            pending = next_slot(session)
            if pending['status'] != 'AWAITING_NATIVE_TRIAL':
                break
            index = pending['slot']['scheduleIndex']
            folder = inbox / f'{index:06d}'
            if not folder.is_dir():
                break
            measurements = folder / 'measurements.json'
            row = read_json(measurements)
            capture_path = folder / 'capture.raw' if row.get('state') == 'DELIVERED' else None
            try:
                imported.append(import_capture(session, index, measurements, observer_key, capture_path,
                                               attest_origin=True, _report=False))
            finally:
                # A crash/failure after the immutable rename can retain an
                # observation even before import_capture returns its receipt.
                committed = committed or (Path(session) / 'slots' / f'{index:06d}' / 'observation.json').is_file()
    finally:
        if committed:
            make_report(session)
    return {'imported': imported, 'next': next_slot(session),
            'note': 'Existing slots were preserved; only consecutive, independently attested inbox rows were imported.'}


def grade_session(session, cfg_path, grader_key, slot_index=None, *, recovery=False, bridge_call=bridge):
    session = Path(session).resolve()
    cfg = lab_config(cfg_path, need_source=not recovery)
    receipts = []
    with session_lock(session):
        session, envelope, plan, observer, grader = load_session(session)
        verify_cfg_session(cfg, plan, session, grader_key)
        # Validate every retained observation, capture and grade before the first
        # possible submission. One inventory per locked batch also keeps a full
        # campaign from repeatedly verifying all previous signatures per slot.
        observations, grades = evidence(session, plan)
        native.compare_signed(envelope, observations, grades, raw_public(observer), raw_public(grader))
        record_audit = audit_writer(session)
        pending_grades = []
        schedule = indexed_schedule(plan)
        if slot_index is not None and not 0 <= slot_index < len(schedule):
            raise KitError('Invalid schedule position')
        for slot in schedule:
            index = slot['scheduleIndex']
            if slot_index is not None and index != slot_index:
                continue
            folder = session / 'slots' / f'{index:06d}'
            observation_path = folder / 'observation.json'
            if not observation_path.exists():
                continue
            observation = native.verified(read_json(observation_path), observer, native.OBSERVATION)
            if observation['state'] != 'DELIVERED':
                continue
            area = session / 'grader' / f'{index:06d}'
            area.mkdir(exist_ok=True)
            if (area / 'grade.json').exists():
                receipts.append({'scheduleIndex': index, 'status': 'SIGNED_GRADE_PRESENT'})
                continue
            trial = area / 'trial.sqlite'
            candidate = folder / 'candidate.txt'
            if sha(read_bytes(candidate, 65536)) != observation['artifactDigest']:
                raise KitError('Candidate digest changed; stop before contacting the grader')
            selected = next(r for r in plan['kit']['tasks'] if r['taskDigest'] == slot['taskDigest'])
            request_id = None
            try:
                if not trial.exists():
                    if recovery:
                        receipts.append({'scheduleIndex': index, 'status': 'NOT_PREPARED', 'networkAction': None})
                        continue
                    bridge_call(cfg, 'prepare', {'task': str(safe_child(session, selected['file'])), 'source': slot['product'],
                        'candidate': str(candidate), 'trial': str(trial)})
                # Existing trials are always read back, even if the controller crashed before recording a reply.
                # A locally prepared but definitely never-attempted trial can be submitted by grade, never by recover.
                with contextlib.closing(sqlite3.connect(trial.resolve().as_uri() + '?mode=ro', uri=True)) as db:
                    saved_raw, attempted = db.execute('SELECT plan, attempted FROM trial WHERE id=1').fetchone()
                saved = json.loads(saved_raw)
                expected = saved['request']
                request_id = expected.get('request_id')
                if expected['fixture_digest'] != slot['taskDigest'] or expected['proposal_digest'] != observation['artifactDigest']:
                    raise KitError('Immutable trial belongs to another task or candidate')
                operation = 'status' if recovery or attempted else 'submit'
                body = {'trial': str(trial)}
                if operation == 'submit':
                    body['candidate'] = str(candidate)
                record_audit('grader_operation_started', scheduleIndex=index, operation=operation, requestId=expected['request_id'])
                status = bridge_call(cfg, operation, body)
                if status.get('request_id') != expected['request_id'] or status.get('proposal_digest') != observation['artifactDigest']:
                    raise KitError('Grader status does not match the frozen trial')
                atomic_write(area / 'status.json', json_bytes(status))
                record_audit('grader_readback', scheduleIndex=index, requestId=expected['request_id'],
                             submissionState=status.get('submissionState'), state=status.get('state'))
                if recovery and cfg.get('source') != runtime_identity():
                    receipts.append({'scheduleIndex': index, 'status': 'RUNTIME_CHANGED_READBACK_ONLY',
                        'submissionState': status.get('submissionState'), 'state': status.get('state'),
                        'requestId': expected['request_id'], 'operation': 'status',
                        'nextAction': 'Original request read back after an application update. Preserve the receipt; a changed runtime cannot sign a new grade for this frozen campaign.'})
                    continue
                if status.get('submissionState') == 'OBSERVED' and status.get('state') in TERMINAL:
                    signed = bridge_call(cfg, 'grade', {'trial': str(trial), 'plan': str(session / 'plan.json'),
                        'observation': str(observation_path), 'observer_public': str(session / 'public/observer.pem'),
                        'grader_key': str(Path(grader_key).resolve())})
                    pending_grades.append((area / 'grade.json', signed))
                receipts.append({'scheduleIndex': index, 'submissionState': status.get('submissionState'),
                    'state': status.get('state'), 'requestId': expected['request_id'], 'operation': operation})
            except (ValueError, KeyError, TypeError, OSError, sqlite3.Error) as error:
                record_audit('grader_operation_unresolved', scheduleIndex=index, reason=str(error))
                receipts.append({'scheduleIndex': index, 'status': 'UNRESOLVED', 'reason': str(error),
                    'requestId': request_id,
                    'nextAction': 'Keep the original trial; recover performs status only. Do not create a replacement identity.'})
        if pending_grades:
            native.compare_signed(envelope, observations, grades + [row for _, row in pending_grades],
                                  raw_public(observer), raw_public(grader))
            # A crash before promotion leaves the original trial/status for
            # readback, without admitting the candidate a second time.
            for path, signed in pending_grades:
                write_json_new(path, signed)
    make_report(session)
    return {'mode': 'STATUS_ONLY_RECOVERY' if recovery else 'SUPPLIED_ANSWER_GRADING', 'receipts': receipts, 'modelRequests': 0}


def recover_campaign(session, cfg_path, grader_key, slot_index=None, *, polls=1, interval_seconds=5,
                     grade_call=grade_session, sleep=time.sleep):
    if type(polls) is not int or not 1 <= polls <= 120 or not native.finite(interval_seconds, 0, 60):
        raise KitError('Use 1-120 bounded recovery polls and a 0-60 second interval')
    history = []
    for index in range(polls):
        result = grade_call(session, cfg_path, grader_key, slot_index, recovery=True)
        history.append({'poll': index + 1, 'at': now(), 'receipts': result['receipts']})
        pending = [r for r in result['receipts'] if r.get('status') == 'UNRESOLVED' or
                   r.get('submissionState') == 'NOT_OBSERVED' or
                   r.get('submissionState') == 'OBSERVED' and r.get('state') not in TERMINAL]
        if not pending or index + 1 == polls:
            break
        print(f'Recovery poll {index + 1}: {len(pending)} unresolved/pending trial(s); no submission will be repeated.', file=sys.stderr)
        sleep(interval_seconds)
    return {**result, 'pollsCompleted': len(history), 'pollHistory': history,
            'note': 'Every recovery operation is readback-only. Unknown admission remains unknown after the poll limit.'}


def retire(session, cfg_path, slot_index):
    session, _, plan, _, _ = load_session(session)
    cfg = lab_config(cfg_path)
    verify_cfg_session(cfg, plan, session)
    if type(slot_index) is not int or not 0 <= slot_index < len(native.schedule(plan)):
        raise KitError('Invalid slot')
    trial = session / 'grader' / f'{slot_index:06d}' / 'trial.sqlite'
    if not trial.exists():
        raise KitError('No original trial exists to retire')
    with session_lock(session):
        result = bridge(cfg, 'retire', {'trial': str(trial)})
        atomic_write(trial.parent / 'retirement.json', json_bytes(result))
        audit(session, 'retirement_readback', scheduleIndex=slot_index, receipt=result)
    return {'receipt': result, 'note': 'Keep this identity and all evidence. REMOVED plus NOT_DISPATCHED is required to establish no admission; no automatic replacement is created.'}


def fmt(value, places=2):
    if value is None:
        return 'Unknown'
    if isinstance(value, float):
        return f'{value:.{places}f}'
    return str(value)


def make_report(session, out=None):
    session = Path(session).resolve()
    with session_lock(session):
        session, plan, result, observations, grades = comparison(session)
        directory = guard_output(out) if out else session / 'reports'
        if contained(directory, PACKAGE) or contained(directory, session / 'tasks') or contained(directory, session / 'slots'):
            raise KitError('Choose a separate report directory')
        lane = plan['kit']['evidenceLane']
        details = []
        obs_by_index = {o['payload']['scheduleIndex']: o['payload'] for o in observations}
        grades_by_run = {g['payload']['runId']: g['payload'] for g in grades}
        for slot in indexed_schedule(plan):
            row = obs_by_index.get(slot['scheduleIndex'])
            grade = grades_by_run.get(row['runId']) if row else None
            details.append({**slot, 'observationState': row['state'] if row else 'MISSING_OBSERVATION',
                'independentGrade': grade.get('result') if grade else None,
                'seconds': row.get('seconds') if row else None, 'costUsd': row.get('costUsd') if row else None,
                'interventions': row.get('interventions') if row else None,
                # Canonical signed grades do not sign the secondary rubric or unsigned local readback metrics.
                'rawExecutableResolved': None, 'rubricScoreOutOf1000': None,
                'firstAttemptResult': None, 'bestWithinBudgetResult': None})
        report = {'schema': 'gpt-mobile-benchkit.scoreboard.v1', 'kitVersion': VERSION, 'generatedAt': now(),
            'evidenceLane': lane, 'sourceCommit': plan['kit']['sourceCommit'],
            'runtimeDigest': plan['kit'].get('runtimeDigest'), 'campaign': plan['kit']['campaignName'],
            'comparativeModelQuality': 'NOT_MEASURED' if lane == SYNTHETIC or not (
                any(g['payload']['result'] in ('PASS', 'FAIL') for g in grades) or
                any(o['payload']['state'] == 'PRODUCT_FAILURE' for o in observations)) else 'REGISTERED_SUITE_ONLY',
            'nativeComparison': result, 'products': result['performance'], 'coverage': result['coverage'], 'slots': details,
            'githubIntelligencePointsOutOf100': None, 'firstAttemptComparison': None, 'bestWithinBudgetComparison': None,
            'boundaries': ['No claim about unobserved provider identity or model settings.',
                'Synthetic examples test the controller, not any product.',
                'Known failures are retained; missing observations and cost stay unknown.',
                'Timing percentiles include every observed state, including failures, harness errors and unsupported routes.',
                'The ten-job GitHub Intelligence Score requires separate production execution receipts.',
                'Unsigned Gateway readbacks are retained for inspection; secondary rubric values are not promoted into this signed comparison.',
                'A statistical verdict is scoped to the signed suite, versions, controls, budgets and task-family assumptions.'],
            'keyFingerprints': {'observer': plan['kit']['observerFingerprint'], 'grader': plan['kit']['graderFingerprint']}}
        directory.mkdir(parents=True, exist_ok=True)
        # Preserve canonical CLI replay inputs separately from the shareable scoreboard.
        # Observer provenance notes can be private; do not mix them into report exports.
        atomic_write(session / 'receipts/observations.json', json_bytes(observations))
        atomic_write(session / 'receipts/grades.json', json_bytes(grades))
        atomic_write(directory / 'comparison.json', json_bytes(result))
        atomic_write(directory / 'scoreboard.json', json_bytes(report))
        title = 'SYNTHETIC FIXTURE DEMO - NOT PRODUCT RESULTS' if lane == SYNTHETIC else 'Native product benchmark scoreboard'
        md = ['# ' + title, '', f"**Verdict: {result['verdict']}** | Campaign: {plan['kit']['campaignName']} | Lane: {lane}", '',
              'Missing evidence is Unknown. No ranks are assigned from partial results.', '',
              '| Product | Observed / planned | Verified within budget | Complete verified rate | Seconds p50 / p95 | Observed cost USD | Cost coverage |',
              '| --- | ---: | ---: | ---: | ---: | ---: | ---: |']
        csv_rows = []
        for product, metrics in result['performance'].items():
            rate = None if metrics['verifiedRate'] is None else 100 * metrics['verifiedRate']
            md.append(f"| {product} | {metrics['observed']} / {metrics['planned']} | {metrics['verifiedWithinBudget']} | {fmt(rate)}{'%' if rate is not None else ''} | {fmt(metrics['elapsedSecondsP50'])} / {fmt(metrics['elapsedSecondsP95'])} | {fmt(metrics['observedCostUsd'], 4)} | {metrics['costObserved']} / {metrics['planned']} |")
            csv_rows.append({'product': product, 'evidenceLane': lane, **metrics})
        md += ['', '## Paired comparisons: GPT Mobile AI minus peer', '',
               '| Peer | Missing pairs | Independent families observed | Difference (points) | Simultaneous 95% lower bound | Clears margin |',
               '| --- | ---: | ---: | ---: | ---: | --- |']
        for peer, row in result['comparisonsAgainstMobile'].items():
            md.append(f"| {peer} | {row['missingPairs']} | {row['independentFamiliesObserved']} | {fmt(row['deltaPoints'])} | {fmt(row['lowerSimultaneous95Points'])} | {'Yes' if row['clearsMargin'] else 'Not established'} |")
        md += ['', '## Evidence coverage', '']
        for product, counts in result['coverage'].items():
            md.append('- **' + product + ':** ' + ', '.join(f'{name}: {count}' for name, count in counts.items()))
        md += ['', '## Separate evidence boundaries', '', 'GitHub Intelligence Score: **Unknown**. First attempt: **Unknown**. Best within budget: **Unknown**.', '', *['- ' + item for item in report['boundaries']], '',
               'Public-key fingerprints (check against the independently archived keys):', '',
               '- Observer: `' + plan['kit']['observerFingerprint'] + '`', '- Grader: `' + plan['kit']['graderFingerprint'] + '`', '',
               'Raw details: scoreboard.json, comparison.json, scoreboard.csv, trial-details.csv. Candidate text is intentionally excluded from these reports.', '']
        atomic_write(directory / 'scoreboard.md', '\n'.join(md).encode('utf-8'))
        for filename, rows in [('scoreboard.csv', csv_rows), ('trial-details.csv', details)]:
            buffer = io.StringIO(newline='')
            writer = csv.DictWriter(buffer, fieldnames=list(rows[0]) if rows else ['status'])
            writer.writeheader()
            for row in rows:
                writer.writerow({k: '' if v is None else v for k, v in row.items()})
            atomic_write(directory / filename, buffer.getvalue().encode('utf-8'))
        atomic_write(directory / 'scoreboard.html', render_html(report, title).encode('utf-8'))
    return {'directory': str(directory), 'scoreboard': str(directory / 'scoreboard.html'), 'verdict': result['verdict'], 'evidenceLane': lane}


def render_html(report, title):
    esc = html.escape
    result = report['nativeComparison']
    body = []
    for product, p in result['performance'].items():
        rate = 'Unknown' if p['verifiedRate'] is None else f"{100*p['verifiedRate']:.1f}%"
        body.append('<tr><th>' + esc(product) + '</th>' + ''.join('<td>' + esc(str(x)) + '</td>' for x in
            [f"{p['observed']} / {p['planned']}", p['verifiedWithinBudget'], rate,
             f"{fmt(p['elapsedSecondsP50'])} / {fmt(p['elapsedSecondsP95'])}", fmt(p['observedCostUsd'], 4), f"{p['costObserved']} / {p['planned']}"]) + '</tr>')
    pairs = []
    for peer, p in result['comparisonsAgainstMobile'].items():
        pairs.append('<tr><th>' + esc(peer) + '</th>' + ''.join('<td>' + esc(str(x)) + '</td>' for x in
            [p['missingPairs'], p['independentFamiliesObserved'], fmt(p['deltaPoints']), fmt(p['lowerSimultaneous95Points']),
             'Yes' if p['clearsMargin'] else 'Not established']) + '</tr>')
    coverage = ''.join('<section class="card"><h3>' + esc(p) + '</h3><dl>' + ''.join('<dt>'+esc(k)+'</dt><dd>'+str(v)+'</dd>' for k,v in counts.items()) + '</dl></section>' for p,counts in result['coverage'].items())
    note = 'Synthetic numbers exercise code paths. They say nothing about product intelligence.' if report['evidenceLane'] == SYNTHETIC else 'Only signed observations and independent grades enter this view. No product winner is inferred from missing evidence.'
    return '''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Benchmark scoreboard</title>
<style>:root{color-scheme:light;--ink:#162c36;--muted:#52656e;--line:#cbd6d9;--teal:#006b68;--paper:#f3f6f5}*{box-sizing:border-box}body{margin:0;background:var(--paper);color:var(--ink);font:16px/1.55 Segoe UI,Arial,sans-serif}main{max-width:1240px;margin:auto;padding:40px 28px}header{border-top:6px solid var(--teal);padding-top:22px}h1{font-size:34px;line-height:1.2;max-width:980px;margin:10px 0}h2{font-size:23px;margin-top:36px}h3{font-size:17px;margin:0 0 12px}.eyebrow{letter-spacing:.12em;font-size:12px;font-weight:700;color:var(--teal)}.meta{color:var(--muted);font-size:14px}.badge{display:inline-block;background:#fff0bd;color:#5a4100;border:1px solid #e2c975;border-radius:30px;padding:7px 16px;font-weight:700}.notice{background:#fff8de;border-left:4px solid #c18c00;padding:15px 18px;margin:22px 0}.wrap{overflow:auto;background:white;border:1px solid var(--line);border-radius:10px}table{border-collapse:collapse;width:100%;font-size:14px}th,td{text-align:right;padding:15px 14px;border-bottom:1px solid #dce4e6;white-space:nowrap}th:first-child{text-align:left}thead{background:#e5eeed;color:#294b51}tbody tr:last-child>*{border-bottom:0}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:14px}.card{background:white;border:1px solid var(--line);border-radius:10px;padding:20px}dl{display:grid;grid-template-columns:1fr auto;gap:9px;font-size:13px}dt{overflow-wrap:anywhere}dd{margin:0;font-weight:700}.unknown{font-size:24px;font-weight:650;color:#6c7680}.boundary{color:var(--muted);font-size:14px}code{word-break:break-all}a{color:var(--teal)}footer{border-top:1px solid var(--line);margin-top:36px;padding-top:18px;font-size:12px;color:var(--muted)}@media print{body{background:white}main{padding:0}.wrap{overflow:visible}table{font-size:10px}th,td{padding:7px}.notice{break-inside:avoid}}@media(max-width:650px){main{padding:22px 14px}h1{font-size:28px}}</style></head><body><main><header><div class="eyebrow">GPT MOBILE AI · INDEPENDENT BENCHMARK KIT</div><h1>''' + esc(title) + '''</h1><p class="meta">''' + esc(report['campaign'] + ' · ' + report['evidenceLane'] + ' · Generated ' + report['generatedAt']) + '''</p><span class="badge">''' + esc(result['verdict']) + '''</span></header><div class="notice">''' + esc(note) + '''</div>
<h2>Observed product outcomes</h2><p class="boundary">Verified rate stays Unknown until all planned outcomes are known. Costs show observed amounts only; blank CSV cells mean unknown.</p><div class="wrap"><table><thead><tr><th>Product</th><th>Observed / planned</th><th>Verified in budget</th><th>Complete rate</th><th>Seconds p50 / p95</th><th>Observed USD</th><th>Cost coverage</th></tr></thead><tbody>''' + ''.join(body) + '''</tbody></table></div>
<h2>Paired comparison: GPT Mobile AI minus peer</h2><p class="boundary">Equal family weighting; repeats and related variants stay in their family. The upstream simultaneous 95% lower bounds are conservative. Preregistered margin: ''' + esc(fmt(result['marginPoints'])) + ''' points.</p><div class="wrap"><table><thead><tr><th>Peer</th><th>Missing pairs</th><th>Families observed</th><th>Difference (points)</th><th>95% lower bound</th><th>Clears margin</th></tr></thead><tbody>''' + ''.join(pairs) + '''</tbody></table></div><h2>Evidence coverage</h2><div class="grid">''' + coverage + '''</div><h2>Measurements that remain separate</h2><div class="grid"><section class="card"><h3>GitHub Intelligence / 100</h3><div class="unknown">Unknown</div><p>Needs ten production-job receipt sets.</p></section><section class="card"><h3>First attempt</h3><div class="unknown">Unknown</div><p>A final export does not establish the first patch.</p></section><section class="card"><h3>Best within budget</h3><div class="unknown">Unknown</div><p>No candidate selection from unobserved attempts.</p></section></div><h2>Interpretation</h2><ul class="boundary">''' + ''.join('<li>'+esc(v)+'</li>' for v in report['boundaries']) + '''</ul><p><a href="scoreboard.json">Full JSON</a> · <a href="comparison.json">Canonical comparison</a> · <a href="scoreboard.csv">Product CSV</a> · <a href="trial-details.csv">Trial details CSV</a> · <a href="scoreboard.md">Markdown</a></p><footer>Plan digest: <code>''' + esc(result['planDigest']) + '''</code><br>Inspected source: <code>''' + esc(report['sourceCommit']) + '''</code><br>Observer key fingerprint: <code>''' + esc(report['keyFingerprints']['observer']) + '''</code><br>Grader key fingerprint: <code>''' + esc(report['keyFingerprints']['grader']) + '''</code><br>Compare fingerprints with the independently archived public keys. Candidate text and private keys are not included in this report.</footer></main></body></html>'''


def select_chatgpt(export, conversation_id, out):
    # Large account exports are read only for explicit local selection; never uploaded.
    data = read_json(export, 256 * 1024 * 1024)
    rows = data if isinstance(data, list) else [data]
    matches = [r for r in rows if isinstance(r, dict) and (r.get('conversation_id') or r.get('id')) == conversation_id]
    if len(matches) != 1:
        raise KitError('Exactly one selected conversation is required')
    native.native_answer('ChatGPT', json.dumps(matches[0], ensure_ascii=False))
    write_json_new(out, matches[0])
    return {'output': str(Path(out).resolve()), 'conversations': 1,
            'note': 'No branches, nodes, turns or malformed answer text were edited to make the capture pass.'}


def doctor(config=None):
    import cryptography
    checks = [{'name': 'Python 3.10+', 'ok': sys.version_info >= (3, 10), 'observed': sys.version.split()[0]},
              {'name': 'cryptography', 'ok': True, 'observed': cryptography.__version__},
              {'name': 'Installed grading runtime', 'ok': True, 'observed': runtime_identity()}]
    if config:
        cfg = lab_config(config)
        checks.append({'name': 'Gateway settings file',
                       'ok': (Path(cfg['gateway']['root']) / 'settings.json').is_file()})
    return {'checks': checks, 'networkRequests': 0, 'modelRequests': 0, 'autoInstall': False,
            'note': 'Local inspection only. No model warm-up, Docker pull or product generation.'}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    command = commands.add_parser('doctor'); command.add_argument('--config')
    command = commands.add_parser('keygen'); command.add_argument('--role', choices=['observer', 'grader'], required=True); command.add_argument('--out', required=True)
    command = commands.add_parser('export-tasks')
    for option in ('config', 'campaign', 'out'): command.add_argument('--' + option, required=True)
    command = commands.add_parser('init')
    for option in ('config', 'campaign', 'tasks', 'out', 'observer-key'): command.add_argument('--' + option, required=True)
    command = commands.add_parser('next'); command.add_argument('--session', required=True); command.add_argument('--measurements-out')
    for name in ('import', 'record'):
        command = commands.add_parser(name)
        for option in ('session', 'measurements', 'observer-key'): command.add_argument('--' + option, required=True)
        command.add_argument('--slot', type=int, required=True)
        command.add_argument('--attest-origin', action='store_true')
        if name == 'import': command.add_argument('--capture', required=True)
    command = commands.add_parser('import-inbox')
    for option in ('session', 'inbox', 'observer-key'): command.add_argument('--' + option, required=True)
    command.add_argument('--attest-origin', action='store_true')
    for name in ('grade', 'recover'):
        command = commands.add_parser(name)
        for option in ('session', 'config', 'grader-key'): command.add_argument('--' + option, required=True)
        command.add_argument('--slot', type=int)
        if name == 'recover':
            command.add_argument('--polls', type=int, default=1)
            command.add_argument('--interval-seconds', type=float, default=5)
    command = commands.add_parser('retire')
    command.add_argument('--session', required=True); command.add_argument('--config', required=True); command.add_argument('--slot', type=int, required=True)
    command = commands.add_parser('report'); command.add_argument('--session', required=True); command.add_argument('--out')
    command = commands.add_parser('demo'); command.add_argument('--out', required=True)
    command = commands.add_parser('select-chatgpt')
    for option in ('export', 'conversation-id', 'out'): command.add_argument('--' + option, required=True)
    command = commands.add_parser('capture-mobile')
    for option in ('config', 'conversation-id', 'out'): command.add_argument('--' + option, required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == 'doctor': result = doctor(args.config)
        elif args.command == 'keygen': result = keygen(args.role, args.out)
        elif args.command == 'export-tasks': result = export_tasks(args.config, args.campaign, args.out)
        elif args.command == 'init': result = initialize(args.config, args.campaign, args.tasks, args.out, args.observer_key)
        elif args.command == 'next':
            result = next_slot(args.session)
            if args.measurements_out:
                if 'measurementsTemplate' not in result: raise KitError('No pending slot')
                write_json_new(args.measurements_out, result['measurementsTemplate'])
        elif args.command in ('import', 'record'):
            result = import_capture(args.session, args.slot, args.measurements, args.observer_key,
                                    getattr(args, 'capture', None), attest_origin=args.attest_origin)
        elif args.command == 'import-inbox':
            result = import_inbox(args.session, args.inbox, args.observer_key, attest_origin=args.attest_origin)
        elif args.command == 'grade':
            result = grade_session(args.session, args.config, args.grader_key, args.slot)
        elif args.command == 'recover':
            result = recover_campaign(args.session, args.config, args.grader_key, args.slot,
                                      polls=args.polls, interval_seconds=args.interval_seconds)
        elif args.command == 'retire': result = retire(args.session, args.config, args.slot)
        elif args.command == 'report': result = make_report(args.session, args.out)
        elif args.command == 'demo':
            from benchmarks.campaign_synthetic import create_demo
            result = create_demo(args.out)
        elif args.command == 'select-chatgpt': result = select_chatgpt(args.export, args.conversation_id, args.out)
        else:
            cfg = lab_config(args.config)
            result = bridge(cfg, 'read-capture', {'conversation_id': args.conversation_id})
            native.native_answer('GPT Mobile AI', json.dumps(result, ensure_ascii=False))
            write_json_new(args.out, result)
            result = {'output': str(Path(args.out).resolve()), 'modelRequests': 0}
        print(json.dumps(result, indent=2, ensure_ascii=False, allow_nan=False))
        if args.command in ('grade', 'recover') and any(r.get('status') == 'UNRESOLVED' for r in result['receipts']):
            return 3
        return 0
    except (ValueError, KeyError, TypeError, OSError, sqlite3.Error) as error:
        print('Native benchmark campaign: ' + str(error), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
