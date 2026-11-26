"""Installed desktop workspace for signed, matched native-product campaigns.

Generation happens on the selected product surface. This module only exports
public tasks, imports existing captures and delegates supplied-answer grading to
the canonical benchmark controller. Private signing keys are never copied into
the workspace or a report.
"""
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import re
import shutil
import uuid

from desktop.configuration import atomic_json
from desktop.conversation_client import Client, ClientError, scope_key
from v14.state_lock import StateDirectoryLock

SCHEMA = 'gateway.desktop.native-product-campaign.v1'
IDENTIFIER = re.compile(r'campaign_[a-f0-9]{32}')
MAX_SCHEDULE_ROWS = 500


def _engine():
    from benchmarks import campaign
    return campaign


def _inside(path, parent):
    try:
        Path(path).resolve().relative_to(Path(parent).resolve())
        return True
    except ValueError:
        return False


def _directory(root):
    root = Path(root).resolve()
    result = root / 'data' / 'native-product-campaigns'
    if not _inside(result, root) or any(part.is_symlink() for part in (root / 'data', result)):
        raise ValueError('The campaign directory must stay inside this Gateway state directory.')
    return result


def _workspace(root, ident):
    if not isinstance(ident, str) or not IDENTIFIER.fullmatch(ident):
        raise ValueError('Choose a saved native product campaign.')
    result = _directory(root) / ident
    if result.is_symlink() or not _inside(result, _directory(root)):
        raise ValueError('The campaign workspace has moved outside its original directory.')
    return result


def _file(value, *, optional=False):
    if optional and value in (None, ''):
        return None
    if not isinstance(value, (str, Path)) or not str(value) or len(str(value)) > 4096:
        raise ValueError('Choose an existing local file.')
    path = Path(value).expanduser()
    if not path.is_absolute() or not path.is_file():
        raise ValueError('Choose an existing file using its absolute path.')
    return path.resolve()


def _destination(root, value):
    if not isinstance(value, (str, Path)) or not str(value) or len(str(value)) > 4096:
        raise ValueError('Choose a new output directory.')
    path = Path(value).expanduser()
    if not path.is_absolute():
        raise ValueError('Choose a new output directory using its absolute path.')
    path = _engine().guard_output(path)
    if _inside(path, root):
        raise ValueError('Choose an output directory outside the Gateway installation and campaign state.')
    if path.exists():
        raise ValueError('Choose a new directory; existing files are never replaced.')
    return path


def _key_file(root, value):
    path = _file(value)
    _engine().external_secret(path, repo=Path(root).resolve())
    return path


@contextmanager
def _lock(root, ident=None):
    location = _workspace(root, ident) / 'controller-lock' if ident else _directory(root) / 'controller-lock'
    if location.is_symlink():
        raise ValueError('The campaign lock cannot be a symbolic link.')
    lock = StateDirectoryLock(location / 'owner')
    lock.acquire()
    try:
        yield
    finally:
        lock.release()


def _read_metadata(root, ident):
    workspace = _workspace(root, ident)
    path = workspace / 'desktop.json'
    if path.is_symlink():
        raise ValueError('The campaign metadata cannot be a symbolic link.')
    value = _engine().read_json(path, 64 * 1024)
    if not isinstance(value, dict) or value.get('schema') != SCHEMA or value.get('id') != ident:
        raise ValueError('The saved native product campaign is invalid.')
    for name in ('session', 'task-export', 'measurements'):
        if (workspace / name).is_symlink() or not _inside(workspace / name, workspace):
            raise ValueError('Campaign evidence must stay inside its original workspace.')
    for name in ('lab.json', 'campaign.json'):
        saved = workspace / name
        if saved.is_symlink() or hashlib.sha256(_engine().read_bytes(saved)).hexdigest() != value.get('files', {}).get(name):
            raise ValueError('The frozen campaign setup changed. Restore the original files before continuing.')
    return workspace, value


def _save_metadata(workspace, value):
    atomic_json(workspace / 'desktop.json', value)


def _owner(root, value=None):
    client = Client(root)
    observed = client.identity()
    if value and (scope_key(observed) != scope_key(value.get('owner', {})) or client.origin != value.get('origin')):
        raise ClientError('Restore the original Gateway and account before continuing this campaign.',
                          code='identity_changed', not_dispatched=True)
    return client, {key: observed[key] for key in ('gateway_id', 'principal_id')}


