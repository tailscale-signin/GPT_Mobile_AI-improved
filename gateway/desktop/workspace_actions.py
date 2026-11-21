"""Native operator controls backed by the same stores and validators as the supervisor."""
from pathlib import Path
import hashlib
import json
import time
import re
import threading

from desktop import configuration
from v14.operator_schedule import OperatorSchedule


def record_check(root, name, ready, subject=''):
    # Persist only the observed state and original public repository identifier.
    configuration.atomic_json(root / 'data' / ('desktop-check-' + name + '.json'),
                              {'ready': ready, 'subject': subject, 'observed': time.time()})


def workspace_readiness(root):
    from desktop.validation_policy import load_policy_snapshot
    policy = load_policy_snapshot(root)
    result = {'validation_enabled': policy['enabled'], 'repositories': len(policy['repositories'])}
    for name in ('repository', 'environment'):
        try:
            result[name] = json.loads((root / 'data' / ('desktop-check-' + name + '.json')).read_text(encoding='utf-8'))
        except (FileNotFoundError, ValueError):
            result[name] = {}
    return result


def schedules(root, action, args):
    store = OperatorSchedule(root / 'data')
    if action == 'schedule-add':
        key = store.add(args.get('title'), args.get('kind'), args.get('delay'), args.get('hours'), args.get('query'))
        return {'id': key}
    if action == 'schedule-list':
        return {'items': store.rows()}
    key = args.get('id')
    if not isinstance(key, str) or not re.fullmatch('[a-f0-9]{32}', key):
        raise ValueError('Select a schedule.')
    if action == 'schedule-change':
        return store.change(key, args.get('revision'), enabled=args.get('enabled'), title=args.get('title'), period_hours=args.get('hours'))
    if action == 'schedule-remove':
        store.remove(key)
    elif action == 'schedule-ack':
        if type(args.get('through')) is not int:
            raise ValueError('Open the alerts first.')
        store.acknowledge(key, args['through'])
    elif action == 'schedule-results':
        return {'alerts': store.notices(key), 'publications': store.publications(key), 'tests': store.test_history(key)}
    else:
        raise ValueError('Unknown schedule operation.')
    return {'id': key}


CONNECTION_FIELDS = {'bind_host', 'port', 'network_hostnames', 'shutdown_seconds', 'mcp_config'}


def _mcp_digest(path):
    if not path:
        return None
    try:
        file = Path(path)
        if not file.is_file() or file.stat().st_size > 1024 * 1024:
            return None
        with file.open('rb') as stream:
            content = stream.read(1024 * 1024 + 1)
        return hashlib.sha256(content).hexdigest() if len(content) <= 1024 * 1024 else None
    except OSError:
        return None


def _mcp_profiles(root):
    try:
        saved = json.loads((root / 'data' / 'approved-mcp-files.json').read_text(encoding='utf-8'))
        return saved if isinstance(saved, dict) else {}
    except (OSError, ValueError):
        return {}


def phone_connection_settings(store, principal, command=None):
    from desktop.control_store import ControlError
    snapshot = store.snapshot(principal)
    if snapshot['role'] != 'owner' or not (store.connected_account == principal or snapshot['capabilities'].get('privateAddress.v1')):
        raise ControlError('connection_settings_owner_required', 403)
    if command is None:
        return connection(store.root, 'connection-read', {}, phone=True)['settings']
    if not isinstance(command, dict) or set(command) != {'original', 'changes'}:
        raise ControlError('invalid_connection_settings')
    changes = command['changes']
    # The MCP file selects executable host processes. Files are installed on the PC.
    if not isinstance(changes, dict) or not changes or not changes.keys() <= CONNECTION_FIELDS:
        raise ControlError('invalid_connection_settings')
    try:
        return connection(store.root, 'connection-save', command, phone=True)['settings']
    except ValueError as error:
        if str(error).startswith('Connection settings changed'):
            raise ControlError('connection_settings_changed', 409) from None
        raise ControlError('invalid_connection_settings') from None


