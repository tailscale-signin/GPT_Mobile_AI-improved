"""Offline task terminal. Docker owns the checkout, process namespace and lifetime."""
import hashlib
import json
import re
from pathlib import Path
import subprocess
import tarfile
import tempfile
import time

from v14 import workspace_process
from v14.engineering_scope import accepted_scope
from v14.engineering_validation import cancellation_scope, load_policy, materialize_checkout, patch_identity, run_bounded
from v14.engineering_workspace_checks import catalog, retained_candidate

RPC = """
import socket, sys
with socket.socket(socket.AF_UNIX) as connection:
    connection.connect('/control/terminal.sock')
    connection.sendall(sys.argv[1].encode()+b'\\n')
    with connection.makefile('rb') as stream:
        sys.stdout.buffer.write(stream.readline(65537))
"""
SEAL = """
import os, sys, tarfile
from pathlib import Path
root=Path('/task')
with tarfile.open(fileobj=sys.stdin.buffer, mode='r|') as source:
    source.extractall(root, filter='data')
(root/'home').mkdir(exist_ok=True)
for folder in [root/'home', root/'checkout']:
    for path in [folder, *folder.rglob('*')]:
        os.chown(path, 65534, 65534, follow_symlinks=False)
Path('/control/ready').touch()
"""
STATUS = '{{.Id}} {{index .Config.Labels "gpt.workspace"}} {{.State.Running}}'


def docker(args, root, timeout=30, *, missing=False, stdin=subprocess.DEVNULL):
    result = run_bounded(['docker', *args], root, timeout, stdin=stdin)
    if result['exit_code'] != 0:
        message = result['output_tail']
        if missing and ('No such object' in message or 'No such container' in message):
            return None
        raise ValueError('Workspace container operation failed: ' + message[-1000:])
    return result['output_tail'].strip()


