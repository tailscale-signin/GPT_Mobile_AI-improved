"""Protocol, lease, privacy, retry and candidate tests without paid model calls."""
import copy
import asyncio
import io
import json
import os
from pathlib import Path
import sqlite3
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.agent_config import AgentError, credential, digest, load, save, validate
from v14.agent_cloud import CloudAgent
from v14.agent_candidates import unified_changes, verify_changes
from v14.agent_process import AgentProcess, blob
from v14.agent_local import LocalAgent
from v14.agent_runs import AgentRuns
from v14.agent_http_worker import HttpFailure, perform
from v14.agent_sdk_driver import CheckoutTools, Driver
from v14.engineering import EngineeringStore
from v14.engineering_artifacts import EngineeringArtifacts
from v14.engineering_backend import blob_sha


class ConfigurationTests(unittest.TestCase):
    def row(self):
        return {'schema_version': 1, 'agents': {'cursor': {'enabled': True, 'repositories': ['owner/repo'],
                'principals': ['owner'], 'model_id': 'model-x', 'credential_reference': 'env:AGENT_TEST_TOKEN'}}}

    def test_settings_compare_revision_atomically_without_storing_secrets(self):
        with tempfile.TemporaryDirectory() as temporary:
            target = Path(temporary) / 'agents.json'
            initial = digest({'schema_version': 1, 'agents': {}})
            result = save(self.row(), initial, target)
            self.assertEqual(result['revision'], load(target)[1])
            with self.assertRaises(AgentError) as changed:
                save(self.row(), initial, target)
            self.assertEqual('AGENT_CONFIG_CHANGED', changed.exception.code)
            self.assertEqual('env:AGENT_TEST_TOKEN', json.loads(target.read_text())['agents']['cursor']['credential_reference'])
            self.assertEqual(0, target.stat().st_mode & 0o077)

    def test_config_refuses_secret_values_unpinned_images_endpoints_and_shell_strings(self):
        for key, value in [('credential_reference', 'real-secret-value'), ('endpoint', 'https://attacker.invalid')]:
            doc = self.row(); doc['agents']['cursor'][key] = value
            with self.assertRaises(AgentError):
                validate(doc)
        local = self.row()['agents']['cursor']
        for image, command in [('agent:latest', ['codex']), ('agent@sha256:' + 'a' * 64, 'codex --unsafe')]:
            doc = {'schema_version': 1, 'agents': {'codex': dict(local, image=image, command=command)}}
            with self.assertRaises(AgentError):
                validate(doc)
        with self.assertRaises(AgentError):
            credential('env:AGENT_TEST_TOKEN', {})

    def test_sdk_bridge_cannot_be_replaced_or_given_a_repository_credential_tool(self):
        local = dict(self.row()['agents']['cursor'], image='agent@sha256:' + 'a' * 64)
        for extra in ({'command': ['copilot', '--unsafe']},
                      {'environment_references': {'GH_TOKEN': 'env:SHARED_REPO_TOKEN'}},
                      {'environment_references': {'NODE_OPTIONS': 'env:EXECUTE_ARBITRARY_MODULE'}}):
            with self.assertRaises(AgentError):
                validate({'schema_version': 1, 'agents': {'copilot_sdk': dict(local, **extra)}})
        with self.assertRaises(AgentError):
            validate({'schema_version': 1, 'agents': {'codex': dict(local, environment_references={'GITHUB_TOKEN': 'env:SHARED_REPO_TOKEN'})}})
        for backend in ('kiro', 'devin', 'github_copilot'):
            with self.assertRaises(AgentError) as error:
                validate({'schema_version': 1, 'agents': {backend: local}})
            self.assertEqual('AGENT_CREDENTIAL_REFERENCE_UNSUPPORTED', error.exception.code)


class Response:
    def __init__(self, value, status=200):
        self.value, self.status_code = value, status
    def __enter__(self):
        return self
    def __exit__(self, *unused):
        pass
    def iter_content(self, unused):
        yield json.dumps(self.value).encode()


