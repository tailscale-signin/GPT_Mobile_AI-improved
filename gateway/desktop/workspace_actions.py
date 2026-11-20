"""Native operator controls backed by the same stores and validators as the supervisor."""
from pathlib import Path
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


def connection(root, action, args):
    fields = {'bind_host', 'port', 'network_hostnames', 'shutdown_seconds', 'mcp_config'}
    if action == 'connection-firewall':
        from desktop.firewall import apply
        apply(root, remove=args.get('remove') is True)
        return {'applied': True}
    with configuration.settings_write_lock(root):
        current = configuration.load(root)
        selected = {key: current[key] for key in fields}
        if action == 'connection-save':
            changes = args.get('changes')
            if not isinstance(changes, dict) or not changes.keys() <= fields or args.get('original') != selected:
                raise ValueError('Connection settings changed. Reload before applying your edits.')
            updated = configuration.validate(dict(current, **changes))
            configuration.atomic_json(root / 'settings.json', updated)
            selected = {key: updated[key] for key in fields}
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
