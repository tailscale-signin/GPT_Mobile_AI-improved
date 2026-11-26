"""Offline fixtures. Never call these grades product measurements."""
from __future__ import annotations
import json
from pathlib import Path
import shutil
import tempfile

from benchmarks import campaign as kit


def capture(product, answer, session_id='synthetic-session'):
    """Return UTF-8 native container bytes; final answer bytes are not normalized."""
    if product == 'Codex':
        rows = [{'type': 'thread.started', 'thread_id': session_id}, {'type': 'turn.started'},
                {'type': 'item.completed', 'item': {'type': 'agent_message', 'phase': 'commentary', 'text': 'SYNTHETIC progress excluded from answer'}},
                {'type': 'item.completed', 'item': {'type': 'agent_message', 'phase': 'final_answer', 'text': answer}},
                {'type': 'turn.completed'}]
        return ('\n'.join(json.dumps(row, ensure_ascii=False) for row in rows) + '\n').encode('utf-8')
    if product == 'Claude Code':
        row = {'type': 'result', 'subtype': 'success', 'is_error': False, 'session_id': session_id, 'result': answer}
    elif product == 'GPT Mobile AI':
        row = {'conversation': {'conversation_id': session_id, 'history_complete': True, 'job_state': 'COMPLETED'},
               'messages': [{'role': 'user', 'content': 'SYNTHETIC one task', 'command_id': 'synthetic-command'},
                            {'role': 'assistant', 'content': answer, 'command_id': 'synthetic-command', 'status': 'completed'}]}
    elif product == 'ChatGPT':
        ids = ['root', 'task', 'tool-call', 'tool-result', 'answer']
        messages = [None,
            {'author': {'role': 'user'}, 'content': {'content_type': 'text', 'parts': ['SYNTHETIC one task']}},
            {'author': {'role': 'assistant'}, 'recipient': 'github.search', 'end_turn': False,
             'content': {'content_type': 'code', 'text': 'SYNTHETIC tool request'}},
            {'author': {'role': 'tool'}, 'content': {'content_type': 'text', 'parts': ['SYNTHETIC private tool text excluded']}},
            {'author': {'role': 'assistant'}, 'recipient': 'all', 'end_turn': True, 'status': 'finished_successfully',
             'metadata': {'finish_details': {'type': 'stop'}}, 'content': {'content_type': 'text', 'parts': [answer]}}]
        row = {'id': session_id, 'current_node': ids[-1], 'mapping': {name: {
            'id': name, 'parent': ids[index - 1] if index else None,
            'children': ids[index + 1:index + 2], 'message': messages[index]} for index, name in enumerate(ids)}}
    else:
        raise kit.KitError('Unknown synthetic capture product')
    return kit.json_bytes(row)


def build_inputs(base, products=kit.PRODUCTS, task_count=2, lane=kit.SYNTHETIC):
    """Build in a temporary controller directory, outside the kit/repository."""
    if lane != kit.SYNTHETIC:
        raise kit.KitError('Synthetic builders cannot mint measured evidence lanes')
    base = Path(base).resolve()
    base.mkdir(parents=True, exist_ok=True)
    secrets = base / 'private-keys'
    observer = kit.keygen('observer', secrets)
    grader = kit.keygen('grader', secrets)
    source = kit.runtime_identity()
    ref = source['commit']
    lab = kit.read_json(kit.ASSETS / 'config/lab.example.json')
    lab['source'] = source
    lab['gateway']['allowedOrigin'] = 'http://127.0.0.1:8090'
    lab['controls']['startingRevision'] = ref
    lab['gateway']['root'] = str(base / 'unused-gateway-root')
    lab.update(observerPublicKey=observer['publicKey'], graderPublicKey=grader['publicKey'])
    lab['controls'].update(hardwareClass='SYNTHETIC fixture controller',
        commonToolPolicy='SYNTHETIC: no tools, models, network or native product execution',
        cacheResetProcedure='SYNTHETIC independent fixture containers',
        costAccountingBasis='SYNTHETIC explicit fixture numbers only; no billing observations')
    for product in products:
        lab['products'][product].update(version='SYNTHETIC-NOT-A-PRODUCT-VERSION',
            surface='SYNTHETIC native-format fixture', toolAccess='NONE', requestedSettings={})
    campaign = {'schema': 'gpt-mobile-benchkit.campaign.v1', 'name': 'synthetic-controller-demo',
        'products': list(products), 'cache': 'cold', 'repeats': 1, 'maxSeconds': 30, 'maxCostUsd': 0,
        'marginPoints': 5, 'tasks': [{'id': f'synthetic-task-{i+1}', 'pack': 'python', 'seed': 17,
            'family': f'synthetic-family-{i+1}'} for i in range(task_count)]}
    paths = {'config': base / 'lab.json', 'campaign': base / 'campaign.json', 'tasks': base / 'tasks',
             'observer_key': Path(observer['privateKey']), 'grader_key': Path(grader['privateKey'])}
    kit.write_json_new(paths['config'], lab)
    kit.write_json_new(paths['campaign'], campaign)
    paths['tasks'].mkdir()
    entries = []
    for spec in campaign['tasks']:
        row = {'id': spec['id'], 'seed': spec['seed'], 'taskPack': spec['pack'], 'family': spec['family'],
            'prompt': 'SYNTHETIC controller fixture. This is not a model benchmark task. Return the literal fixture answer.',
            'outputSchema': {'type': 'object', 'properties': {'synthetic': {'const': True}}},
            'taskDigest': kit.digest(['SYNTHETIC', spec]), 'graderDigest': kit.digest('SYNTHETIC hidden grader stand-in'),
            'environmentDigest': kit.digest('SYNTHETIC no execution environment'),
            'gatewayOwnerDigest': kit.digest('SYNTHETIC no Gateway owner'), 'synthetic': True}
        filename = kit.task_filename(spec)
        kit.write_json_new(paths['tasks'] / filename, row)
        entries.append({'file': filename, 'sha256': kit.sha(kit.read_bytes(paths['tasks'] / filename)), **spec})
    kit.write_json_new(paths['tasks'] / 'export-manifest.json', {'schema': 'gpt-mobile-benchkit.task-export.v1',
        'sourceCommit': ref, 'runtimeDigest': source['runtimeDigest'], 'exportedAt': kit.now(), 'evidenceLane': kit.SYNTHETIC,
        'campaignDigest': kit.digest(campaign), 'files': entries})
    return paths