class CloudProtocolTests(unittest.TestCase):
    def setUp(self):
        self.config = {'credential_reference': 'env:AGENT_TEST_TOKEN', 'model_id': 'model-x'}
        self.request = Mock(return_value=Response({'agent': {'id': 'a-1'}, 'run': {'id': 'r-1', 'agentId': 'a-1', 'status': 'CREATING'}}))
        self.agent = CloudAgent('cursor', self.config, request=self.request, environment={'AGENT_TEST_TOKEN': 'test-secret'})
        self.input = {'repository': 'owner/repo', 'source_sha': 'a' * 40, 'source_branch': 'feature',
                      'model_id': 'model-x', 'objective': 'Fix the accepted task', 'correlation': 'gateway-unique'}

    def test_cursor_starts_exact_source_isolated_branch_after_dispatch_fence(self):
        order = []
        def request(*args, **kwargs):
            order.append('http')
            return self.request(*args, **kwargs)
        self.agent.request = request
        result = self.agent.start(self.input, {}, lambda: order.append('fenced'))
        self.assertEqual(['fenced', 'http'], order)
        self.assertEqual(('a-1', 'r-1', 'QUEUED'), (result['session_id'], result['remote_run_id'], result['state']))
        args, kwargs = self.request.call_args
        self.assertEqual(('POST', 'https://api.cursor.com/v1/agents'), args)
        self.assertFalse(kwargs['allow_redirects'])
        self.assertEqual('a' * 40, kwargs['json']['repos'][0]['startingRef'])
        self.assertIs(False, kwargs['json']['workOnCurrentBranch'])
        self.assertIs(False, kwargs['json']['autoCreatePR'])
        self.assertNotIn('test-secret', json.dumps(kwargs['json']))

    def test_unknown_mutation_has_no_transport_retry_or_raw_error(self):
        self.request.side_effect = requests.Timeout('token=test-secret')
        fence = Mock()
        with self.assertRaises(AgentError) as error:
            self.agent.start(self.input, {}, fence)
        self.assertEqual('AGENT_OUTCOME_UNKNOWN', error.exception.code)
        self.assertTrue(error.exception.dispatched)
        self.assertNotIn('test-secret', str(error.exception))
        self.assertEqual(1, self.request.call_count)
        fence.assert_called_once()

    def test_redirect_is_rejected_and_write_without_fence_never_reaches_transport(self):
        with self.assertRaises(AgentError):
            self.agent.api('POST', '/v1/agents', body={})
        self.request.assert_not_called()
        self.request.return_value = Response({}, 302)
        with self.assertRaises(AgentError) as error:
            self.agent.api('GET', '/v1/models')
        self.assertEqual('AGENT_HTTP_302', error.exception.code)

    def test_jules_model_source_plan_and_capabilities_are_truthful(self):
        responses = [Response({'sources': [{'name': 'sources/s-1', 'githubRepo': {'owner': 'owner', 'repo': 'repo'}}]}),
                     Response({'name': 'sessions/s-2', 'state': 'AWAITING_PLAN_APPROVAL'})]
        request = Mock(side_effect=responses)
        agent = CloudAgent('jules', dict(self.config, model_id='provider-default'), request=request,
                           environment={'AGENT_TEST_TOKEN': 'test-secret'})
        qualification = agent.qualify('owner/repo')
        self.assertEqual('BRANCH_PREFLIGHT', qualification['source_binding'])
        result = agent.start(dict(self.input, model_id='provider-default'), qualification, Mock())
        self.assertEqual('WAITING_FOR_INPUT', result['state'])
        body = request.call_args.kwargs['json']
        self.assertTrue(body['requirePlanApproval'])
        self.assertNotIn('automationMode', body)
        self.assertEqual('feature', body['sourceContext']['githubRepoContext']['startingBranch'])
        with self.assertRaises(AgentError) as error:
            agent.control(result, 'cancel', None, Mock())
        self.assertEqual('AGENT_CAPABILITY_UNAVAILABLE', error.exception.code)

    def test_trickling_json_response_has_a_total_elapsed_bound(self):
        clock = Mock(side_effect=[0, 0, 1, 20, 41])
        response = Response({})
        response.iter_content = lambda unused: iter([b'{', b' ', b'}'])
        with self.assertRaises(HttpFailure) as error:
            perform({'backend': 'cursor', 'path': '/v1/models', 'method': 'GET', 'headers': {}},
                    request=Mock(return_value=response), clock=clock)
        self.assertEqual('AGENT_RESPONSE_DEADLINE', error.exception.code)

    def test_stalled_headers_are_killed_on_parent_cancellation_without_credential_argv(self):
        cancelled, children, received = threading.Event(), [], []
        real_popen = subprocess.Popen
        def stalled(command, **kwargs):
            received.append((command, kwargs['env']))
            child = real_popen([sys.executable, '-I', '-c', 'import sys,time; sys.stdin.buffer.read(); time.sleep(30)'], **kwargs)
            children.append(child)
            return child
        agent = CloudAgent('cursor', self.config, environment={'AGENT_TEST_TOKEN': 'test-secret'}, cancel=cancelled)
        timer = threading.Timer(.15, cancelled.set)
        before = time.monotonic()
        timer.start()
        try:
            with patch('v14.agent_cloud.subprocess.Popen', side_effect=stalled), self.assertRaises(AgentError) as error:
                agent.start(self.input, {}, Mock())
        finally:
            timer.cancel()
            for child in children:
                if child.poll() is None:
                    child.kill(); child.wait(timeout=2)
        self.assertLess(time.monotonic() - before, 3)
        self.assertTrue(error.exception.dispatched)
        self.assertEqual('AGENT_PARENT_STOPPED', error.exception.code)
        self.assertNotIn('test-secret', json.dumps(received))
        self.assertTrue(children and children[0].returncode is not None)

    def test_frozen_http_worker_uses_internal_entry_not_python_flags(self):
        child = Mock()
        child.communicate.return_value = (b'{"result":{}}', b'')
        child.poll.return_value = child.returncode = 0
        agent = CloudAgent('cursor', self.config, environment={'AGENT_TEST_TOKEN': 'test-secret'})
        with patch.object(sys, 'frozen', True, create=True), patch('v14.agent_cloud.subprocess.Popen', return_value=child) as launch:
            self.assertEqual({}, agent.api('GET', '/v1/models'))
        self.assertEqual([sys.executable, 'agent-http-worker'], launch.call_args.args[0])
        self.assertNotIn('test-secret', json.dumps(launch.call_args.args))