def templates(root, path):
    """Copy editable, unmeasured controls and both campaign presets."""
    engine = _engine()
    target = _destination(root, path)
    source = Path(engine.__file__).resolve().parent / 'campaign_assets'
    if not (source / 'config' / 'lab.example.json').is_file():
        raise ValueError('The installed campaign templates are unavailable. Repair this Gateway installation.')
    shutil.copytree(source, target)
    shutil.copyfile(source.parent / 'NATIVE_CAMPAIGNS.md', target / 'GUIDE.md')
    config_path = target / 'config' / 'lab.example.json'
    config = engine.read_json(config_path)
    config['source'] = engine.runtime_identity()
    # Reading installed settings does not connect, create a job or invoke a model.
    config['gateway'] = {'root': str(Path(root).resolve()), 'allowedOrigin': Client(root).origin}
    atomic_json(config_path, config)
    return {'directory': str(target), 'configPath': str(config_path),
            'campaigns': {name: str(target / 'config' / (name + '.json')) for name in ('quick', 'comprehensive')},
            'modelRequests': 0}


def keys(root, role, path):
    target = _destination(root, path)
    return _engine().keygen(role, target)


def _setup(root, workspace, value, observer_key):
    engine = _engine()
    _owner(root, value)
    config_path, campaign_path = workspace / 'lab.json', workspace / 'campaign.json'
    exported, session = workspace / 'task-export', workspace / 'session'
    if not session.exists():
        if not exported.exists():
            engine.export_tasks(config_path, campaign_path, exported)
        expected_owner = engine.digest(scope_key(value['owner']))
        for task in engine.campaign_config(campaign_path)['tasks']:
            observed = engine.read_json(exported / engine.task_filename(task), 2 * 1024 * 1024)
            if observed.get('gatewayOwnerDigest') != expected_owner:
                raise ClientError('The exported task belongs to another Gateway or account. Preserve it and restore the original owner.',
                                  code='identity_changed', not_dispatched=True)
        _owner(root, value)
        engine.initialize(config_path, campaign_path, exported, session, observer_key)
    # A crash after the canonical atomic rename can only reconcile that same plan.
    _, _, plan, _, _ = engine.load_session(session)
    config = engine.lab_config(config_path, need_source=False)
    selected = engine.campaign_config(campaign_path)
    if (plan['kit']['campaignDigest'] != engine.digest(selected)
            or plan['controlsDigest'] != engine.digest(engine.freeze_controls(config, selected))
            or plan['kit']['sourceCommit'] != config['source']['commit']
            or plan['kit'].get('runtimeDigest') != config['source'].get('runtimeDigest')):
        raise ValueError('The frozen session differs from its retained campaign setup.')
    for task in plan['kit']['tasks']:
        observed = engine.read_json(engine.safe_child(session, task['file']), 2 * 1024 * 1024)
        if observed.get('gatewayOwnerDigest') != engine.digest(scope_key(value['owner'])):
            raise ClientError('The frozen plan belongs to another Gateway or account.', code='identity_changed', not_dispatched=True)
    value['state'] = 'READY'
    _save_metadata(workspace, value)
    return _snapshot(root, value['id'])


def create(root, config_path, *, preset='quick', campaign_path=None, observer_key):
    engine = _engine()
    config_path, observer_key = _file(config_path), _key_file(root, observer_key)
    if preset not in ('quick', 'comprehensive'):
        raise ValueError('Choose Quick or Comprehensive.')
    source = Path(engine.__file__).resolve().parent / 'campaign_assets' / 'config'
    campaign_path = _file(campaign_path) if campaign_path else source / (preset + '.json')
    config, campaign = engine.lab_config(config_path), engine.campaign_config(campaign_path)
    client, owner = _owner(root)
    gateway = config.get('gateway') or {}
    if gateway.get('root') and Path(gateway['root']).expanduser().resolve() != Path(root).resolve():
        raise ValueError('The lab configuration must select this installed Gateway state directory.')
    if gateway.get('allowedOrigin') and gateway['allowedOrigin'] != client.origin:
        raise ValueError('The configured Gateway origin differs from this installed Gateway.')
    config['gateway'] = {'root': str(Path(root).resolve()), 'allowedOrigin': client.origin,
                         'expectedOwnerDigest': engine.digest(scope_key(owner))}
    for name in ('observerPublicKey', 'graderPublicKey'):
        config[name] = str(_file(config.get(name)))
    # Validate controls and key custody before retaining any new campaign state.
    engine.freeze_controls(config, campaign)
    engine.external_secret(observer_key, repo=Path(engine.__file__).resolve().parents[1])
    with _lock(root):
        ident = 'campaign_' + uuid.uuid4().hex
        workspace = _workspace(root, ident)
        workspace.mkdir()
        for name, item in (('lab.json', config), ('campaign.json', campaign)):
            atomic_json(workspace / name, item)
        value = {'schema': SCHEMA, 'id': ident, 'title': campaign['name'], 'createdAt': engine.now(),
                 'owner': owner, 'origin': client.origin, 'state': 'SETUP_INCOMPLETE',
                 'files': {name: hashlib.sha256((workspace / name).read_bytes()).hexdigest()
                           for name in ('lab.json', 'campaign.json')}}
        _save_metadata(workspace, value)
    with _lock(root, ident):
        return _setup(root, workspace, value, observer_key)


