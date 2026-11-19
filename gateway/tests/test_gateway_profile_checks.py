import sqlite3
import tempfile
import threading
from pathlib import Path
from types import SimpleNamespace
from unittest import TestCase
from unittest.mock import Mock, patch

from v14.profile_checks import ProfileChecks


class ProfileCheckTests(TestCase):
    def setUp(self):
        root = tempfile.TemporaryDirectory()
        self.addCleanup(root.cleanup)
        self.db = sqlite3.connect(Path(root.name) / 'journal.db')
        self.addCleanup(self.db.close)
        self.enabled = True
        store = SimpleNamespace(tx=lambda op: op(self.db), admission_settings=lambda db, owner: {'enabled':self.enabled})
        self.backend = SimpleNamespace(api=Mock(return_value={'object':{'sha':'b'*40}}), stopping=threading.Event(), token=Mock(return_value='read-only'))
        self.api = ProfileChecks(SimpleNamespace(store=store, bindings=SimpleNamespace(boot_id='boot'), backend=self.backend))
        self.args = dict(repository='owner/repo',branch='main',head_sha='b'*40,profile_binding='c'*64)
        self.policy = {'remote_profile_checks':True}
        mock = patch('v14.profile_checks.load_policy', return_value=(self.policy, None, 'sha256:'+'a'*64, [['python','-V']], []))
        mock.start()
        self.addCleanup(mock.stop)

    def test_capability_requires_host_approval_owner_and_current_branch(self):
        self.assertEqual('gateway', self.api.execute('owner', dict(self.args,action='catalog'))['executor'])
        self.enabled = False
        with self.assertRaises(PermissionError):
            self.api.execute('owner', dict(self.args,action='catalog'))
        self.enabled = True
        self.policy['remote_profile_checks'] = False
        with self.assertRaises(PermissionError):
            self.api.execute('owner', dict(self.args,action='catalog'))
        self.policy['remote_profile_checks'] = True
        with self.assertRaisesRegex(ValueError,'advanced'):
            self.api.execute('owner',dict(self.args,action='catalog',head_sha='a'*40))

    def test_idempotent_dispatch_encrypted_output_and_recovery_fences(self):
        args = dict(self.args, action='run', request_id='case1', check_index=0)
        with patch('v14.profile_checks.threading.Thread') as thread:
            first = self.api.execute('user', args)
            self.assertEqual(first, self.api.execute('user',args))
            self.assertEqual(1, thread.call_count)
            with self.assertRaises(ValueError):
                self.api.execute('user', dict(args,changes=[dict(path='new',expected_blob_sha=None,content='change')]))
            with self.assertRaises(ValueError):
                self.api.execute('user', dict(self.args,action='status',request_id='case1',profile_binding='f'*64))
            with self.assertRaises(ValueError):
                self.api.execute('other', dict(self.args,action='status',request_id='case1'))
            self.api.boot = 'after-restart'
            self.assertEqual('INTERRUPTED', self.api.execute('user', args)['state'])
            self.api.boot = 'boot'
            self.backend.api.return_value={'tree':{'sha':'tree'}}
            with patch('v14.profile_checks.prepare_source'), patch('v14.profile_checks.validate',return_value={
                    'status':'PASSED','commands':[dict(exit_code=0, output_sha256='d'*64,output_tail='private-output',
                        report_snapshots={'result.xml': {'sha256': 'e'*64, 'cases': {'AppTest.repair': 'PASSED', 'AppTest.unsupported': 'UNAVAILABLE'}}})]}):
                self.api.run(*thread.call_args.kwargs['args'])
            self.backend.api.return_value={'object':{'sha':'b'*40}}
            result=self.api.execute('user',dict(self.args,action='status',request_id='case1'))
            self.assertEqual('private-output', result['output'])
            self.assertEqual('PASSED', result['state'])
            self.assertEqual([dict(name='AppTest.repair', status='PASSED', report_sha256='e'*64)], result['case_results'])
            self.assertEqual(0, result['changed_path_count'])
            self.assertNotIn('private-output',self.db.execute('SELECT receipt FROM engineering_profile_checks').fetchone()[0])