def connection(root, action, args, *, phone=False):
    fields = CONNECTION_FIELDS
    if action == 'connection-firewall':
        from desktop.firewall import apply
        apply(root, remove=args.get('remove') is True, include_control=True)
        return {'applied': True}
    with configuration.settings_write_lock(root):
        current = configuration.load(root)
        selected = {key: current[key] for key in fields}
        catalog = _mcp_profiles(root)
        if action == 'connection-save':
            changes = args.get('changes')
            original = args.get('original')
            if phone and isinstance(original, dict):
                original = {key: value for key, value in original.items() if key != 'mcp_choices'}
            if not isinstance(changes, dict) or not changes.keys() <= fields or original != selected:
                raise ValueError('Connection settings changed. Reload before applying your edits.')
            if phone and 'mcp_config' in changes:
                path = changes['mcp_config']
                if not isinstance(path, str) or path and (path not in catalog or catalog[path] != _mcp_digest(path)):
                    raise ValueError('Choose an unchanged MCP file previously selected on the PC.')
            updated = configuration.validate(dict(current, **changes))
            if not phone and 'mcp_config' in changes:
                for path in (selected['mcp_config'], updated['mcp_config']):
                    if digest := _mcp_digest(path):
                        catalog.pop(path, None)
                        catalog[path] = digest
                catalog = dict(list(catalog.items())[-16:])
                configuration.atomic_json(root / 'data' / 'approved-mcp-files.json', catalog)
            elif phone and selected['mcp_config'] and changes.get('mcp_config') == '' and selected['mcp_config'] not in catalog:
                # Preserve the installed desktop selection when an upgraded phone selects the default.
                path = selected['mcp_config']
                if digest := _mcp_digest(path):
                    catalog[path] = digest
                    catalog = dict(list(catalog.items())[-16:])
                    configuration.atomic_json(root / 'data' / 'approved-mcp-files.json', catalog)
            configuration.atomic_json(root / 'settings.json', updated)
            selected = {key: updated[key] for key in fields}
        if phone:
            selected['mcp_choices'] = sorted({'', *catalog, selected['mcp_config']})
        return {'settings': selected, 'restart_required': action == 'connection-save'}


def repositories(root, action, args):
    from desktop import repository_validation as validation, validation_policy as policy
    from desktop import acceptance_discovery
    from desktop.github_setup import configured_connection
    snapshot = policy.load_policy_snapshot(root)
    if action == 'repositories':
        return snapshot
    repository = validation.repository_name(args.get('repository', ''))
    if action == 'repo-load':
        return {'options': validation.options_for(root, repository, snapshot['repositories']), 'revision': snapshot['revision'],
                'enabled': snapshot['enabled'], 'profiles': [*validation.PROFILES, validation.CUSTOM_PROFILE]}
    if snapshot['revision'] != args.get('revision'):
        raise ValueError('Repository settings changed. Reload the current revision to review your edits.')
    options = args.get('options') or {}
    if options.get('repository') != repository:
        raise ValueError('Select the original repository for these options.')
    if action == 'repo-discover':
        result = acceptance_discovery.discover(options, args.get('query', ''), source_sha=args.get('sha') or None)
        return result
    if action == 'repo-map':
        return {'options': acceptance_discovery.add_discovered(options, args['candidate'], **args['mapping'])}
    if action == 'repo-save':
        entry = validation.entry_from_options(validation.validate_options(options), snapshot['repositories'].get(repository, {}))
        return validation.save_snapshot(root, repository, entry, snapshot['revision'], enabled=args['enabled'])
    if action in {'repo-prepare', 'repo-check'}:
        _, token = configured_connection(root, configuration.load(root))
        return validation.setup(root, options, snapshot['revision'], prepare=action == 'repo-prepare', token=token, enabled=args['enabled'])
    raise ValueError('Unknown repository action.')