def measurement(session, slot_index, **updates):
    session, _, plan, _, _ = kit.load_session(session)
    slot = kit.indexed_schedule(plan)[slot_index]
    row = {k: slot[k] for k in ('product', 'version', 'taskDigest', 'repeat', 'scheduleIndex', 'cache')}
    row.update(planDigest=kit.digest(plan), controlsDigest=plan['controlsDigest'], seconds=2.5, costUsd=0,
               interventions=0, state='DELIVERED', runId=None,
               originEvidence='SYNTHETIC: constructed offline fixture, no product origin claimed', actualControlsConfirmed=True)
    row.update(updates)
    return row


def fixture_grade(session, slot_index, grader_key, result='PASS'):
    session, envelope, plan, observer, grader = kit.load_session(session)
    if plan['kit']['evidenceLane'] != kit.SYNTHETIC:
        raise kit.KitError('Fixture grades cannot be used on a measured campaign')
    observation = kit.read_json(session / 'slots' / f'{slot_index:06d}' / 'observation.json')['payload']
    case = next(c for c in plan['cases'] if c['taskDigest'] == observation['taskDigest'])
    row = {'schema': kit.native.GRADE, 'planDigest': kit.digest(plan), 'taskDigest': case['taskDigest'],
           'graderDigest': case['graderDigest'], 'environmentDigest': case['environmentDigest'],
           'artifactDigest': observation['artifactDigest'], 'runId': observation['runId'],
           'graderRunId': 'SYNTHETIC-NOT-EXECUTED-' + observation['runId'], 'result': result}
    signed = kit.attest(row, kit.key(grader_key, True))
    observations, grades = kit.evidence(session, plan)
    kit.native.compare_signed(envelope, observations, grades + [signed], kit.raw_public(observer), kit.raw_public(grader))
    kit.write_json_new(session / 'grader' / f'{slot_index:06d}' / 'grade.json', signed)
    return signed


def create_demo(out):
    out = kit.guard_output(out)
    if out.exists():
        raise kit.KitError('Choose a new demo directory; evidence is never overwritten')
    out.mkdir(parents=True)
    with tempfile.TemporaryDirectory(prefix='benchkit-synthetic-') as scratch:
        inputs = build_inputs(Path(scratch))
        session = out / 'session'
        kit.initialize(inputs['config'], inputs['campaign'], inputs['tasks'], session,
                       inputs['observer_key'], synthetic=True)
        shutil.copytree(session / 'reports', out / 'unmeasured-scoreboard')
        _, _, plan, _, _ = kit.load_session(session)
        for index, slot in enumerate(kit.indexed_schedule(plan)):
            # Designed code-path coverage, NOT outcomes attributable to these product labels.
            mode = ['PASS', 'COST_UNKNOWN', 'FAIL', 'GRADER_UNAVAILABLE',
                    'PRODUCT_FAILURE', 'HARNESS_FAILURE', 'UNSUPPORTED', 'OVER_BUDGET'][index % 8]
            row = measurement(session, index)
            capture_path = Path(scratch) / f'capture-{index}.raw'
            if mode in ('PRODUCT_FAILURE', 'HARNESS_FAILURE', 'UNSUPPORTED'):
                row.update(state=mode, runId=kit.digest(['SYNTHETIC dispatch', index, slot]), costUsd=None)
                capture_path = None
            else:
                answer = ' {"synthetic":true,"utf8":"caf\u00e9","malformedIsPreserved":true}\r\n  '
                kit.write_new(capture_path, capture(slot['product'], answer, f'SYNTHETIC-session-{index}'))
                if mode == 'COST_UNKNOWN': row['costUsd'] = None
                if mode == 'OVER_BUDGET': row['seconds'] = plan['maxSeconds'] + 1
            measurements_path = Path(scratch) / f'measurements-{index}.json'
            kit.write_json_new(measurements_path, row)
            kit.import_capture(session, index, measurements_path, inputs['observer_key'], capture_path, attest_origin=True)
            if capture_path:
                result = mode if mode in ('FAIL', 'GRADER_UNAVAILABLE') else 'PASS'
                fixture_grade(session, index, inputs['grader_key'], result)
        report = kit.make_report(session)
        kit.write_json_new(out / 'DEMO-ONLY.json', {'evidenceLane': kit.SYNTHETIC,
            'productsExecuted': False, 'paidRequests': 0, 'networkRequests': 0,
            'grades': 'Artificial signed fixture receipts testing the comparison engine, not executable task grades',
            'privateKeysIncluded': False, 'liveGatewaySubmissionPermitted': False})
    return {**report, 'session': str(session), 'unmeasuredScoreboard': str(out / 'unmeasured-scoreboard/scoreboard.html'),
            'scope': 'SYNTHETIC_CONTROLLER_DEMO_ONLY', 'liveProductsMeasured': False, 'networkRequests': 0, 'paidRequests': 0}
