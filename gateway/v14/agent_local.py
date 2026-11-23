"""Retained, isolated agent containers owned by an existing engineering job."""
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
import time

from v14.agent_config import AgentError, CATALOG, canonical, credential, digest
from v14.engineering_validation import cancellation_scope, load_policy, materialize_checkout, run_bounded

STATUS = '{{.Id}} {{index .Config.Labels "gpt.agent"}} {{.State.Running}}'
RPC = """
import socket, sys
with socket.socket(socket.AF_UNIX) as connection:
    connection.settimeout(25)
    connection.connect('/control/agent.sock')
    connection.sendall(sys.stdin.buffer.read(262145))
    with connection.makefile('rb') as stream:
        raw = stream.readline(2097153)
        if len(raw) > 2097152:
            raise ValueError('Agent response exceeds bound')
        sys.stdout.buffer.write(raw)
"""
SEAL = """
import os, sys, tarfile
from pathlib import Path
root=Path('/task')
with tarfile.open(fileobj=sys.stdin.buffer, mode='r|') as source:
    source.extractall(root, filter='data')
(root/'launch.json').replace('/control/launch.json')
os.chmod('/control/launch.json', 0o600)
(root/'home').mkdir(exist_ok=True)
for folder in (root/'home', root/'checkout'):
    for path in [folder, *folder.rglob('*')]:
        os.chown(path, 65534, 65534, follow_symlinks=False)
for path in (root/'runner').rglob('*'):
    os.chmod(path, 0o555)
Path('/control/ready').touch()
"""
COMMANDS = {
    'codex': ['codex', 'app-server'], 'kiro': ['kiro-cli', 'acp'], 'devin': ['devin', 'acp'],
    'cline': ['node', '/task/runner/agent_cline_driver.mjs'],
    **{name: ['python3', '-I', '-u', '/task/runner/agent_sdk_driver.py', name]
       for name in ('claude', 'openhands', 'copilot_sdk', 'aider')}
}
ENV_NAMES = {'codex': 'OPENAI_API_KEY', 'claude': 'ANTHROPIC_API_KEY', 'cline': 'CLINE_API_KEY',
             'openhands': 'LLM_API_KEY', 'copilot_sdk': 'GH_TOKEN', 'aider': 'OPENAI_API_KEY'}