def dispatch(root, name, args):
    if name.startswith('schedule-'):
        return schedules(root, name, args)
    if name.startswith('report-'):
        from desktop.workspace_reports import report
        return report(root, name, args)
    action = name.removeprefix('workspace-')
    if action == 'readiness':
        return workspace_readiness(root)
    if action.startswith('connection-'):
        return connection(root, action, args)
    if action.startswith('repo'):
        return repositories(root, action, args)
    if action.startswith('github-'):
        from desktop.github_setup import configured_connection, test_connection
        from desktop.github_credentials import save_token, remove_token
        cfg = configuration.load(root)
        if action == 'github-connect':
            token = args.get('token', '')
            note = test_connection(token, args.get('repository', ''))
            save_token(root, token)
            return {'message': note}
        if action == 'github-remove':
            remove_token(root)
            record_check(root, 'repository', None)
        if action == 'github-copilot':
            configuration.save_copilot_enabled(root, args.get('enabled'))
        source, token = configured_connection(root, cfg)
        if action == 'github-test':
            repo = args.get('repository', '')
            try:
                note = test_connection(token, repo)
            except (ValueError, OSError):
                record_check(root, 'repository', False, repo[:201])
                raise
            record_check(root, 'repository', True if repo else None, repo)
            return {'message': note}
        return {'source': source, 'configured': bool(token), 'public_maps': configuration.public_repository_maps(root),
                'copilot': configuration.copilot_enabled(root)}
    if action.startswith('addons-'):
        from desktop.addons import load_addons, save_addons
        path = Path(args.get('path') or configuration.load(root)['mcp_config'] or root / 'data' / 'mcp.json')
        if action == 'addons-save':
            save_addons(path, args.get('revision'), args.get('enabled', {}))
        config, revision = load_addons(path)
        return {'path': str(path), 'revision': revision, 'enabled': {k: not v.get('disabled', False) for k,v in config['mcpServers'].items()}}
    if action.startswith('env-'):
        from desktop import engineering_setup as setup
        if action == 'env-check':
            result = setup.check_environment()
            record_check(root, 'environment', result['ready_for_validation'])
            return result
        if action == 'env-prepare':
            return setup.prepare_and_verify(root)
        if action == 'env-repair-path':
            return {'message': setup.repair_helper_path(approved=args.get('approved') is True)}
        if action == 'env-install':
            return {'message': setup.install_prerequisite(args.get('kind'), approved=args.get('approved') is True)}
    if action == 'qualification':
        import json
        from benchmarks.engineering_qualification import run_manifest
        from desktop.conversation_client import Client
        source = Path(args['path'])
        with source.open('rb') as stream:
            data = stream.read(1048577)
        if len(data) > 1048576:
            raise ValueError('The qualification manifest exceeds 1 MiB.')
        client = Client(root)
        client.identity()
        return run_manifest(client, json.loads(data))
    if action.startswith('sandbox-'):
        from benchmarks import settings
        if action == 'sandbox-read':
            return settings.load_settings(root)
        if action == 'sandbox-save':
            return settings.save_settings(root, args['settings'], expected=args['original'])
        if action == 'sandbox-test':
            return {'verified': settings.smoke_sandbox_image(args.get('image', ''))}
        if action == 'sandbox-prepare':
            return {'image': settings.prepare_sandbox(root, expected=args['original'])}
    if action.startswith('analysis-'):
        from desktop import analysis_panel as analysis
        if action == 'analysis-run':
            return {'output': analysis.analyze(root, args.get('code'), args.get('input'), threading.Event())}
        path = Path(args['path'])
        if action == 'analysis-import':
            return {'input': analysis.import_data(path)}
        if action == 'analysis-open':
            code, source = analysis.read_analysis(path)
            return {'code': code, 'input': source}
        if action == 'analysis-save':
            analysis.write_analysis(path, args.get('code'), args.get('input'))
        elif action == 'analysis-export':
            analysis.write_export(path, analysis.export_result(args['output'], path.suffix.lower()))
        else:
            raise ValueError('Unknown analysis action.')
        return {'saved': path.name}
    raise ValueError('Unsupported workspace operation.')