class SdkBoundaryTests(unittest.TestCase):
    @unittest.skipUnless(os.name == 'posix' and shutil.which('node'), 'Cline isolated Linux runtime uses Node')
    def test_cline_tools_abort_process_groups_without_retries_or_pipe_wait(self):
        source = Path(__file__).parents[1] / 'v14' / 'agent_cline_driver.mjs'
        # Replace only the optional SDK dependency; execute the shipped tool
        # functions against real files and a real child/grandchild process.
        harness = source.read_text().replace("const require = createRequire('/opt/gateway-agents/package.json');\nconst {Agent, createTool} = require('@cline/sdk');",
                                            'const createTool = value => value; const Agent = class {};')
        harness = harness[:harness.index('for await (const line of createInterface')]
        harness += '''
import assert from 'node:assert/strict';
assert.ok(tools.every(tool => tool.retryable === false && tool.maxRetries === 0));
await assert.rejects(tools.find(tool => tool.name === 'read_file').execute({path:'pipe'}), /regular/);
await assert.rejects(tools.find(tool => tool.name === 'edit_file').execute({path:'pipe',old:'x',replacement:'y'}), /regular/);
const controller = new AbortController();
const childProgram = "const {spawn}=require('node:child_process');spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:['ignore',process.stdout,process.stderr]});process.stdout.write('child ready');";
const started = Date.now();
setTimeout(() => controller.abort(), 150);
const result = await tools.find(tool => tool.name === 'run_check').execute({argv:[process.execPath,'-e',childProgram]}, {abortSignal:controller.signal});
assert.equal(result.stopped_reason, 'ABORTED');
assert.equal(result.exit_code, null);
assert.ok(Date.now() - started < 2500);
'''
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / 'harness.mjs').write_text(harness); os.mkfifo(root / 'pipe')
            result = subprocess.run(['node', str(root / 'harness.mjs')], cwd=root, capture_output=True, text=True, timeout=5)
            self.assertEqual(0, result.returncode, result.stderr)

    def test_file_tools_do_not_allow_shell_links_fifo_or_ambiguous_edits(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); (root / 'file.py').write_text('value = 1\n')
            tools = CheckoutTools(root)
            self.assertEqual('value = 1\n', tools.call('gateway_read_file', {'path': 'file.py'})['content'])
            tools.call('gateway_edit_file', {'path': 'file.py', 'old': '1', 'replacement': '2'})
            tools.call('gateway_edit_file', {'path': 'sub/new.py', 'old': '', 'replacement': 'new\n'})
            self.assertEqual('value = 2\n', (root / 'file.py').read_text())
            for kind, args in [('shell', {'command': 'git push'}), ('gateway_read_file', {'path': '../secret'}),
                               ('gateway_read_file', {'path': '.GiT/config'}),
                               ('gateway_edit_file', {'path': 'file.py', 'old': '', 'replacement': 'overwrite'})]:
                with self.assertRaises((ValueError, OSError)):
                    tools.call(kind, args)
            (root / 'link').symlink_to('/tmp')
            os.mkfifo(root / 'pipe')
            for path in ('link/secret', 'pipe'):
                with self.assertRaises((ValueError, OSError)):
                    tools.call('gateway_read_file', {'path': path})

    def test_copilot_empty_mode_limits_runtime_to_authenticated_file_tools(self):
        class Client:
            def __init__(self, **kwargs):
                self.options = kwargs
            async def start(self):
                pass
            async def create_session(self, **kwargs):
                self.session_options = kwargs
                return SimpleNamespace(on=lambda unused: None)
        runtime = SimpleNamespace(for_spawned_stdio=lambda **kwargs: kwargs)
        modules = {'copilot': SimpleNamespace(CopilotClient=Client, RuntimeConnection=runtime),
                   'copilot.tools': SimpleNamespace(Tool=lambda **kwargs: SimpleNamespace(**kwargs), ToolResult=lambda **kwargs: SimpleNamespace(**kwargs)),
                   'copilot.rpc': SimpleNamespace(PermissionDecisionReject=lambda **kwargs: SimpleNamespace(**kwargs))}
        driver = Driver('copilot_sdk', io.StringIO())
        options = {'model_id': 'exact-model', 'max_iterations': 2, 'max_budget_usd': 1}
        with tempfile.TemporaryDirectory() as temporary, patch.object(Path, 'cwd', return_value=Path(temporary)), \
                patch.dict(sys.modules, modules), patch.dict(os.environ, {'GH_TOKEN': 'test-inference-token', 'GITHUB_TOKEN': 'unrelated-host-secret'}):
            asyncio.run(driver.create({'cwd': temporary, '_meta': {'gateway': options}}))
            self.assertEqual('empty', driver.client.options['mode'])
            self.assertEqual({'token': 'test-inference-token', 'use_logged_in_user': False}, driver.client.options['github'])
            environment = driver.client.options['runtime']['env']
            self.assertNotIn('GH_TOKEN', environment); self.assertNotIn('GITHUB_TOKEN', environment)
            selected = driver.client.session_options
            self.assertFalse(selected['enable_config_discovery'])
            expected = ['gateway_list_files', 'gateway_read_file', 'gateway_edit_file']
            self.assertEqual(['custom:' + name for name in expected], selected['tools']['available_tools'])
            self.assertIn('Only the three', selected['permissions']['on_request']({}, {}).feedback)
            tools = selected['tools']['definitions']
            result = asyncio.run(tools[2].handler(SimpleNamespace(session_id=driver.session_id,
                                 arguments={'path': 'result.py', 'old': '', 'replacement': 'accepted\n'})))
            self.assertEqual('success', result.result_type)
            denied = asyncio.run(tools[1].handler(SimpleNamespace(session_id='other-session', arguments={'path': 'result.py'})))
            self.assertEqual('failure', denied.result_type)
            self.assertTrue(all(tool.skip_permission for tool in tools))

    def test_claude_toolset_and_settings_cannot_enable_shell_hooks_or_network(self):
        class Client:
            def __init__(self, options):
                self.options = options
            async def connect(self):
                pass
        module = SimpleNamespace(ClaudeSDKClient=Client, ClaudeAgentOptions=lambda **kwargs: kwargs)
        driver = Driver('claude', io.StringIO())
        with patch.dict(sys.modules, {'claude_agent_sdk': module}):
            asyncio.run(driver.create({'cwd': str(Path.cwd()), '_meta': {'gateway': {'model_id': 'exact-model', 'max_iterations': 2, 'max_budget_usd': 1}}}))
        self.assertEqual(['Read', 'Edit', 'Write', 'Glob', 'Grep'], driver.client.options['tools'])
        self.assertEqual([], driver.client.options['setting_sources'])
        self.assertTrue(driver.client.options['strict_mcp_config'])
        self.assertEqual({}, driver.client.options['mcp_servers'])


class CandidateTests(unittest.TestCase):
    def job(self):
        return {'data': {'head_sha': 'a' * 40, 'tree': {'file.py': {'sha': blob_sha('answer = 1\n'), 'mode': '100644'}}}}

    def test_exact_unified_patch_roundtrip_add_modify_and_delete(self):
        job = self.job()
        patch_value = ('diff --git a/file.py b/file.py\nindex aaaaaaa..bbbbbbb 100644\n--- a/file.py\n+++ b/file.py\n'
                       '@@ -1 +1 @@\n-answer = 1\n+answer = 2\n'
                       'diff --git a/new.py b/new.py\nnew file mode 100644\nindex 0000000..bbbbbbb\n--- /dev/null\n+++ b/new.py\n'
                       '@@ -0,0 +1 @@\n+new = True\n')
        changes = unified_changes(job, patch_value, lambda unused: 'answer = 1\n')
        self.assertEqual(['answer = 2\n', 'new = True\n'], [v['content'] for v in changes])
        self.assertIsNone(changes[1]['expected_blob_sha'])
        deletion = ('diff --git a/file.py b/file.py\ndeleted file mode 100644\n--- a/file.py\n+++ /dev/null\n'
                    '@@ -1 +0,0 @@\n-answer = 1\n')
        self.assertIsNone(unified_changes(job, deletion, lambda unused: 'answer = 1\n')[0]['content'])

    def test_patch_never_fuzzes_changed_context_or_unsafe_source(self):
        base = ('diff --git a/file.py b/file.py\n--- a/file.py\n+++ b/file.py\n@@ -1 +1 @@\n-answer = 0\n+answer = 2\n')
        with self.assertRaises(AgentError) as error:
            unified_changes(self.job(), base, lambda unused: 'answer = 1\n')
        self.assertEqual('AGENT_PATCH_SOURCE_CHANGED', error.exception.code)
        for path in ('../outside', '.git/config', '/tmp/file', 'FILE.py'):
            with self.subTest(path=path), self.assertRaises(AgentError):
                verify_changes(self.job(), [{'path': path, 'content': 'text', 'expected_blob_sha': None, 'mode': '100644'}])

    def test_candidate_rejects_wrong_blob_symlinks_mode_changes_and_parent_aliases(self):
        job = self.job()
        value = {'path': 'file.py', 'content': 'new', 'expected_blob_sha': '0' * 40, 'mode': '100644'}
        with self.assertRaises(AgentError):
            verify_changes(job, [value])
        value['expected_blob_sha'] = job['data']['tree']['file.py']['sha']; value['mode'] = '100755'
        with self.assertRaises(AgentError):
            verify_changes(job, [value])
        job['data']['tree']['link'] = {'sha': 'f' * 40, 'mode': '120000'}
        with self.assertRaises(AgentError):
            verify_changes(job, [{'path': 'link/file', 'content': 'new', 'expected_blob_sha': None, 'mode': '100644'}])


