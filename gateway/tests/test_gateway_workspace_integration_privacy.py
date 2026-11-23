"""Workspace admission must precede real catalogue discovery and exact dispatch."""
import copy
import hashlib
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from gateway_security import current_device
from v14.catalog import CatalogCache, _ScopedDiscoveryCancellation
from v14.execution_binding import canonical, fingerprint
from v14.tools import install_exact_routing
from v14 import workspace_tool_integrations as integrations


class WorkspaceIntegrationPrivacyTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.lock = threading.RLock()
        self.calls = []
        self.cancel = threading.Event()
        self.connections = {'docs': {'command': ['configured-docs']},
                            'other': {'command': ['unrelated-service']}}
        self.schema = {'type': 'object', 'properties': {'query': {'type': 'string'}},
                       'required': ['query'], 'additionalProperties': False}
        self.advertised = {'docs': [{'name': 'query-docs', 'inputSchema': self.schema}],
                           'other': [{'name': 'read', 'inputSchema': {'type': 'object'}}]}
        self.policy = {'workspace_integrations': {'context7': {
            'enabled': True, 'allow_remote': True, 'tools': ['docs_query-docs']}}}
        self.job = {'id': 'job-private-discovery', 'principal': 'owner',
                    'request': {'repository': 'owner/repo'},
                    'data': {'head_sha': 'a' * 40, 'admission': {'policy_sha256': fingerprint(self.policy)}}}
        self.values = {}
        def put_json(job, value, purpose):
            raw = canonical(value).encode()
            key = hashlib.sha256(raw).hexdigest()
            self.values[key] = copy.deepcopy(value)
            return {'result_ref': key, 'sha256': key, 'byte_length': len(raw)}
        self.runtime = SimpleNamespace(
            GATEWAY_DURABLE_JOBS=True, durable_job_initialized=True,
            durable_job_lock=threading.RLock(), mcp_cache_lock=threading.RLock(),
            _durable_connect=lambda: sqlite3.connect(Path(self.temp.name) / 'actions.db'),
            load_mcp_config=lambda: copy.deepcopy(self.connections), mcp_tool_metadata={},
            mcp_call=self.rpc)
        self.catalog = CatalogCache(self.runtime, interactive=1, total=2)
        self.runtime.gateway_catalogue = self.catalog
        self.runtime.get_all_mcp_tools = self.catalog.get
        install_exact_routing(self.runtime)
        self.backend = SimpleNamespace(runtime=self.runtime, cancellation=lambda job: self.cancel,
            private_artifacts=SimpleNamespace(store=SimpleNamespace(assert_claim=Mock()), put_json=put_json))
        policy = patch.object(integrations, 'load_policy', side_effect=lambda repository: (copy.deepcopy(self.policy),))
        policy.start()
        self.addCleanup(policy.stop)
        self.addCleanup(self.finish_discovery)

    def rpc(self, server, method, params, **kwargs):
        with self.lock:
            self.calls.append((server, method, copy.deepcopy(params), current_device.get()))
        if method == 'tools/list':
            return {'tools': copy.deepcopy(self.advertised[server])}
        return {'content': [{'type': 'text', 'text': 'Observed documentation'}]}

    def finish_discovery(self):
        with self.catalog.lock:
            pending = list(self.catalog.flights.values())
        for event, cancel in pending:
            cancel.set()
            self.assertTrue(event.wait(3), 'The discovery producer did not finish')

    def admit_policy(self):
        self.job['data']['admission']['policy_sha256'] = fingerprint(self.policy)

    def action(self, **changes):
        return {'integration': 'context7', 'tool': 'docs_query-docs', 'arguments': {'query': 'API v2'},
                'invocation_id': 'docs-one', **changes}

    def discovery(self):
        with self.lock:
            return [row for row in self.calls if row[1] == 'tools/list']

    def background(self, function, name='workspace-caller'):
        result, done = {}, threading.Event()
        def run():
            try:
                result['value'] = function()
            except BaseException as error:
                result['error'] = error
            finally:
                done.set()
        thread = threading.Thread(target=run, name=name, daemon=True)
        thread.start()
        self.addCleanup(lambda: thread.join(3))
        return done, result

    def test_private_status_never_starts_catalogue_io(self):
        self.job['request']['private'] = True
        result = integrations.status(self.backend, self.job, discover=True)
        self.assertEqual('LOCAL_ONLY_POLICY', result['integrations'][0]['state'])
        self.assertEqual([], self.calls)

    def test_local_only_status_never_starts_catalogue_io(self):
        self.job['execution_binding'] = {'required_capability_policy': ['LOCAL_ONLY']}
        result = integrations.status(self.backend, self.job, discover=True)
        self.assertEqual('LOCAL_ONLY_POLICY', result['integrations'][0]['state'])
        self.assertEqual([], self.calls)

    def test_disabled_status_never_starts_catalogue_io(self):
        self.policy['workspace_integrations']['context7']['enabled'] = False
        self.admit_policy()
        result = integrations.status(self.backend, self.job, discover=True)
        self.assertEqual('DISABLED', result['integrations'][0]['state'])
        self.assertEqual([], self.calls)

    def test_remote_denied_status_never_starts_catalogue_io(self):
        self.policy['workspace_integrations']['context7']['allow_remote'] = False
        self.admit_policy()
        result = integrations.status(self.backend, self.job, discover=True)
        self.assertEqual('LOCAL_ONLY_POLICY', result['integrations'][0]['state'])
        self.assertEqual([], self.calls)

    def test_cancelled_status_stops_before_catalogue_io(self):
        self.cancel.set()
        with self.assertRaises(InterruptedError):
            integrations.status(self.backend, self.job, discover=True)
        self.assertEqual([], self.calls)

    def test_unknown_tool_cannot_discover_another_enabled_integration(self):
        with self.assertRaises(PermissionError):
            integrations.call(self.backend, self.job, {**self.action(), 'tool': 'other_read'})
        self.assertEqual([], self.calls)

    def test_allowed_call_scopes_both_discovery_and_actual_exact_dispatch(self):
        result = integrations.call(self.backend, self.job, self.action())
        self.assertEqual('COMPLETED', result['status'])
        self.assertEqual([('docs', 'tools/list'), ('docs', 'tools/call')],
                         [(row[0], row[1]) for row in self.calls])
        self.assertEqual(['owner', 'owner'], [row[3] for row in self.calls])
        self.assertEqual(result, integrations.call(self.backend, self.job, self.action()))
        self.assertEqual(2, len(self.calls))

    def test_selected_tool_does_not_require_other_cold_tools_in_its_integration(self):
        self.policy['workspace_integrations']['context7']['tools'].append('other_read')
        self.admit_policy()
        result = integrations.call(self.backend, self.job, self.action())
        self.assertEqual('COMPLETED', result['status'])
        self.assertEqual([('docs', 'tools/list'), ('docs', 'tools/call')],
                         [(row[0], row[1]) for row in self.calls])
        aggregate = integrations.status(self.backend, self.job)
        self.assertEqual('DISCOVERY_REQUIRED', aggregate['integrations'][0]['state'])
        self.assertEqual(2, len(self.calls))

    def test_permitted_status_is_scoped_and_normal_global_discovery_still_works(self):
        state = integrations.status(self.backend, self.job, discover=True)
        self.assertEqual('READY_FOR_DISPATCH', state['integrations'][0]['state'])
        self.assertEqual(['docs'], [row[0] for row in self.discovery()])
        self.assertEqual('owner', self.discovery()[0][3])
        self.assertIsNone(current_device.get())
        definitions = self.runtime.get_all_mcp_tools()
        self.assertEqual({'docs_query-docs', 'other_read'}, {row['function']['name'] for row in definitions})
        self.assertEqual(['docs', 'other'], [row[0] for row in self.discovery()])
        self.runtime.get_all_mcp_tools()
        self.assertEqual(2, len(self.calls))

    def test_all_three_integrations_keep_their_32_tool_allowance(self):
        configured = {}
        for integration, server in (('context7', 'docs'), ('playwright', 'other')):
            self.advertised[server] = [{'name': 'operation_' + str(i), 'inputSchema': {'type': 'object'}} for i in range(32)]
            configured[integration] = {'enabled': True, 'allow_remote': True,
                                       'tools': [server + '_' + row['name'] for row in self.advertised[server]]}
        github = []
        operations = sorted(integrations.GITHUB_COMPLEMENTARY_READS)
        for index in range(32):
            server, operation = 'security' + str(index // len(operations)), operations[index % len(operations)]
            self.connections[server] = {'command': ['configured-' + server]}
            self.advertised.setdefault(server, []).append({'name': operation, 'inputSchema': {'type': 'object'}})
            github.append(server + '_' + operation)
        configured['github'] = {'enabled': True, 'allow_remote': True, 'tools': github}
        self.policy['workspace_integrations'] = configured
        self.admit_policy()
        state = integrations.status(self.backend, self.job, discover=True)
        self.assertEqual([32, 32, 32], [len(row['tools']) for row in state['integrations']])
        self.assertTrue(all(row['state'] == 'READY_FOR_DISPATCH' for row in state['integrations']))
        self.assertEqual(set(self.connections), {row[0] for row in self.discovery()})
        self.assertEqual(len(self.connections), len(self.discovery()))

    def test_requested_denial_cannot_borrow_other_enabled_integration(self):
        self.policy['workspace_integrations']['playwright'] = {
            'enabled': True, 'allow_remote': True, 'tools': ['other_read']}
        for key in ('enabled', 'allow_remote'):
            with self.subTest(denied=key):
                self.policy['workspace_integrations']['context7'][key] = False
                self.admit_policy()
                with self.assertRaises(PermissionError):
                    integrations.call(self.backend, self.job, self.action())
                self.assertEqual([], self.calls)
                self.policy['workspace_integrations']['context7'][key] = True

    def test_cancelled_call_and_missing_principal_never_start_discovery(self):
        self.cancel.set()
        with self.assertRaises(InterruptedError):
            integrations.call(self.backend, self.job, self.action())
        self.cancel.clear()
        self.job['principal'] = None
        with self.assertRaises(PermissionError):
            integrations.call(self.backend, self.job, self.action())
        self.assertEqual([], self.calls)

    def test_github_overlap_is_rejected_before_cold_discovery(self):
        self.policy['workspace_integrations']['github'] = {
            'enabled': True, 'allow_remote': True, 'tools': ['docs_create_pull_request']}
        self.admit_policy()
        with self.assertRaises(PermissionError):
            integrations.call(self.backend, self.job,
                              self.action(integration='github', tool='docs_create_pull_request', arguments={}))
        self.assertEqual([], self.calls)

    def test_github_complementary_read_can_be_discovered_and_dispatched(self):
        operation = 'list_code_scanning_alerts'
        self.advertised['docs'] = [{'name': operation, 'inputSchema': {'type': 'object'}}]
        self.policy['workspace_integrations']['github'] = {
            'enabled': True, 'allow_remote': True, 'tools': ['docs_' + operation]}
        self.admit_policy()
        result = integrations.call(self.backend, self.job,
                                  self.action(integration='github', tool='docs_' + operation, arguments={}))
        self.assertEqual('COMPLETED', result['status'])
        self.assertEqual([('docs', 'tools/list'), ('docs', 'tools/call')],
                         [(row[0], row[1]) for row in self.calls])
        self.assertEqual(operation, self.calls[-1][2]['name'])

    def test_ambiguous_cold_or_warm_alias_never_selects_a_connection(self):
        self.connections['docs_api'] = {'command': ['different-owner']}
        self.policy['workspace_integrations']['context7']['tools'] = ['docs_api_query']
        self.admit_policy()
        for warm in (False, True):
            with self.subTest(warm_metadata=warm):
                if warm:
                    self.runtime.mcp_tool_metadata['docs_api_query'] = {
                        'server': 'docs', 'tool': 'api_query', 'inputSchema': self.schema}
                with self.assertRaises(PermissionError):
                    integrations.call(self.backend, self.job, self.action(tool='docs_api_query'))
                self.assertEqual([], self.calls)

    def test_disabled_or_missing_connection_never_starts_discovery(self):
        self.connections['docs']['enabled'] = False
        with self.assertRaises(PermissionError):
            integrations.call(self.backend, self.job, self.action())
        del self.connections['docs']
        with self.assertRaises(PermissionError):
            integrations.call(self.backend, self.job, self.action())
        self.assertEqual([], self.calls)

    def test_a_connection_advertising_other_tools_does_not_admit_the_requested_tool(self):
        self.advertised['docs'] = [{'name': 'another-operation', 'inputSchema': self.schema}]
        with self.assertRaises(PermissionError):
            integrations.call(self.backend, self.job, self.action())
        self.assertEqual([('docs', 'tools/list')], [(row[0], row[1]) for row in self.calls])
        self.assertNotIn('workspace_integrations', self.job['data'])

    def test_changed_connection_or_policy_before_producer_entry_sends_nothing(self):
        for change in ('connection', 'policy', 'cancellation'):
            with self.subTest(change=change):
                entered, release = threading.Event(), threading.Event()
                original = self.catalog._load
                def deferred(*args):
                    entered.set()
                    if not release.wait(2):
                        raise AssertionError('The test did not release discovery')
                    return original(*args)
                with patch.object(self.catalog, '_load', side_effect=deferred):
                    done, result = self.background(lambda: integrations.status(self.backend, self.job, discover=True))
                    self.addCleanup(release.set)
                    self.assertTrue(entered.wait(1))
                    if change == 'connection':
                        self.connections['docs']['command'] = ['replacement']
                    elif change == 'policy':
                        self.policy['workspace_integrations']['context7']['allow_remote'] = False
                    else:
                        self.cancel.set()
                    release.set()
                    self.assertTrue(done.wait(2))
                    self.assertIsInstance(result.get('error'), (PermissionError, ValueError, InterruptedError))
                    self.finish_discovery()
                    self.assertEqual([], self.calls)
                self.connections['docs']['command'] = ['configured-docs']
                self.policy['workspace_integrations']['context7']['allow_remote'] = True
                self.cancel.clear()

    def test_cancellation_during_final_config_read_prevents_physical_entry(self):
        original = self.runtime.load_mcp_config
        def load():
            if current_device.get() == 'owner' and threading.current_thread() is not threading.main_thread():
                self.cancel.set()
            return original()
        self.runtime.load_mcp_config = load
        with self.assertRaises(InterruptedError):
            integrations.status(self.backend, self.job, discover=True)
        self.finish_discovery()
        self.assertEqual([], self.calls)

    def block_discovery(self):
        entered, release, stopped = threading.Event(), threading.Event(), threading.Event()
        def rpc(server, method, params, **kwargs):
            result = self.rpc(server, method, params, **kwargs)
            if method == 'tools/list':
                entered.set()
                while not release.wait(.01):
                    if kwargs['cancel_event'].is_set():
                        stopped.set()
                        raise InterruptedError('Discovery transport observed cancellation')
            return result
        self.runtime.mcp_call = rpc
        self.addCleanup(release.set)
        return entered, release, stopped

    def test_cancelled_creator_stops_its_own_new_producer(self):
        entered, release, stopped = self.block_discovery()
        done, result = self.background(lambda: integrations.status(self.backend, self.job, discover=True))
        self.assertTrue(entered.wait(1))
        self.cancel.set()
        self.assertTrue(done.wait(1))
        self.assertIsInstance(result.get('error'), InterruptedError)
        self.assertTrue(stopped.wait(1))
        self.finish_discovery()
        self.assertEqual([('docs', 'tools/list')], [(row[0], row[1]) for row in self.calls])
        self.assertEqual({}, self.catalog.cache)
        self.assertEqual({}, self.catalog.health)

    def test_cancelled_follower_does_not_stop_an_existing_global_producer(self):
        del self.connections['other']
        self.catalog.interactive = .02
        entered, release, stopped = self.block_discovery()
        self.assertEqual([], self.catalog.get())
        self.assertTrue(entered.wait(1))
        with self.catalog.lock:
            event, producer_cancel = next(iter(self.catalog.flights.values()))
        joined = threading.Event()
        original_wait = event.wait
        def wait(timeout=None):
            if threading.current_thread().name == 'workspace-follower':
                joined.set()
            return original_wait(timeout)
        self.catalog.interactive = 1
        with patch.object(event, 'wait', side_effect=wait):
            done, result = self.background(lambda: integrations.status(self.backend, self.job, discover=True),
                                           name='workspace-follower')
            self.assertTrue(joined.wait(1))
            self.cancel.set()
            self.assertTrue(done.wait(1))
            self.assertIsInstance(result.get('error'), InterruptedError)
        self.assertFalse(producer_cancel.is_set())
        self.assertFalse(stopped.is_set())
        release.set()
        self.assertTrue(event.wait(1))
        self.assertEqual(1, len(self.catalog.get()))
        self.assertEqual(1, len(self.calls))

    def test_global_adopter_preserves_a_scope_created_producer(self):
        del self.connections['other']
        self.catalog.interactive = .02
        entered, release, stopped = self.block_discovery()
        integrations.status(self.backend, self.job, discover=True)
        self.assertTrue(entered.wait(1))
        with self.catalog.lock:
            event, producer_cancel = next(iter(self.catalog.flights.values()))
        self.catalog.get()
        self.cancel.set()
        self.assertFalse(producer_cancel.is_set())
        release.set()
        self.assertTrue(event.wait(1))
        self.assertFalse(stopped.is_set())
        self.assertEqual(1, len(self.catalog.get()))
        self.assertEqual(1, len(self.calls))

    def test_other_admitted_job_preserves_a_shared_scoped_producer(self):
        self.catalog.interactive = .02
        second_cancel = threading.Event()
        self.backend.cancellation = lambda job: self.cancel if job['id'] == self.job['id'] else second_cancel
        entered, release, stopped = self.block_discovery()
        integrations.status(self.backend, self.job, discover=True)
        self.assertTrue(entered.wait(1))
        other = copy.deepcopy(self.job)
        other.update(id='another-job', principal='another-owner')
        integrations.status(self.backend, other, discover=True)
        with self.catalog.lock:
            event, producer_cancel = next(iter(self.catalog.flights.values()))
        self.cancel.set()
        self.assertFalse(producer_cancel.is_set())
        release.set()
        self.assertTrue(event.wait(1))
        self.assertFalse(stopped.is_set())
        self.assertEqual('READY_FOR_DISPATCH', integrations.status(self.backend, other)['integrations'][0]['state'])
        self.assertEqual(1, len(self.calls))

    def test_adopter_joining_during_revocation_is_not_cancelled(self):
        for global_owner in (True, False):
            with self.subTest(global_owner=global_owner):
                entered, release = threading.Event(), threading.Event()
                def revoked():
                    entered.set()
                    self.assertTrue(release.wait(2))
                    raise PermissionError('Original policy was revoked')
                producer = _ScopedDiscoveryCancellation(SimpleNamespace(cancel=threading.Event(), check=revoked))
                done, result = self.background(producer.admit)
                self.addCleanup(release.set)
                self.assertTrue(entered.wait(1))
                producer.adopt(None if global_owner else SimpleNamespace(cancel=threading.Event(), check=lambda: None))
                release.set()
                self.assertTrue(done.wait(1))
                self.assertNotIn('error', result)
                self.assertFalse(producer.is_set())

    def test_concurrent_scopes_keep_each_dispatch_compiled_binding(self):
        self.runtime.get_all_mcp_tools()
        published = copy.deepcopy(self.runtime.mcp_tools_cache)
        entered, release = threading.Event(), threading.Event()
        class PublicationLock:
            def __enter__(inner):
                return inner
            def __exit__(inner, *args):
                if threading.current_thread().name == 'first-dispatch':
                    entered.set()
                    if not release.wait(2):
                        raise AssertionError('The test did not release catalogue publication')
        self.runtime.mcp_cache_lock = PublicationLock()
        def dispatch(tool, arguments):
            token = current_device.set('owner')
            try:
                with self.catalog.discovery_scope([tool], cancel_event=threading.Event()):
                    return self.runtime._execute_mcp_tool_single(tool, arguments, call_id=tool)
            finally:
                current_device.reset(token)
        done, result = self.background(lambda: dispatch('docs_query-docs', {'query': 'API v2'}), name='first-dispatch')
        self.addCleanup(release.set)
        self.assertTrue(entered.wait(1))
        second = dispatch('other_read', {})
        release.set()
        self.assertTrue(done.wait(1))
        self.assertNotIn('error', result)
        self.assertEqual(second, result['value'])
        self.assertEqual({'docs', 'other'}, {row[0] for row in self.calls if row[1] == 'tools/call'})
        self.assertEqual(2, len(self.discovery()))
        self.assertEqual(published, self.runtime.mcp_tools_cache)

    def test_connection_change_after_retrieval_cannot_become_new_dispatch_authority(self):
        def changed_after_retrieval(force=False):
            result = self.catalog.get(force)
            self.connections['docs']['command'] = ['unadmitted-replacement']
            return result
        self.runtime.get_all_mcp_tools = changed_after_retrieval
        install_exact_routing(self.runtime)
        with self.assertRaises(PermissionError):
            integrations.call(self.backend, self.job, self.action())
        self.assertEqual([('docs', 'tools/list')], [(row[0], row[1]) for row in self.calls])
        self.assertNotIn('workspace_integrations', self.job['data'])


if __name__ == '__main__':
    unittest.main()