def resume_setup(root, ident, observer_key):
    with _lock(root, ident):
        workspace, value = _read_metadata(root, ident)
        return _setup(root, workspace, value, _key_file(root, observer_key))


def _snapshot(root, ident):
    engine = _engine()
    workspace, value = _read_metadata(root, ident)
    session = workspace / 'session'
    result = {key: value[key] for key in ('id', 'title', 'createdAt', 'owner')}
    result.update(sessionPath=str(session), configPath=str(workspace / 'lab.json'), reportPath='',
                  evidenceLane=None, state='SETUP_INCOMPLETE', report=None, next=None, schedule=[],
                  scheduleTotal=0, scheduleTruncated=False, schedulePath=str(session / 'schedule.csv'), modelRequests=0)
    if not session.exists():
        result['message'] = 'Public-task export or plan freeze is incomplete. Resume setup with the original observer key.'
        return result
    report_result = engine.make_report(session)
    report = engine.read_json(Path(report_result['directory']) / 'scoreboard.json')
    pending = engine.next_slot(session)
    if pending.get('status') == 'AWAITING_NATIVE_TRIAL':
        prompt = engine.read_bytes(pending['promptFile'], 1024 * 1024).decode('utf-8')
        index = pending['slot']['scheduleIndex']
        measurements = workspace / 'measurements' / f'{index:06d}.json'
        # A prefilled observation remains editable until the observer signs it.
        # Refresh must never overwrite actual values already entered by the user.
        if not measurements.exists():
            engine.write_json_new(measurements, pending['measurementsTemplate'])
        pending.update(promptText=prompt, measurementsPath=str(measurements))
        try:
            pending['measurements'] = engine.read_json(measurements, 64 * 1024)
        except (ValueError, OSError):
            pending['measurements'] = None
            pending['measurementsError'] = 'The saved measurements draft is unreadable. Repair it before importing this trial.'
    slots = report.get('slots', [])
    result.update(evidenceLane=report['evidenceLane'], state=pending['status'],
                  report={key: value for key, value in report.items() if key != 'slots'},
                  reportPath=report_result['scoreboard'], next=pending, schedule=slots[:MAX_SCHEDULE_ROWS],
                  scheduleTotal=len(slots), scheduleTruncated=len(slots) > MAX_SCHEDULE_ROWS)
    return result


def read(root, ident):
    with _lock(root, ident):
        return _snapshot(root, ident)


def history(root):
    rows = []
    for directory in _directory(root).glob('campaign_*'):
        if not IDENTIFIER.fullmatch(directory.name):
            continue
        try:
            snapshot = read(root, directory.name)
            report = snapshot.get('report') or {}
            performance = report.get('products') or {}
            rows.append({key: snapshot.get(key) for key in ('id', 'title', 'createdAt', 'evidenceLane', 'state')})
            rows[-1].update(verdict=(report.get('nativeComparison') or {}).get('verdict', 'UNKNOWN'),
                            slots=sum(row.get('planned', 0) for row in performance.values()),
                            observed=sum(row.get('observed', 0) for row in performance.values()))
        except (OSError, ValueError, RuntimeError, KeyError, TypeError, AttributeError):
            # A corrupt or busy campaign stays visible but contributes no result.
            rows.append({'id': directory.name, 'title': 'Campaign evidence unavailable', 'createdAt': '',
                         'evidenceLane': None, 'state': 'UNAVAILABLE', 'verdict': 'UNKNOWN', 'slots': None, 'observed': None})
    return sorted(rows, key=lambda row: row['createdAt'], reverse=True)


def _slot(index, *, optional=False):
    if optional and index is None:
        return None
    if type(index) is not int or index < 0:
        raise ValueError('Choose a valid scheduled trial position.')
    return index