class Transport:
    def __init__(self):
        self.starts, self.reads, self.qualifications, self.controls, self.disposals = 0, 0, 0, 0, 0
        self.fail_start, self.before_start = False, None
        self.state = 'RUNNING'
        self.artifact = {'changes': [{'path': 'file.py', 'content': 'answer = 2\n', 'expected_blob_sha': blob_sha('answer = 1\n'), 'mode': '100644'}]}
    def qualify(self, repository):
        self.qualifications += 1
        return {'state': 'READY', 'source': 'sources/s-1', 'source_binding': 'SEALED_COMMIT'}
    def prepare(self, backend, job, run, *, check_owner=None):
        if check_owner is not None:
            check_owner()
        return {'container': 'c' * 64, 'container_identity': digest(run['run_id'])}
    def start(self, *args):
        if self.before_start:
            self.before_start()
        args[-1]()
        self.starts += 1
        if self.fail_start:
            raise requests.Timeout('provider-secret-should-not-leak')
        return {'state': self.state, 'session_id': 'session-1', 'remote_run_id': 'turn-1'}
    def status(self, saved):
        self.reads += 1
        return {'state': self.state, 'session_id': 'session-1', 'remote_run_id': 'turn-1'}
    def api(self, method, path, **kwargs):
        self.reads += 1
        return {'sessions': [], 'items': []}
    def control(self, *args):
        args[-1]()
        self.controls += 1
        return {'state': 'RUNNING', 'session_id': 'session-1', 'remote_run_id': 'turn-1'}
    def events(self, saved, after=None):
        return {'events': [{'text': 'private evidence'}], 'next_cursor': 1, 'complete': True}
    def artifacts(self, saved):
        return dict(self.artifact, state=self.state, complete=True, session_id='session-1', remote_run_id=saved.get('remote_run_id', 'turn-1'))
    def operation(self, saved, operation_id):
        return {'state': 'COMPLETED', 'result': self.status(saved)}
    def dispose(self, saved):
        self.disposals += 1
        return {'state': 'CANCELLED'}


class DurableAgentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root, self.now = Path(self.temp.name), 1000.
        runtime = SimpleNamespace(GATEWAY_DURABLE_JOBS=True, durable_job_initialized=True, durable_job_lock=threading.RLock(),
                                  _durable_connect=lambda: sqlite3.connect(self.root / 'jobs.db'))
        self.store = EngineeringStore(runtime, lambda: self.now)
        self.job = {'id': 'job', 'principal': 'owner', 'state': 'IMPLEMENT', 'revision': 7, '_execution_lease': 'lease', '_owner_epoch': 1,
                    'deadline': 4000., 'execution_binding': {'required_capability_policy': ['SELECTED_ONLY']},
                    'request': {'repository': 'owner/repo', 'branch': 'feature', 'base': 'main', 'objective': 'Fix the accepted task',
                                'task_scope': 'implement', 'model': 'parent-model'},
                    'data': {'head_sha': 'a' * 40, 'admission': {'policy_sha256': 'policy'}, 'files': {},
                             'tree': {'file.py': {'mode': '100644', 'sha': blob_sha('answer = 1\n')}}}}
        self.store.tx(lambda db: db.execute('INSERT INTO engineering_jobs VALUES(?,?,?,?,?,?,?,?,?)',
            ('job', 'owner', 'parent', 'parent-digest', 'IMPLEMENT', 7, self.now, 'lease', json.dumps(self.job))))
        self.store.tx(lambda db: db.execute('INSERT INTO engineering_owner_epochs VALUES(?,?)', ('job', 1)))
        self.backend = SimpleNamespace(ref=Mock(return_value='a' * 40), identity=Mock(return_value={'id': 7}))
        self.service = SimpleNamespace(store=self.store, backend=self.backend, artifacts=EngineeringArtifacts(self.store),
                                      validate_execution=lambda job, **unused: self.store.assert_claim(job))
        self.config = {'schema_version': 1, 'agents': {'jules': {'enabled': True, 'model_id': 'provider-default',
                       'principals': ['owner'], 'repositories': ['owner/repo'], 'credential_reference': 'env:AGENT_TEST_TOKEN'}}}
        self.environment = patch.dict(os.environ, {'AGENT_TEST_TOKEN': 'private-test-credential'})
        self.environment.start(); self.addCleanup(self.environment.stop)
        self.transport = Transport()
        self.agents = AgentRuns(self.service, config_loader=lambda: (self.config, digest(self.config)),
                                transport_factory=lambda unused, config: self.transport)

    def action(self, kind, **values):
        self.job['_workspace_user_command_id'] = values.get('request_id', 'read-command')
        try:
            return self.agents.act(self.job, {'action': 'agent_' + kind, 'head_sha': self.job['data']['head_sha'], **values})
        finally:
            self.job.pop('_workspace_user_command_id', None)

    def qualify(self, backend='jules'):
        return self.action('qualify', backend=backend)

    def start(self, backend='jules', request_id='start'):
        return self.action('start', backend=backend, model_id=self.config['agents'][backend]['model_id'],
                           objective=self.job['request']['objective'], consent=True, request_id=request_id)

    def local(self):
        self.config['agents']['codex'] = dict(self.config['agents']['jules'], model_id='model-x', image='agent@sha256:' + 'd' * 64)
        self.qualify('codex')

    def test_read_projections_do_not_contact_provider_or_expose_configuration(self):
        self.qualify()
        self.transport.qualify = Mock(side_effect=AssertionError('GET must not qualify'))
        self.transport.status = Mock(side_effect=AssertionError('GET must not poll'))
        rows = self.agents.capabilities(self.job)
        self.assertTrue(next(row for row in rows if row['id'] == 'jules')['ready'])
        self.assertEqual([], self.agents.list(self.job))
        raw = json.dumps(rows)
        self.assertLess(len(raw), 48000)
        self.assertNotIn('AGENT_TEST_TOKEN', raw)
        self.assertNotIn('private-test-credential', raw)
        self.assertNotIn('credential_reference', raw)

    def test_start_requires_qualification_consent_exact_objective_model_and_source(self):
        with self.assertRaises(AgentError) as error:
            self.start()
        self.assertEqual('AGENT_QUALIFICATION_REQUIRED', error.exception.code)
        self.qualify()
        for override in ({'consent': False}, {'objective': 'different task'}, {'model_id': 'fallback'}, {'head_sha': 'b' * 40}):
            value = dict(action='agent_start', backend='jules', head_sha='a' * 40, model_id='provider-default',
                         objective=self.job['request']['objective'], consent=True, request_id='start', **{})
            value.update(override)
            with self.assertRaises(AgentError):
                self.agents.act(self.job, value)
        self.assertEqual(0, self.transport.starts)
        self.backend.ref.return_value = 'b' * 40
        with self.assertRaises(AgentError) as error:
            self.start()
        self.assertEqual('AGENT_SOURCE_CHANGED', error.exception.code)

    def test_private_policy_and_other_principal_fail_closed(self):
        self.job['execution_binding']['required_capability_policy'].append('LOCAL_ONLY')
        with self.assertRaises(AgentError):
            self.qualify()
        self.assertEqual(0, self.transport.qualifications)
        self.assertFalse(any(row['ready'] for row in self.agents.capabilities(self.job)))
        self.job['execution_binding']['required_capability_policy'].remove('LOCAL_ONLY')
        self.qualify(); result = self.start()
        other = dict(self.job, principal='other')
        with self.assertRaises(AgentError):
            self.agents.get(other, result['run_id'])

    def test_model_cannot_manufacture_remote_consent_or_paid_control(self):
        self.qualify()
        with self.assertRaises(AgentError) as error:
            self.agents.act(self.job, {'action': 'agent_start', 'head_sha': 'a' * 40, 'backend': 'jules',
                                      'model_id': 'provider-default', 'objective': self.job['request']['objective'],
                                      'consent': True, 'request_id': 'invented-by-model'})
        self.assertEqual('AGENT_USER_COMMAND_REQUIRED', error.exception.code)
        self.assertEqual(0, self.transport.starts)
        self.assertTrue(self.action('qualify', backend='jules', request_id='queue-read-id')['ready'])

    def test_restart_retry_observes_existing_remote_run_without_replaying_start(self):
        self.qualify(); first = self.start()
        restarted = AgentRuns(self.service, config_loader=self.agents.config_loader, transport_factory=self.agents.transport_factory)
        self.agents = restarted
        second = self.start()
        self.assertEqual(first['run_id'], second['run_id'])
        self.assertEqual(1, self.transport.starts)
        self.assertEqual(1, self.transport.reads)
        self.assertNotIn(self.job['request']['objective'], json.dumps(second))
        db_payload = self.agents.rows(self.job)[0]
        self.assertNotIn(self.job['request']['objective'], json.dumps(db_payload))

    def test_unknown_cloud_start_is_durable_and_never_retried_after_restart(self):
        self.qualify(); self.transport.fail_start = True
        first = self.start()
        self.assertEqual('OUTCOME_UNKNOWN', first['state'])
        self.assertEqual('OUTCOME_UNKNOWN', first['operation_state'])
        second = self.start()
        self.assertEqual('OUTCOME_UNKNOWN', second['state'])
        self.assertEqual(1, self.transport.starts)
        self.assertNotIn('provider-secret', json.dumps(second))

    def test_lease_loss_at_last_boundary_prevents_external_start(self):
        self.qualify()
        self.transport.before_start = lambda: self.store.tx(lambda db: db.execute('UPDATE engineering_owner_epochs SET epoch=2 WHERE job_id=?', ('job',)))
        with self.assertRaises(Exception):
            self.start()
        self.assertEqual(0, self.transport.starts)

    def test_config_or_credential_change_never_redirects_existing_run(self):
        self.qualify(); result = self.start()
        with patch.dict(os.environ, {'AGENT_TEST_TOKEN': 'another-account'}):
            with self.assertRaises(AgentError) as error:
                self.action('status', run_id=result['run_id'])
        self.assertEqual('AGENT_CONFIGURATION_CHANGED', error.exception.code)
        self.assertEqual(0, self.transport.reads)

    def test_evidence_redacts_runtime_credentials_and_candidate_capture_rejects_them(self):
        self.local(); result = self.start('codex')
        self.transport.events = Mock(return_value={'events': [{'text': 'token private-test-credential'}], 'complete': True})
        receipt = self.action('events', run_id=result['run_id'])
        evidence = self.service.artifacts.json_value(self.job, receipt['result_ref'])
        self.assertNotIn('private-test-credential', json.dumps(evidence))
        self.assertIn('REDACTED', json.dumps(evidence))
        self.transport.state = 'COMPLETED'
        self.transport.artifact['changes'][0]['content'] = 'token = "private-test-credential"\n'
        result = self.action('status', run_id=result['run_id'])
        self.assertEqual('COMPLETED', result['state'])
        self.assertEqual('CAPTURE_BLOCKED', result['candidate_status'])
        with self.assertRaises(AgentError) as error:
            self.action('artifacts', run_id=result['run_id'])
        self.assertEqual('AGENT_CREDENTIAL_IN_CANDIDATE', error.exception.code)

    def test_exact_control_identity_prevents_duplicate_steering(self):
        self.local(); result = self.start('codex')
        arguments = dict(run_id=result['run_id'], request_id='steer-1', message='Run the accepted check', expected_remote_run_id='turn-1')
        self.action('steer', **arguments)
        self.action('steer', **arguments)
        self.assertEqual(1, self.transport.controls)
        with self.assertRaises(AgentError) as error:
            self.action('steer', **dict(arguments, message='Change the task'))
        self.assertEqual('AGENT_REQUEST_CONFLICT', error.exception.code)

    def test_completed_local_candidate_is_retained_before_container_expires_and_not_promoted(self):
        self.local(); result = self.start('codex')
        self.transport.state = 'COMPLETED'
        self.action('status', run_id=result['run_id'])
        retained = self.agents.get(self.job, result['run_id'])
        self.assertEqual('CAPTURED_UNVALIDATED', retained['candidate_status'])
        self.transport.artifacts = Mock(side_effect=AssertionError('retained candidate must survive runtime loss'))
        with patch('v14.engineering_workspace_checks.catalog', return_value=('policy', [])):
            imported = self.action('import', run_id=result['run_id'])
        self.assertEqual('UNVERIFIED', imported['candidate']['status'])
        self.assertFalse(imported['candidate']['complete_validation'])
        self.assertNotIn('changes', self.job['data'])
        self.assertEqual('parent-model', self.job['request']['model'])
        self.assertIn(imported['candidate_check_id'], self.job['data']['workspace_checks'])
        self.assertRegex(imported['candidate_check_id'], '^[a-f0-9]{64}$')

    def test_imported_candidate_uses_existing_details_and_rewind_contract(self):
        from v14.engineering_checkpoints import candidate_details, rewind
        self.local(); result = self.start('codex'); self.transport.state = 'COMPLETED'
        self.action('status', run_id=result['run_id'])
        self.backend.private_artifacts = self.service.artifacts
        self.service.evidence_access = lambda principal, identity: self.store.get(identity, principal)
        self.service.wake = threading.Event()
        self.store.conversations.engineering_projection = Mock()
        with patch('v14.engineering_workspace_checks.catalog', return_value=('policy', [])):
            imported = self.action('import', run_id=result['run_id'])
            self.job.update(state='NEEDS_INPUT', revision=8)
            self.job.pop('_execution_lease')
            self.job['data']['files']['file.py'] = {'content': 'answer = 1\n', 'sha': blob_sha('answer = 1\n')}
            self.store.tx(lambda db: db.execute('UPDATE engineering_jobs SET state=?,revision=?,lease=NULL,data=? WHERE id=?',
                                               ('NEEDS_INPUT', 8, json.dumps(self.job), 'job')))
            details = candidate_details(self.service, 'owner', 'job', imported['candidate_check_id'])
            self.assertIn('-answer = 1\n+answer = 2\n', details['diff'])
            self.assertIsNone(imported['candidate']['check_index'])
            restored = rewind(self.service, 'owner', 'job', 8, imported['candidate_check_id'])
            self.assertEqual('REVIEW', restored['state'])
            self.assertEqual('answer = 2\n', restored['data']['changes'][0]['content'])
            self.assertTrue(self.service.wake.is_set())

    def test_lost_steer_response_cannot_reuse_previous_turn_candidate(self):
        self.local(); result = self.start('codex'); self.transport.state = 'COMPLETED'
        self.action('status', run_id=result['run_id'])
        previous = self.agents.get(self.job, result['run_id'])['candidate_raw_ref']
        def lost(*args):
            args[-1]()
            raise requests.Timeout('response lost after remote acceptance')
        self.transport.control = lost
        self.action('steer', run_id=result['run_id'], request_id='followup', expected_remote_run_id='turn-1', message='Finish the accepted fix')
        retained = self.agents.get(self.job, result['run_id'])
        self.assertIsNone(retained['candidate_raw_ref'])
        self.assertEqual('OUTCOME_UNKNOWN', retained['state'])
        with patch('v14.engineering_workspace_checks.catalog', return_value=('policy', [])), self.assertRaises(AgentError):
            self.action('import', run_id=result['run_id'])
        self.transport.status = lambda unused: {'state': 'COMPLETED', 'session_id': 'session-1', 'remote_run_id': 'turn-2'}
        self.transport.artifact['changes'][0]['content'] = 'answer = 3\n'
        self.action('status', run_id=result['run_id'])
        retained = self.agents.get(self.job, result['run_id'])
        self.assertEqual('turn-2', retained['remote_run_id'])
        self.assertNotEqual(previous, retained['candidate_raw_ref'])
        self.assertEqual('answer = 2\n', self.service.artifacts.json_value(self.job, previous)['changes'][0]['content'])
        with patch('v14.engineering_workspace_checks.catalog', return_value=('policy', [])):
            imported = self.action('import', run_id=result['run_id'])
        changes = self.service.artifacts.json_value(self.job, imported['candidate']['candidate_ref'])
        self.assertEqual('answer = 3\n', changes[0]['content'])

    def test_unknown_cursor_creation_does_not_adopt_a_different_clients_followup(self):
        self.config['agents']['cursor'] = dict(self.config['agents']['jules'], model_id='model-x')
        self.qualify('cursor'); self.transport.fail_start = True
        first = self.start('cursor')
        saved = self.agents.get(self.job, first['run_id'])
        self.transport.api = Mock(side_effect=[
            {'items': [{'id': 'agent-1', 'name': saved['correlation']}]},
            {'id': 'agent-1', 'latestRunId': 'later-run', 'repos': [{'url': 'https://github.com/owner/repo', 'startingRef': 'a' * 40}],
             'workOnCurrentBranch': False, 'autoCreatePR': False},
            {'items': [{'id': 'later-run', 'agentId': 'agent-1'}, {'id': 'original-run', 'agentId': 'agent-1'}]}])
        result = self.start('cursor')
        self.assertEqual('OUTCOME_UNKNOWN', result['state'])
        self.assertNotIn('remote_run_id', result)
        self.assertEqual(1, self.transport.starts)

    def test_cancel_racing_prepared_container_compensates_without_start(self):
        self.local()
        original = self.transport.prepare
        def prepare(backend, job, run, **kwargs):
            result = original(backend, job, run, **kwargs)
            self.store.tx(lambda db: db.execute('UPDATE engineering_owner_epochs SET epoch=2 WHERE job_id=?', ('job',)))
            return result
        self.transport.prepare = prepare
        with self.assertRaises(Exception):
            self.start('codex')
        self.assertEqual(0, self.transport.starts)
        self.assertEqual(1, self.transport.disposals)

    def test_advance_uses_parent_clock_one_run_and_bounded_wait(self):
        self.qualify(); self.start()
        self.now += 16
        receipt = self.agents.advance(self.job)
        self.assertTrue(receipt['holding']); self.assertTrue(receipt['pending'])
        self.assertEqual(1, len(receipt['observed']))
        self.assertGreaterEqual(receipt['next_at'], self.now + 10)
        self.assertLessEqual(receipt['next_at'], self.now + 30)

    def test_parent_cancel_hard_stops_local_even_when_configuration_removed(self):
        self.local(); self.start('codex'); self.config['agents'].clear()
        result = self.agents.cancel_all(self.job)
        self.assertTrue(result['all_stopped'])
        self.assertEqual(1, self.transport.disposals)

    def test_unsupported_remote_cancel_reports_continuation(self):
        self.qualify(); self.start()
        result = self.agents.cancel_all(self.job)
        self.assertFalse(result['all_stopped'])
        self.assertEqual('REMOTE_CONTINUES', result['runs'][0]['state'])
        self.assertTrue(result['runs'][0]['detached'])
        self.assertEqual(0, self.transport.controls)

    def test_post_cancel_cleanup_uses_terminal_revision_without_revoked_lease(self):
        self.local(); self.start('codex'); self.config['agents'].clear()
        self.job.update(state='CANCELLED', revision=8)
        self.job.pop('_execution_lease')
        self.store.tx(lambda db: db.execute('UPDATE engineering_jobs SET state=?,revision=?,lease=NULL,data=? WHERE id=?',
                                           ('CANCELLED', 8, json.dumps(self.job), 'job')))
        self.store.tx(lambda db: db.execute('UPDATE engineering_owner_epochs SET epoch=2 WHERE job_id=?', ('job',)))
        result = self.agents.stop_parent(self.job)
        self.assertTrue(result['all_stopped'])
        self.assertEqual(1, self.transport.disposals)
        self.assertTrue(self.agents.stop_parent(self.job)['all_stopped'])
        self.assertEqual(1, self.transport.disposals)
        with self.assertRaises(AgentError):
            self.agents.stop_parent(dict(self.job, revision=7))

    def test_post_cancel_cleanup_rejects_live_parent_or_other_principal(self):
        with self.assertRaises(AgentError):
            self.agents.stop_parent(self.job)
        with self.assertRaises(AgentError):
            self.agents.stop_parent(dict(self.job, principal='other', state='CANCELLED'))
        self.assertEqual(0, self.transport.disposals)

    def test_uncertain_remote_parent_cancel_is_not_replayed(self):
        self.config['agents']['cursor'] = dict(self.config['agents']['jules'], model_id='model-x')
        self.qualify('cursor'); self.start('cursor')
        self.job.update(state='CANCELLED', revision=8)
        self.store.tx(lambda db: db.execute('UPDATE engineering_jobs SET state=?,revision=?,lease=NULL,data=? WHERE id=?',
                                           ('CANCELLED', 8, json.dumps(self.job), 'job')))
        def lost_cancel(*args):
            args[-1]()
            self.transport.controls += 1
            raise requests.Timeout('unconfirmed')
        self.transport.control = lost_cancel
        for _ in range(2):
            receipt = self.agents.stop_parent(self.job)
            self.assertFalse(receipt['all_stopped'])
            self.assertEqual('REMOTE_CONTINUES', receipt['runs'][0]['state'])
        self.assertEqual(1, self.transport.controls)

    def test_cursor_queued_steer_cannot_misatttibute_a_later_run(self):
        self.config['agents']['cursor'] = dict(self.config['agents']['jules'], model_id='model-x')
        self.qualify('cursor'); first = self.start('cursor')
        self.transport.api = Mock(return_value={'id': 'session-1', 'latestRunId': 'turn-1'})
        def queued(*args):
            args[-1]()
            self.transport.controls += 1
            return {'delivery': 'queued'}
        self.transport.control = queued
        result = self.action('steer', run_id=first['run_id'], expected_remote_run_id='turn-1', request_id='steer-1', message='Please add a test')
        self.assertEqual('queued', result['steer_delivery'])
        self.transport.api.return_value = {'id': 'session-1', 'latestRunId': 'someone-elses-run'}
        result = self.action('status', run_id=first['run_id'])
        self.assertEqual('WAITING_FOR_INPUT', result['state'])
        self.assertEqual('turn-1', result['remote_run_id'])
        self.assertEqual('UNVERIFIED_FOLLOWUP', result['candidate_attribution'])
        with self.assertRaises(AgentError) as error:
            self.action('import', run_id=first['run_id'])
        self.assertEqual('AGENT_FOLLOWUP_ATTRIBUTION_REQUIRED', error.exception.code)

    def test_comparison_preserves_system_label_identity_budget_and_no_score(self):
        self.local(); self.qualify()
        result = self.action('compare', backends=['codex', 'jules'], objective=self.job['request']['objective'], consent=True, request_id='compare-1')
        self.assertEqual('AGENT_SYSTEM', result['comparison_scope'])
        self.assertFalse(result['scored'])
        self.assertEqual(2, len(result['run_ids']))
        self.assertEqual(2, self.transport.starts)
        again = self.action('compare', backends=['codex', 'jules'], objective=self.job['request']['objective'], consent=True, request_id='compare-1')
        self.assertEqual(result['run_ids'], again['run_ids'])
        self.assertEqual(2, self.transport.starts)
        self.assertEqual({'a' * 40}, {row['source_sha'] for row in self.agents.list(self.job)})

    def test_partial_comparison_retains_admitted_effects_and_never_restarts(self):
        self.local()  # Jules is deliberately not qualified.
        arguments = dict(backends=['codex', 'jules'], objective=self.job['request']['objective'], consent=True, request_id='partial-compare')
        result = self.action('compare', **arguments)
        self.assertEqual('PARTIAL', result['state'])
        self.assertEqual(1, len(result['run_ids']))
        self.assertEqual('AGENT_QUALIFICATION_REQUIRED', result['failures'][0]['error_code'])
        self.qualify('jules')
        self.assertEqual(result, self.action('compare', **arguments))
        self.assertEqual(1, self.transport.starts)