def act(backend, job, action):
    if accepted_scope(job['request']) != 'implement':
        raise PermissionError('The job needs implementation scope to use an offline terminal')
    policy_sha, _ = catalog(job)
    policy, mirror, image, _, _ = load_policy(job['request']['repository'])
    if policy.get('workspace_terminal') is not True:
        raise ValueError('Enable the offline task terminal for this repository in Gateway validation settings')
    head = job['data']['head_sha']
    kind = action['action']
    saved = job['data'].get('terminal_session') or {}
    branches = job['data'].setdefault('terminal_branches', {})
    if saved:
        branches[saved['session_id']] = saved
    session = action.get('session_id')
    if kind == 'terminal_timeline':
        return {'active': saved.get('session_id'), 'checkouts': [
            {k: row.get(k) for k in ('session_id', 'head_sha', 'candidate_check_id', 'forked_from', 'patch_identity')}
            for row in branches.values()], 'expires_at': job['deadline']}
    if kind == 'terminal_switch':
        chosen = branches.get(workspace_process.identifier(session))
        if not chosen or chosen['head_sha'] != head or chosen['policy_sha256'] != policy_sha:
            raise ValueError('Select an existing checkout at the current revision and policy')
        job['data']['terminal_session'] = chosen
        return {'session_id': session, 'head_sha': head, 'state': 'SELECTED'}
    forked_from = None
    if kind == 'terminal_fork':
        workspace_process.identifier(session)
        if session in branches:
            saved = branches[session]
            if saved.get('candidate_check_id') != action.get('candidate_check_id'):
                raise ValueError('This checkout is already bound to a different candidate')
            forked_from = saved.get('forked_from')
        else:
            if not saved or len(branches) >= 3:
                raise ValueError('Open a workspace first. Up to three local checkouts can be retained.')
            forked_from = saved['session_id']
            saved = {}
        kind = 'terminal_open'
    cancel = backend.cancellation(job)
    if kind == 'terminal_open' and action.get('session_id') in job['data'].get('closed_terminal_sessions', []):
        raise ValueError('This session was closed; choose a fresh session_id')
    with tempfile.TemporaryDirectory(prefix='engineering-terminal-') as directory, cancellation_scope(cancel, job['deadline']):
        root = Path(directory)
        if kind == 'terminal_open':
            session = workspace_process.identifier(action.get('session_id'))
            candidate = action.get('candidate_check_id')
            changes = retained_candidate(backend, job, candidate) if candidate is not None else []
            identity = hashlib.sha256(json.dumps([job['principal'], job['id'], head, policy_sha, patch_identity(head, changes), session]).encode()).hexdigest()
            name = 'gpt-work-' + identity[:48]
            if saved and saved.get('name') != name:
                raise ValueError('Close the previous workspace before opening a different checkout')
            state = docker(['container', 'inspect', '--format', STATUS, name], root, missing=True)
            container = None
            try:
                if state is None:
                    if saved:
                        raise ValueError('The container expired or was removed. Close the saved session and open a new checkout.')
                    from v14.engineering_source import prepare_source
                    parent = backend.api(backend.root(job) + '/git/commits/' + head, job=job)
                    prepare_source(job, backend.token(), parent['tree']['sha'], cancel)
                    checkout = materialize_checkout(job, mirror, changes, root, cancel)
                    from v14.workspace_environment import read as read_environment
                    _, _, limits = read_environment(policy)
                    argv = ['run', '--detach', '--rm', '--pull=never', '--name', name, '--label', 'gpt.workspace=' + identity,
                            '--network=none', '--env=GITHUB_SHA=' + head, '--read-only', '--cap-drop=ALL', '--cap-add=CHOWN', '--cap-add=SETUID',
                            '--cap-add=SETGID', '--cap-add=KILL', '--security-opt=no-new-privileges',
                            '--pids-limit=256', '--memory=' + str(limits['memory_gib']) + 'g', '--cpus=' + str(limits['cpus']), '--user=0:0', '--log-driver=none',
                            '--tmpfs=/task:rw,nosuid,nodev,size=' + str(limits['checkout_gib']) + 'g', '--tmpfs=/tmp:rw,nosuid,nodev,size=512m',
                            '--tmpfs=/control:rw,noexec,nosuid,nodev,mode=0700,size=272m', '--entrypoint=python3',
                            image, '-I', '-u', '-c', Path(workspace_process.__file__).read_text(encoding='utf-8'), str(job['deadline'])]
                    container = docker(argv, root)
                    if not re.fullmatch('[a-f0-9]{64}', container):
                        raise ValueError('Docker did not return a workspace container ID')
                    # Docker's archive API refuses writes to a read-only rootfs,
                    # including its tmpfs mounts. Extract the sealed source inside /task.
                    with tempfile.TemporaryFile(dir=root) as archive:
                        with tarfile.open(fileobj=archive, mode='w') as source:
                            source.add(checkout, arcname='checkout')
                        archive.seek(0)
                        docker(['exec', '-i', container, 'python3', '-I', '-c', SEAL],
                               root, 180, stdin=archive)
                    state = docker(['container', 'inspect', '--format', STATUS, name], root)
                bits = state.split()
                if len(bits) != 3 or bits[1:] != [identity, 'true'] or saved and bits[0] != saved.get('container'):
                    raise ValueError('Workspace container identity changed')
                docker(['exec', bits[0], 'test', '-f', '/control/ready'], root)
            except BaseException:
                if container is not None:
                    # A stopped job must still remove its own observed container.
                    # Only this bounded cleanup may outlive the cancelled scope;
                    # normal work keeps the original cancellation and deadline.
                    with cancellation_scope(None, time.time() + 30):
                        dispose_one({'container': container, 'identity': identity})
                raise
            saved = dict(name=name, session_id=session, container=bits[0], identity=identity, head_sha=head,
                         candidate_check_id=candidate, forked_from=forked_from or saved.get('forked_from'),
                         patch_identity=patch_identity(head, changes), policy_sha256=policy_sha, image=image)
            branches[session] = saved
            job['data']['terminal_session'] = saved
            return dict(session_id=session, head_sha=head, patch_identity=saved['patch_identity'], image=image,
                        expires_at=job['deadline'], network='none', scope='exploratory')
        if not saved or saved.get('session_id') != action.get('session_id') or saved.get('head_sha') != head or saved.get('policy_sha256') != policy_sha:
            raise ValueError('Open the pinned task workspace first')
        state = docker(['container', 'inspect', '--format', STATUS, saved['container']], root, missing=True)
        if state is not None and state.split() != [saved['container'], saved['identity'], 'true']:
            raise ValueError('Workspace ownership changed')
        if kind == 'terminal_close':
            if state is not None:
                docker(['rm', '-f', saved['container']], root)
            job['data']['closed_terminal_sessions'] = (job['data'].get('closed_terminal_sessions', []) + [saved['session_id']])[-128:]
            branches.pop(saved['session_id'], None)
            job['data']['terminal_session'] = next(iter(branches.values()), None)
            return {'state': 'CLOSED', 'session_id': saved['session_id']}
        if state is None:
            raise ValueError('Workspace interrupted. The container no longer exists; commands were not replayed.')
        if kind == 'code_intelligence':
            from v14 import workspace_lsp
            query = {k: action[k] for k in ('language', 'operation', 'path', 'line', 'column', 'new_name') if k in action}
            action = {'action': 'terminal_start', 'process_id': workspace_process.identifier(action.get('process_id')),
                      'argv': ['python3', '-I', '-c', Path(workspace_lsp.__file__).read_text(encoding='utf-8'), json.dumps(query)],
                      'timeout_seconds': 300}
        if action.get('action') == 'terminal_start':
            from v14.workspace_environment import read as read_environment
            _, defaults, _ = read_environment(policy)
            if not isinstance(action.get('env', {}), dict):
                raise ValueError('Workspace command environment must be a mapping')
            action = dict(action, env=dict(defaults, **action.get('env', {})))
        if kind == 'terminal_start':
            workspace_process.identifier(action.get('process_id'))
        payload = json.dumps(action)
        if len(payload) > 32000:
            raise ValueError('Workspace request exceeds the process argument budget')
        result = json.loads(docker(['exec', saved['container'], 'python3', '-I', '-c', RPC, payload], root))
        if 'error' in result:
            raise ValueError(result['error'])
        return dict(result, session_id=saved['session_id'], head_sha=head, image=image)

def dispose(job):
    """Clean up only this job's observed container, including after policy revocation."""
    data = job.get('data', {})
    sessions = dict(data.get('terminal_branches', {}))
    saved = data.get('terminal_session')
    if saved:
        sessions[saved['session_id']] = saved
    for session in sessions.values():
        dispose_one(session)


def dispose_one(saved):
    container = saved.get('container')
    if not isinstance(container, str) or not re.fullmatch('[a-f0-9]{64}', container):
        return
    with tempfile.TemporaryDirectory(prefix='engineering-terminal-stop-') as folder:
        root = Path(folder)
        try:
            state = docker(['container', 'inspect', '--format', STATUS, container], root, missing=True)
            if state and state.split()[:2] == [container, saved.get('identity')]:
                docker(['rm', '-f', container], root, missing=True)
        except (ValueError, OSError, TimeoutError):
            # The committed state remains authoritative. PID 1 enforces the same
            # absolute deadline independently and Docker removes the container.
            return