def operate(root, ident, operation, *, observer_key=None, grader_key=None, slot_index=None,
            measurements_path=None, capture_path=None, attest_origin=False, inbox=None):
    if operation not in {'import', 'import-inbox', 'grade', 'recover', 'retire'}:
        raise ValueError('Unsupported native campaign operation.')
    engine = _engine()
    with _lock(root, ident):
        workspace, value = _read_metadata(root, ident)
        _owner(root, value)
        session, config = workspace / 'session', workspace / 'lab.json'
        if operation in {'import', 'import-inbox'}:
            if attest_origin is not True:
                raise ValueError('The independent observer must explicitly attest the capture origin and actual controls.')
            observer_key = _key_file(root, observer_key)
            if operation == 'import':
                result = engine.import_capture(session, _slot(slot_index), _file(measurements_path),
                    observer_key, _file(capture_path, optional=True), attest_origin=True)
            else:
                if not isinstance(inbox, str) or not Path(inbox).is_absolute() or not Path(inbox).is_dir():
                    raise ValueError('Choose an existing absolute inbox directory.')
                result = engine.import_inbox(session, inbox, observer_key, attest_origin=True)
        elif operation == 'grade':
            result = engine.grade_session(session, config, _key_file(root, grader_key), _slot(slot_index, optional=True))
        elif operation == 'recover':
            # A UI refresh cannot become a retry loop or a new submission.
            result = engine.recover_campaign(session, config, _key_file(root, grader_key), _slot(slot_index, optional=True),
                                             polls=1, interval_seconds=0)
        else:
            result = engine.retire(session, config, _slot(slot_index))
        return dict(_snapshot(root, ident), operation=result)


def export(root, ident, path):
    target = _destination(root, path)
    with _lock(root, ident):
        workspace, _ = _read_metadata(root, ident)
        result = _engine().make_report(workspace / 'session', target)
        return dict(result, modelRequests=0)


def _capture_output(root, path):
    if not isinstance(path, (str, Path)) or not str(path) or len(str(path)) > 4096 or not Path(path).is_absolute():
        raise ValueError('Choose a new capture file using its absolute path.')
    target = _engine().guard_output(path)
    if _inside(target, root) or target.exists():
        raise ValueError('Choose a new capture file outside the Gateway state directory.')
    return target


def select_chatgpt(root, export_path, conversation_id, path):
    if not isinstance(conversation_id, str) or not 1 <= len(conversation_id) <= 256:
        raise ValueError('Enter the exact conversation identity to select.')
    result = _engine().select_chatgpt(_file(export_path), conversation_id, _capture_output(root, path))
    return dict(result, modelRequests=0)


def read_mobile(root, ident, conversation_id, path):
    target = _capture_output(root, path)
    engine = _engine()
    with _lock(root, ident):
        workspace, value = _read_metadata(root, ident)
        _owner(root, value)
        session, _, plan, _, _ = engine.load_session(workspace / 'session')
        config = engine.lab_config(workspace / 'lab.json')
        engine.verify_cfg_session(config, plan, session)
        result = engine.bridge(config, 'read-capture', {'conversation_id': conversation_id})
        engine.native.native_answer('GPT Mobile AI', json.dumps(result, ensure_ascii=False))
        _owner(root, value)
        engine.write_json_new(target, result)
    return {'output': str(target), 'modelRequests': 0}


def demo(root, path):
    from benchmarks.campaign_synthetic import create_demo
    return dict(create_demo(_destination(root, path)), modelRequests=0)


def _dispatch(root, action, args):
    """Small native JSONL action boundary; never dispatch a product generation."""
    if action == 'templates':
        return templates(root, args.get('path'))
    if action == 'keys':
        return keys(root, args.get('role'), args.get('path'))
    if action == 'create':
        return create(root, args.get('configPath'), preset=args.get('preset', 'quick'),
                      campaign_path=args.get('campaignPath'), observer_key=args.get('observerKeyPath'))
    if action == 'resume-setup':
        return resume_setup(root, args.get('id'), args.get('observerKeyPath'))
    if action == 'history':
        return {'campaigns': history(root)}
    if action == 'read':
        return read(root, args.get('id'))
    if action == 'export':
        return export(root, args.get('id'), args.get('path'))
    if action == 'demo':
        return demo(root, args.get('path'))
    if action == 'select-chatgpt':
        return select_chatgpt(root, args.get('exportPath'), args.get('conversationId'), args.get('path'))
    if action == 'read-mobile':
        return read_mobile(root, args.get('id'), args.get('conversationId'), args.get('path'))
    if action in {'import', 'import-inbox', 'grade', 'recover', 'retire'}:
        return operate(root, args.get('id'), action, observer_key=args.get('observerKeyPath'),
                       grader_key=args.get('graderKeyPath'), slot_index=args.get('slotIndex'),
                       measurements_path=args.get('measurementsPath'), capture_path=args.get('capturePath'),
                       attest_origin=args.get('attestOrigin', False), inbox=args.get('inbox'))
    raise ValueError('Unsupported native campaign action.')


def dispatch(root, action, args):
    try:
        return _dispatch(root, action, args)
    except (AttributeError, KeyError, TypeError):
        # Editable JSON and retained evidence are data, not a traceback surface.
        raise ValueError('The campaign configuration or retained evidence has an invalid structure. Review its JSON files before continuing.') from None