class ProcessProtocolTests(unittest.TestCase):
    def test_prepare_cleans_up_lost_creation_response_and_revoked_final_claim(self):
        for failure in ('lost-create-response', 'cancel-after-seal', 'claim-after-seal'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as temporary:
                cancelled, state = threading.Event(), {'created': False, 'removed': False, 'claims': 0}
                now = time.time()
                job = {'request': {'repository': 'owner/repo'}, 'deadline': now + 60,
                       'data': {'tree': {'file.py': {'sha': blob(b'original\n'), 'mode': '100644'}}}}
                run = {'principal': 'owner', 'job_id': 'job', 'run_id': 'run', 'source_sha': 'a' * 40,
                       'config_digest': 'config', 'model_id': 'model-x', 'deadline': now + 60}
                identity = LocalAgent.intent(run)['container_identity']
                backend = SimpleNamespace(cancellation=lambda unused: cancelled, root=lambda unused: '/repos/owner/repo',
                                          api=lambda *args, **kwargs: {'tree': {'sha': 'b' * 40}}, token=lambda: 'private-test-token')
                transport = LocalAgent('codex', {'image': 'agent@sha256:' + 'd' * 64})
                def docker(args, **kwargs):
                    if args[0] == 'container':
                        return ('c' * 64 + ' ' + identity + ' true') if state['created'] else None
                    if args[0] == 'run':
                        state['created'] = True
                        if failure == 'lost-create-response':
                            raise AgentError('AGENT_CONTAINER_UNAVAILABLE', 'Lost create response')
                        return 'c' * 64
                    if args[0] == 'exec' and failure == 'cancel-after-seal':
                        cancelled.set()
                    if args[0] == 'rm':
                        state.update(created=False, removed=True)
                    return ''
                def materialize(job, mirror, changes, root, cancel):
                    checkout = root / 'checkout'; checkout.mkdir(); (checkout / 'file.py').write_text('original\n')
                    return checkout
                def claim():
                    state['claims'] += 1
                    if failure == 'claim-after-seal' and state['claims'] == 2:
                        raise AgentError('AGENT_EXECUTION_OWNER_CHANGED', 'Parent revoked')
                transport.docker = docker
                with patch('v14.agent_local.load_policy', return_value=({}, Path(temporary), None, None, None)), \
                        patch('v14.engineering_source.prepare_source'), \
                        patch('v14.agent_local.materialize_checkout', side_effect=materialize), \
                        patch('v14.workspace_environment.read', return_value=(None, None, {'memory_gib': 1, 'cpus': 1, 'checkout_gib': 1})), \
                        self.assertRaises(AgentError):
                    transport.prepare(backend, job, run, check_owner=claim)
                self.assertTrue(state['removed'])
                self.assertFalse(state['created'])

    def test_candidate_mode_changes_and_special_files_fail_explicitly(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); path = root / 'file.py'; path.write_text('original\n')
            runtime = AgentProcess(root, root, time.time() + 10)
            runtime.baseline = {'file.py': {'sha': blob(b'original\n'), 'mode': '100644'}}
            runtime.state = 'COMPLETED'
            path.chmod(0o755)
            with self.assertRaisesRegex(ValueError, 'mode'):
                runtime.changes()
            path.chmod(0o644)
            new = root / 'new.sh'; new.write_text('echo hi\n'); new.chmod(0o755)
            with self.assertRaisesRegex(ValueError, 'mode'):
                runtime.changes()
            new.unlink(); path.unlink(); os.mkfifo(path)
            with self.assertRaisesRegex(ValueError, 'nonregular'):
                runtime.changes()

    def test_root_candidate_open_does_not_follow_a_raced_parent_link(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); work = root / 'checkout'; control = root / 'control'
            work.mkdir(); control.mkdir(); (control / 'secret').write_text('do-not-read')
            (work / 'sub').symlink_to(control, target_is_directory=True)
            runtime = AgentProcess(work, control, time.time() + 10)
            with self.assertRaises(OSError):
                runtime.open_source('sub/secret')

    def test_late_completion_cannot_reverse_terminal_cancellation(self):
        with tempfile.TemporaryDirectory() as temporary:
            runtime = AgentProcess(temporary, temporary, time.time() + 10)
            runtime.session_id, runtime.turn_id, runtime.prompt_id = 'thread', 'turn', '1'
            frames = [{'id': '1', 'result': {'stopReason': 'end_turn'}},
                      {'method': 'turn/completed', 'params': {'threadId': 'thread', 'turn': {'id': 'turn', 'status': 'completed'}}}]
            runtime.process = SimpleNamespace(stdout=io.BytesIO(('\n'.join(json.dumps(row) for row in frames) + '\n').encode()), poll=lambda: 0)
            runtime.state = 'RUNNING'
            runtime.stop('CANCELLED'); runtime.read()
            self.assertEqual('CANCELLED', runtime.snapshot()['state'])
            with self.assertRaises(ValueError):
                runtime.prompt('another paid turn')

    def test_fast_sdk_aborted_response_stays_cancelled(self):
        with tempfile.TemporaryDirectory() as temporary:
            runtime = AgentProcess(temporary, temporary, time.time() + 10)
            runtime.backend, runtime.session_id = 'cline', 'session-1'
            runtime.request = lambda *args, **kwargs: 'prompt-1'
            runtime.results['prompt-1'] = {'result': {'stopReason': 'cancelled'}}
            self.assertEqual('CANCELLED', runtime.prompt('finish the accepted task')['state'])

    def test_root_owned_source_index_ignores_build_output_but_keeps_tracked_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); work = root / 'checkout'; control = root / 'control'
            work.mkdir(); control.mkdir()
            source = {'.gitignore': 'build/\n', 'file.py': 'original\n'}
            for name, text in source.items():
                (work / name).write_text(text)
            runtime = AgentProcess(work, control, time.time() + 10)
            runtime.baseline = {name: {'sha': blob(text.encode()), 'mode': '100644'} for name, text in source.items()}
            runtime.source_catalog()
            (work / 'build').mkdir(); (work / 'build' / 'huge.bin').write_bytes(b'\0' * (5 * 1024 * 1024))
            (work / 'file.py').write_text('changed\n')
            (work / 'new.py').write_text('new\n')
            (work / '.git').mkdir(); (work / '.git' / 'config').write_text('[core]\nfsmonitor = malicious\n')
            runtime.state = 'COMPLETED'
            self.assertEqual(['file.py', 'new.py'], [row['path'] for row in runtime.changes()['changes']])

    def test_parent_cleanup_finds_container_after_lost_creation_response(self):
        run = {'principal': 'owner', 'job_id': 'job', 'run_id': 'run', 'source_sha': 'a' * 40, 'config_digest': 'config'}
        intended, calls = LocalAgent.intent(run), []
        def execute(argv, cwd, timeout, **kwargs):
            calls.append(argv)
            text = ('c' * 64 + ' ' + intended['container_identity'] + ' true') if 'inspect' in argv else ''
            kwargs['destination'].write(text.encode())
            return {'exit_code': 0, 'output_tail': text}
        transport = LocalAgent('codex', {}, runner=execute)
        result = transport.dispose(dict(run, **intended))
        self.assertEqual('CANCELLED', result['state'])
        self.assertIn(intended['container_name'], calls[0])
        self.assertEqual(['docker', 'rm', '-f', 'c' * 64], calls[-1])
        with self.assertRaises(AgentError):
            transport.dispose(dict(run, principal='other', **intended))

    def test_real_json_rpc_child_is_retained_and_edits_only_its_candidate(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); work = root / 'checkout'; control = root / 'control'
            work.mkdir(); control.mkdir(); (root / 'home').mkdir()
            (work / 'file.py').write_text('answer = 1\n')
            script = root / 'fake_agent.py'
            script.write_text('''import json,sys
from pathlib import Path
for line in sys.stdin:
    value=json.loads(line); method=value.get('method'); params=value.get('params', {})
    if 'id' not in value: continue
    if method=='initialize': result={}
    elif method=='thread/start': result={'thread':{'id':'thread-1'}, 'model':'model-x'}
    elif method=='turn/start':
        Path('file.py').write_text('answer = 2\\n')
        result={'turn':{'id':'turn-1'}}
    elif method=='turn/steer': result={}
    else: result={}
    print(json.dumps({'jsonrpc':'2.0','id':value['id'],'result':result}),flush=True)
    if method=='turn/start':
        print(json.dumps({'jsonrpc':'2.0','method':'turn/completed','params':{'threadId':'thread-1','turn':{'id':'turn-1','status':'completed'}}}),flush=True)
''')
            config = {'backend': 'codex', 'model_id': 'model-x', 'command': [sys.executable, '-u', str(script)],
                      'baseline': {'file.py': {'sha': blob(b'answer = 1\n'), 'mode': '100644'}}}
            (control / 'launch.json').write_text(json.dumps(config))
            runtime = AgentProcess(work, control, time.time() + 10)
            try:
                first = runtime.dispatch({'action': 'start', 'objective': 'fix', 'operation_id': 'op1'})
                deadline = time.monotonic() + 2
                while runtime.state == 'RUNNING' and time.monotonic() < deadline:
                    time.sleep(.01)
                self.assertEqual('COMPLETED', runtime.state)
                self.assertEqual('answer = 2\n', runtime.dispatch({'action': 'artifacts'})['changes'][0]['content'])
                second = runtime.dispatch({'action': 'start', 'objective': 'fix', 'operation_id': 'op1'})
                self.assertEqual(first, second)
                self.assertEqual('COMPLETED', runtime.dispatch({'action': 'operation', 'operation_id': 'op1'})['state'])
                with self.assertRaises(ValueError):
                    runtime.dispatch({'action': 'start', 'objective': 'different', 'operation_id': 'op1'})
                events = runtime.dispatch({'action': 'events', 'after': 0})
                self.assertTrue(events['events'])
            finally:
                runtime.stop('CANCELLED')
                if runtime.process:
                    runtime.process.wait(timeout=2)
                    for stream in (runtime.process.stdin, runtime.process.stdout, runtime.process.stderr):
                        stream.close()

    def test_candidate_snapshot_accepts_unchanged_large_binary_but_rejects_changed_link(self):
        with tempfile.TemporaryDirectory() as temporary:
            work = Path(temporary)
            large = b'\0' * (5 * 1024 * 1024); (work / 'large.bin').write_bytes(large)
            (work / 'changed.py').write_text('new')
            runtime = AgentProcess(work, work, time.time() + 10)
            runtime.baseline = {'large.bin': {'sha': blob(large), 'mode': '100644'}}
            runtime.state = 'COMPLETED'
            self.assertEqual(['changed.py'], [v['path'] for v in runtime.changes()['changes']])
            (work / 'link').symlink_to('/tmp')
            with self.assertRaises(ValueError):
                runtime.changes()


if __name__ == '__main__':
    unittest.main()