class LocalAgent:
    def __init__(self, backend, config, *, runner=None):
        self.backend, self.config = backend, config
        self.runner = runner or run_bounded

    def docker(self, args, *, timeout=30, stdin=None, missing=False):
        with tempfile.TemporaryDirectory(prefix='agent-control-') as temporary, io.BytesIO() as output:
            result = self.runner(['docker', *args], Path(temporary), timeout, maximum=2 * 1024 * 1024,
                                 destination=output, stdin=stdin if stdin is not None else subprocess.DEVNULL)
            raw = output.getvalue().decode('utf-8') if output.tell() else result.get('output_tail', '')
            if result['exit_code'] != 0:
                if missing and ('No such object' in raw or 'No such container' in raw):
                    return None
                raise AgentError('AGENT_CONTAINER_UNAVAILABLE', 'The isolated agent container is unavailable. Check its pinned image and Docker readiness.')
            return raw.strip()

    def qualify(self, repository):
        load_policy(repository)
        command = self.config.get('command') or COMMANDS[self.backend]
        modules = {'claude': 'claude_agent_sdk', 'openhands': 'openhands.sdk', 'copilot_sdk': 'copilot', 'aider': 'aider'}
        packages = {'claude': ['claude-agent-sdk'], 'openhands': ['openhands-sdk', 'openhands-tools'],
                    'copilot_sdk': ['microsoft-copilot-sdk'], 'aider': ['aider-chat']}
        checks = ('import importlib.util, importlib.metadata, shutil, subprocess, json; '
                  'assert shutil.which(' + repr(command[0]) + '), "runtime unavailable"; '
                  'assert shutil.which("git"), "source inventory runtime unavailable"; versions={}; ')
        if self.backend in modules and 'command' not in self.config:
            checks += 'assert importlib.util.find_spec(' + repr(modules[self.backend]) + '), "SDK unavailable"; '
        if self.backend == 'cline':
            checks += 'subprocess.run(["node", "-e", "require.resolve(\'@cline/sdk\', {paths:[\'/opt/gateway-agents\']})"], check=True, timeout=10, capture_output=True); '
        if self.backend in packages and 'command' not in self.config:
            checks += 'versions.update({name:importlib.metadata.version(name) for name in ' + repr(packages[self.backend]) + '}); '
        checks += ('version=subprocess.run(' + repr([command[0], '--version']) + ', capture_output=True, text=True, timeout=10); '
                   'print(json.dumps({"runtime_available":True,"versions":versions,"runtime_version":version.stdout.strip()[:200] if version.returncode==0 else None}))')
        raw = self.docker(['run', '--rm', '--pull=never', '--network=none', '--read-only', '--cap-drop=ALL',
                           '--security-opt=no-new-privileges', '--pids-limit=64', '--memory=512m', '--user=65534:65534',
                           '--entrypoint=python3', self.config['image'], '-I', '-c', checks])
        try:
            observed = json.loads(raw)
        except (TypeError, ValueError):
            observed = {}
        if observed.get('runtime_available') is not True or not isinstance(observed.get('versions'), dict):
            raise AgentError('AGENT_RUNTIME_UNVERIFIED', 'The image did not confirm the configured agent runtime.')
        versions = {key: value for key, value in observed['versions'].items()
                    if key in packages.get(self.backend, []) and isinstance(value, str) and re.fullmatch(r'[A-Za-z0-9_.+-]{1,64}', value)}
        runtime_version = re.search(r'\b\d{1,4}\.\d{1,4}(?:\.\d{1,6})?(?:[-+][A-Za-z0-9.]{1,40})?\b', str(observed.get('runtime_version', ''))[:200])
        return {'state': 'READY', 'image': self.config['image'], 'runtime_available': True,
                'runtime_version': runtime_version[0] if runtime_version else None, 'sdk_versions': versions,
                'account_qualification': 'ON_SESSION_START', 'model_selection': 'ON_SESSION_START',
                'source_binding': 'SEALED_COMMIT', 'cost_limit_supported': self.backend in {'claude', 'openhands'}}

    def prepare(self, backend, job, run, *, check_owner=None):
        intended = self.intent(run)
        identity, name = intended['container_identity'], intended['container_name']
        observed = self.docker(['container', 'inspect', '--format', STATUS, name], missing=True)
        if observed is not None:
            pieces = observed.split()
            if len(pieces) != 3 or pieces[1:] != [identity, 'true']:
                raise AgentError('AGENT_CONTAINER_CHANGED', 'The retained agent container identity changed.')
            self.docker(['exec', pieces[0], 'python3', '-I', '-c', "from pathlib import Path; assert Path('/control/ready').is_file()"])
            if check_owner is not None:
                check_owner()
            return {'container': pieces[0], 'container_identity': identity}
        policy, mirror, _, _, _ = load_policy(job['request']['repository'])
        from v14.engineering_source import prepare_source
        from v14.workspace_environment import read as environment_limits
        from v14 import agent_process
        cancel = backend.cancellation(job)
        parent = backend.api(backend.root(job) + '/git/commits/' + run['source_sha'], job=job)
        prepare_source(job, backend.token(), parent['tree']['sha'], cancel)
        secret_env = {target: credential(reference) for target, reference in self.config.get('environment_references', {}).items()}
        if self.config.get('credential_reference') and self.backend in ENV_NAMES:
            secret_env[ENV_NAMES[self.backend]] = credential(self.config['credential_reference'])
        launch = dict(backend=self.backend, model_id=run['model_id'], environment=secret_env,
                      command=self.config.get('command') or COMMANDS[self.backend],
                      max_iterations=self.config.get('max_iterations', 24), max_budget_usd=self.config.get('max_budget_usd', 2.0),
                      provider_id=self.config.get('provider_id', 'anthropic'),
                      baseline={path: {'sha': row['sha'], 'mode': row['mode']} for path, row in job['data']['tree'].items()
                                if row['mode'] in {'100644', '100755'}})
        _, _, limits = environment_limits(policy)
        container = None
        with tempfile.TemporaryDirectory(prefix='agent-source-') as temporary, cancellation_scope(cancel, job['deadline']):
            root = Path(temporary)
            checkout = materialize_checkout(job, mirror, [], root, cancel)
            try:
                if cancel.is_set():
                    raise AgentError('AGENT_PARENT_STOPPED', 'The parent stopped before isolated preparation.')
                if check_owner is not None:
                    check_owner()
                container = self.docker(['run', '--detach', '--rm', '--pull=never', '--name', name, '--label', 'gpt.agent=' + identity,
                    '--network=bridge', '--read-only', '--cap-drop=ALL', '--cap-add=CHOWN', '--cap-add=SETUID', '--cap-add=SETGID', '--cap-add=KILL',
                    '--security-opt=no-new-privileges', '--pids-limit=256', '--memory=' + str(limits['memory_gib']) + 'g',
                    '--cpus=' + str(limits['cpus']), '--user=0:0', '--log-driver=none',
                    '--tmpfs=/task:rw,exec,nosuid,nodev,size=' + str(limits['checkout_gib']) + 'g',
                    '--tmpfs=/tmp:rw,exec,nosuid,nodev,size=512m', '--tmpfs=/control:rw,noexec,nosuid,nodev,mode=0700,size=64m',
                    '--entrypoint=python3', self.config['image'], '-I', '-u', '-c', Path(agent_process.__file__).read_text(),
                    str(min(job['deadline'], run['deadline']))])
                if not re.fullmatch('[a-f0-9]{64}', container):
                    raise AgentError('AGENT_CONTAINER_UNVERIFIED', 'Docker did not confirm an agent container ID.')
                with tempfile.TemporaryFile(dir=root) as source:
                    with tarfile.open(fileobj=source, mode='w') as archive:
                        archive.add(checkout, arcname='checkout')
                        for filename in ('agent_sdk_driver.py', 'agent_cline_driver.mjs'):
                            archive.add(Path(__file__).with_name(filename), arcname='runner/' + filename)
                        raw = canonical(launch).encode()
                        member = tarfile.TarInfo('launch.json')
                        member.size, member.mode = len(raw), 0o600
                        archive.addfile(member, io.BytesIO(raw))
                    source.seek(0)
                    self.docker(['exec', '-i', container, 'python3', '-I', '-c', SEAL], timeout=180, stdin=source)
                observed = self.docker(['container', 'inspect', '--format', STATUS, container])
                if observed.split() != [container, identity, 'true']:
                    raise AgentError('AGENT_CONTAINER_CHANGED', 'The created agent container did not retain its identity.')
                if cancel.is_set():
                    raise AgentError('AGENT_PARENT_STOPPED', 'The parent stopped during isolated preparation.')
                if check_owner is not None:
                    check_owner()
            except BaseException:
                # Docker may have created a container despite losing stdout.
                # Resolve the persisted deterministic intent and ownership label
                # even when no returned container ID made it into the journal.
                try:
                    with cancellation_scope(None, time.time() + 30):
                        self.dispose(run)
                except Exception:
                    pass  # Retained identity plus container deadline permit recovery.
                raise
        return {'container': container, 'container_identity': identity}

    @staticmethod
    def intent(run):
        identity = digest([run['principal'], run['job_id'], run['run_id'], run['source_sha'], run['config_digest']])
        return {'container_name': 'gpt-agent-' + identity[:48], 'container_identity': identity}

    def locate(self, saved):
        intended = self.intent(saved)
        target = saved.get('container') or intended['container_name']
        if saved.get('container') and not re.fullmatch('[a-f0-9]{64}', target):
            raise AgentError('AGENT_CONTAINER_CHANGED', 'The retained container identifier is invalid.')
        observed = self.docker(['container', 'inspect', '--format', STATUS, target], missing=True)
        if observed is None:
            return None
        pieces = observed.split()
        if (len(pieces) != 3 or not re.fullmatch('[a-f0-9]{64}', pieces[0]) or pieces[1] != intended['container_identity']
                or saved.get('container') and pieces[0] != saved['container']):
            raise AgentError('AGENT_CONTAINER_CHANGED', 'The retained container ownership label changed.')
        return dict(intended, container=pieces[0], running=pieces[2] == 'true')

    def rpc(self, saved, action):
        container = saved.get('container')
        if not isinstance(container, str) or not re.fullmatch('[a-f0-9]{64}', container):
            raise AgentError('AGENT_CONTAINER_UNAVAILABLE', 'The agent has no retained container identity.')
        state = self.docker(['container', 'inspect', '--format', STATUS, container], missing=True)
        if state is None:
            raise AgentError('AGENT_RUNTIME_LOST', 'The agent container expired. Its execution is not replayed automatically.')
        if state.split() != [container, saved['container_identity'], 'true']:
            raise AgentError('AGENT_CONTAINER_CHANGED', 'The agent container ownership changed.')
        with tempfile.TemporaryFile() as request:
            request.write((canonical(action) + '\n').encode())
            request.seek(0)
            raw = self.docker(['exec', '-i', container, 'python3', '-I', '-c', RPC], stdin=request)
        try:
            value = json.loads(raw)
        except ValueError:
            raise AgentError('AGENT_RESPONSE_INVALID', 'The isolated agent response was not complete JSON.') from None
        if not isinstance(value, dict) or value.get('error_code'):
            raise AgentError('AGENT_CONTROL_REJECTED', 'The isolated agent did not confirm the requested control.')
        return value

    def start(self, saved, objective, operation_id, dispatch):
        dispatch()
        return self.rpc(saved, {'action': 'start', 'objective': objective, 'operation_id': operation_id})

    def status(self, saved):
        return self.rpc(saved, {'action': 'status'})

    def events(self, saved, after=None):
        return self.rpc(saved, {'action': 'events', 'after': after or 0})

    def artifacts(self, saved):
        return self.rpc(saved, {'action': 'artifacts'})

    def operation(self, saved, operation_id):
        return self.rpc(saved, {'action': 'operation', 'operation_id': operation_id})

    def dispose(self, saved):
        """Cancel only this retained container, independent of model credentials."""
        located = self.locate(saved)
        if located is None:
            return {'state': 'RUNTIME_ABSENT'}
        with cancellation_scope(None, time.time() + 30):
            self.docker(['rm', '-f', located['container']])
        return {'state': 'CANCELLED'}

    def control(self, saved, kind, message, operation_id, dispatch):
        dispatch()
        return self.rpc(saved, {'action': kind, 'message': message, 'operation_id': operation_id,
                               'expected_remote_run_id': saved.get('remote_run_id')})
