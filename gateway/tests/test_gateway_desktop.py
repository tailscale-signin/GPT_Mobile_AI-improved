"""Behavioral coverage for update trust, crash recovery, settings and local maintenance."""
import asyncio
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import requests
from desktop.configuration import DEFAULTS, atomic_json, environment, validate
from desktop import releases
from desktop.manager import Supervisor, RESTART, read_pointer, request
from v14 import VERSION
from v14.config import Config
from v14.maintenance import prepare
from v14.service import ServiceState, LoadGuard


def bundle(path, number='15.0.1'):
    path.mkdir(parents=True)
    (path / 'Gateway.exe').write_bytes(b'fixture executable')
    atomic_json(path / 'release.json', dict(version=number, stateSchema=1, bootstrapProtocol=1,
        files={'Gateway.exe': hashlib.sha256(b'fixture executable').hexdigest()}))


def metadata(number='15.0.1', **kwargs):
    name = f'Gateway-{number}-windows-x64.zip'
    asset = dict(name=name, digest='sha256:'+'a'*64, size=123,
        browser_download_url=f'https://github.com/{releases.REPOSITORY}/releases/download/gateway-v{number}/{name}')
    asset.update(kwargs)
    return dict(tag_name='gateway-v'+number, assets=[asset], draft=False, prerelease=False)


class SettingsTests(unittest.TestCase):
    def test_defaults_are_private_and_conservative(self):
        cfg = validate({})
        self.assertEqual('notify', cfg['update_mode'])
        self.assertEqual('loopback', cfg['access'])
        self.assertEqual(2, cfg['workers'])
        self.assertFalse(cfg['debug_mode'])

    def test_invalid_settings_fail_before_persistence(self):
        for update in [{'port': 80}, {'port': True}, {'schema': True}, {'workers': 9}, {'backend':'http://example.com'},
                       {'backend':'https://user:secret@example.com'}, {'backend':'http://127.0.0.1:8090'},
                       {'backend':'http://localhost:8080/v1'}, {'backend':None}, {'update_mode':'nightly'},
                       {'access':'public'}, {'command':'evil'}, {'mcp_config':'relative.json'},
                       {'bind_host':'not-an-ip'}]:
            with self.subTest(update=update), self.assertRaises((ValueError, TypeError)):
                validate(update)

    def test_managed_environment_cannot_inherit_unsafe_gateway_overrides(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {'GATEWAY_HOST':'0.0.0.0', 'GATEWAY_LEGACY_BEARER':'true'}):
            cfg = validate({'access':'tailscale','tailnet_host':'pc.tailnet.ts.net','tailnet_user':'owner@example.com'})
            env = environment(temp, cfg, 'secret')
            self.assertEqual('127.0.0.1', env['GATEWAY_HOST'])
            self.assertNotIn('GATEWAY_LEGACY_BEARER', env)
            self.assertEqual('secret', env['GATEWAY_MANAGEMENT_TOKEN'])
            self.assertEqual('loopback', env['GATEWAY_AUTH_MODE'])
            with patch.dict(os.environ, env, clear=True):
                Config.from_env().validate()

    def test_managed_environment_preserves_explicit_engineering_github_configuration(self):
        from v14.engineering_backend import GitHubEngineeringBackend
        inherited = {'GATEWAY_ENGINEERING_GITHUB_SERVER': 'github-official',
                     'GATEWAY_ENGINEERING_GITHUB_TOKEN': 'dedicated-host-secret',
                     'GATEWAY_AUTH_MODE': 'open_network', 'GATEWAY_REMOTE_ENGINEERING': 'true'}
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, inherited, clear=True):
            cfg = validate({})
            env = environment(temp, cfg, 'management-secret')
            self.assertEqual('github-official', env.get('GATEWAY_ENGINEERING_GITHUB_SERVER'))
            self.assertEqual('dedicated-host-secret', env.get('GATEWAY_ENGINEERING_GITHUB_TOKEN'))
            self.assertEqual('loopback', env['GATEWAY_AUTH_MODE'])
            self.assertEqual('false', env['GATEWAY_REMOTE_ENGINEERING'])
            backend = GitHubEngineeringBackend(SimpleNamespace(load_mcp_config=lambda: {
                'github-official': {'env': {'GITHUB_TOKEN': 'selected-host-secret'}}}))
            with patch.dict(os.environ, env, clear=True):
                self.assertEqual('selected-host-secret', backend.token())
            self.assertNotIn('secret', json.dumps(cfg))
            self.assertNotIn('secret', (Path(temp) / 'data' / 'mcp.json').read_text())

    def test_debug_mode_is_explicit_and_persists_only_inside_managed_log_directory(self):
        with tempfile.TemporaryDirectory() as temp, patch.dict(os.environ, {'GATEWAY_DEBUG_LOG': '/arbitrary/file', 'GATEWAY_DEBUG_MODE': 'true'}):
            self.assertEqual('false', environment(temp, validate({}), 'secret')['GATEWAY_DEBUG_MODE'])
            cfg = validate({'debug_mode': True})
            env = environment(temp, cfg, 'secret')
            self.assertEqual('true', env['GATEWAY_DEBUG_MODE'])
            self.assertEqual((Path(temp) / 'logs' / 'gateway-debug.jsonl').resolve(), Path(env['GATEWAY_DEBUG_LOG']).resolve())
        with self.assertRaises(ValueError):
            validate({'debug_mode': 'yes'})

    def test_atomic_json_replacement_failure_preserves_last_good_file(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'settings.json'
            atomic_json(path, {'old':True})
            with patch('desktop.configuration.os.replace', side_effect=OSError('disk full')):
                with self.assertRaises(OSError):
                    atomic_json(path, {'old':False})
            self.assertEqual({'old':True}, json.loads(path.read_text()))
            self.assertEqual([path], list(Path(temp).iterdir()))


    def test_settings_preflight_rejects_invalid_origin_without_writing_installation(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'install';settings=Path(temp)/'chosen.json'
            settings.write_text('{"backend":"http://remote.example"}')
            result=subprocess.run([sys.executable,str(Path(__file__).resolve().parents[1]/'desktop'/'entry.py'),
                'validate-settings','--root',str(root),'--settings-file',str(settings),'--quiet'],capture_output=True,text=True,timeout=10)
            self.assertNotEqual(0,result.returncode)
            self.assertIn('HTTPS',result.stderr)
            self.assertFalse(root.exists())


class ReleaseTests(unittest.TestCase):
    def test_only_newer_stable_compatible_official_digest_assets(self):
        bad = [metadata('15.0.0'), metadata('14.99.0'), metadata('16.0.0'), metadata(digest=None), metadata(size=0), metadata(browser_download_url='https://evil.example/file')]
        bad += [dict(metadata('15.2.0'), prerelease=True), dict(metadata('15.3.0'), draft=True)]
        self.assertIsNone(releases.choose(bad, '15.0.0'))
        self.assertEqual('15.1.0', releases.choose(bad+[metadata(),metadata('15.1.0')], '15.0.0')['version'])

    def test_rejects_archive_paths_links_case_collisions_and_expansion(self):
        for names in [['../escape'], ['/absolute'], ['a\\b'], ['C:evil'], ['NUL'], ['foo.'], ['a','A'], ['x/../y'], ['a//b']]:
            with self.subTest(names=names), tempfile.TemporaryDirectory() as temp:
                archive = Path(temp)/'update.zip'
                with zipfile.ZipFile(archive, 'w') as zipped:
                    for name in names:
                        info = zipfile.ZipInfo(name)
                        info.filename = name  # Preserve malicious Windows separators in the archive bytes.
                        zipped.writestr(info,b'x')
                with self.assertRaises(ValueError):
                    releases.extract(archive, Path(temp)/'stage')
                self.assertFalse((Path(temp)/'stage').exists())
        with tempfile.TemporaryDirectory() as temp:
            archive = Path(temp)/'update.zip'
            with zipfile.ZipFile(archive,'w') as zipped:
                link = zipfile.ZipInfo('link');link.create_system=3;link.external_attr=0o120777<<16
                zipped.writestr(link,'target')
            with self.assertRaises(ValueError):
                releases.extract(archive, Path(temp)/'stage')
            with zipfile.ZipFile(archive,'w') as zipped:
                zipped.writestr('large',b'01234')
            with patch.object(releases,'MAX_EXPANDED',4), self.assertRaises(ValueError):
                releases.extract(archive,Path(temp)/'stage')

    def test_extraction_and_full_runtime_integrity(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);bundle(root/'original')
            archive=root/'update.zip'
            with zipfile.ZipFile(archive,'w') as zipped:
                for p in (root/'original').iterdir():
                    zipped.write(p,p.name)
            releases.extract(archive,root/'stage')
            self.assertEqual('15.0.1', releases.validate_release(root/'stage')['version'])
            (root/'stage'/'Gateway.exe').write_bytes(b'tampered')
            with self.assertRaises(ValueError):
                releases.validate_release(root/'stage')

    def test_extra_nested_manifest_is_not_exempt_from_hash_inventory(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'release';bundle(root)
            (root/'nested').mkdir();(root/'nested'/'release.json').write_text('{}')
            with self.assertRaises(ValueError):
                releases.validate_release(root)

    def test_incompatible_state_schema_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'release';bundle(root)
            data=json.loads((root/'release.json').read_text());data['stateSchema']=2
            atomic_json(root/'release.json',data)
            with self.assertRaises(ValueError):
                releases.validate_release(root)

    def test_digest_mismatch_deletes_partial_download(self):
        response=Mock(status_code=200,url='https://github.com/valid')
        response.__enter__=Mock(return_value=response);response.__exit__=Mock(return_value=False)
        response.iter_content.return_value=[b'bad']
        session=Mock();session.get.return_value=response
        candidate=releases.choose([metadata(size=3)],'15.0.0')
        with tempfile.TemporaryDirectory() as temp:
            target=Path(temp)/'release.zip'
            with self.assertRaises(ValueError):
                releases.download(candidate,target,session)
            self.assertFalse(target.exists())

    def test_untrusted_redirect_is_rejected_before_contact(self):
        response=Mock(status_code=302,headers={'Location':'http://localhost/private'})
        session=Mock();session.get.return_value=response
        with tempfile.TemporaryDirectory() as temp, self.assertRaises(ValueError):
            releases.download(releases.choose([metadata()],'15.0.0'),Path(temp)/'release.zip',session)
        self.assertEqual(1,session.get.call_count)


class UpdateTransactionTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
        bundle(self.root/'releases'/'15.0.1')
        atomic_json(self.root/'current.json',{'version':'15.0.0'})
        self.manager=Supervisor.__new__(Supervisor);self.manager.root=self.root
        for method in ('stop','backup','spawn','control','publish','discard_failed_start'):
            setattr(self.manager,method,Mock())
        self.manager.log=Mock()
        self.manager.stop.return_value=True
        self.manager.spawn.return_value={'checks':{'backend':'unreachable'}}

    def tearDown(self):
        self.temp.cleanup()

    def test_busy_gateway_never_changes_pointer_or_cancels_work(self):
        self.manager.stop.return_value=False
        self.assertFalse(self.manager.activate('15.0.1'))
        self.assertEqual('15.0.0',read_pointer(self.root)['version'])
        self.manager.backup.assert_not_called()

    def test_activation_is_pending_until_new_process_passes_health(self):
        self.assertTrue(self.manager.activate('15.0.1'))
        self.assertTrue(read_pointer(self.root)['pending'])
        self.assertEqual(0,self.manager.boot())
        self.assertEqual('15.0.1',read_pointer(self.root)['version'])
        self.assertNotIn('pending',read_pointer(self.root))
        self.manager.control.assert_called_with('resume')

    def test_unhealthy_candidate_rolls_back_and_blocks_automatic_retry(self):
        self.manager.activate('15.0.1')
        self.manager.spawn.side_effect=RuntimeError('bad runtime')
        self.assertEqual(RESTART,self.manager.boot())
        self.assertEqual({'version':'15.0.0','failedVersion':'15.0.1'},read_pointer(self.root))
        self.manager.spawn.side_effect=None
        self.manager.boot()
        self.assertEqual('15.0.1',read_pointer(self.root)['failedVersion'])

    def test_interrupted_trial_returns_to_previous_before_start(self):
        atomic_json(self.root/'current.json',dict(version='15.0.1',previous='15.0.0',pending=True,attempted=True))
        self.assertEqual(RESTART,self.manager.boot())
        self.assertEqual('15.0.0',read_pointer(self.root)['version'])
        self.manager.spawn.assert_not_called()

    def test_backup_failure_restarts_previous_without_switch(self):
        self.manager.backup.side_effect=OSError('disk full')
        with self.assertRaises(OSError):
            self.manager.activate('15.0.1')
        self.assertEqual('15.0.0',read_pointer(self.root)['version'])
        self.manager.spawn.assert_called_once_with('15.0.0')
        self.manager.control.assert_called_once_with('resume')

    def test_downgrade_is_refused_before_stop(self):
        with self.assertRaises(ValueError):
            self.manager.activate('14.9.0')
        self.manager.stop.assert_not_called()

    def test_stopping_absent_supervisor_does_not_stop_next_launch(self):
        self.assertTrue(request(self.root,'stop',timeout=1)['accepted'])
        self.assertFalse((self.root/'request.json').exists())


class StopRecoveryTests(unittest.TestCase):
    def manager(self):
        manager=Supervisor.__new__(Supervisor)
        manager.child=Mock();manager.child.poll.return_value=None
        manager.cfg={'shutdown_seconds':10};manager.log=Mock()
        return manager

    def test_lost_prepare_response_restores_admission_without_forcing_stop(self):
        manager=self.manager();manager.control=Mock(side_effect=[requests.Timeout('lost prepare'), {}])
        with self.assertRaises(requests.Timeout):
            manager.stop()
        self.assertEqual(['prepare','resume'],[c.args[0] for c in manager.control.call_args_list])
        manager.child.terminate.assert_not_called()

    def test_lost_shutdown_response_reconciles_exited_child(self):
        manager=self.manager();manager.child.poll.side_effect=[None,0]
        manager.control=Mock(side_effect=[{},requests.ConnectionError('lost shutdown')])
        self.assertTrue(manager.stop())
        self.assertEqual(['prepare','shutdown'],[c.args[0] for c in manager.control.call_args_list])

    def test_shutdown_timeout_restores_admission_and_never_kills(self):
        manager=self.manager();manager.control=Mock(return_value={})
        manager.child.wait.side_effect=subprocess.TimeoutExpired('engine',35)
        with self.assertRaises(subprocess.TimeoutExpired):
            manager.stop()
        manager.control.assert_called_with('resume')
        manager.child.terminate.assert_not_called()


class MaintenanceTests(unittest.TestCase):
    def test_prepare_does_not_cancel_requests_workers_or_persisted_work(self):
        state=ServiceState();runtime=SimpleNamespace(gateway_draining=False)
        engineering=SimpleNamespace(store=Mock())
        for requests_count, workers, pending in [(1,{},0),(0,{'worker':(threading.Event(),None)},0),(0,{},1)]:
            state.active_requests=requests_count;state.workers=workers
            with patch('v14.maintenance.pending_work',return_value=pending):
                self.assertFalse(prepare(runtime,state,engineering))
            self.assertFalse(state.draining)
            for signal,_ in workers.values():
                self.assertFalse(signal.is_set())
        state.workers={};state.active_requests=0
        with patch('v14.maintenance.pending_work',return_value=0):
            self.assertTrue(prepare(runtime,state,engineering))
        self.assertTrue(runtime.gateway_draining)

    def test_stale_prepare_cannot_redrain_after_resume(self):
        state=ServiceState();runtime=SimpleNamespace(gateway_draining=False)
        generation=state.maintenance_generation
        with state.lock:
            state.maintenance_generation += 1
            state.draining = False
            runtime.gateway_draining = False
        with patch('v14.maintenance.pending_work',return_value=0):
            self.assertFalse(prepare(runtime,state,SimpleNamespace(),generation))
        self.assertFalse(state.draining)
        self.assertFalse(runtime.gateway_draining)

    def test_request_body_in_flight_blocks_prepare_and_accounting_recovers(self):
        async def run():
            state=ServiceState();entered=asyncio.Event();release=asyncio.Event()
            async def receive():
                entered.set();await release.wait()
                return {'type':'http.request','body':b'{}','more_body':False}
            async def app(scope,receive,send):
                await receive()
            async def send(message):
                pass
            guard=LoadGuard(app,Config(),state)
            task=asyncio.create_task(guard({'type':'http','method':'POST','path':'/completion'},receive,send))
            await entered.wait()
            self.assertEqual(1,state.snapshot()['activeRequests'])
            with patch('v14.maintenance.pending_work',return_value=0):
                self.assertFalse(prepare(SimpleNamespace(),state,None))
            release.set();await task
            self.assertEqual(0,state.snapshot()['activeRequests'])
        asyncio.run(run())

    def test_real_engine_local_secret_quiesce_resume_shutdown_with_backend_offline(self):
        root=Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as temp:
            temp=Path(temp)
            with socket.socket() as sock:
                sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
            cfg=validate(dict(port=port,backend='http://127.0.0.1:1',access='tailscale',tailnet_host='pc.tailnet.ts.net',tailnet_user='owner@example.com'))
            env=environment(temp,cfg,'test-secret');env['GATEWAY_START_QUIESCED']='1'
            with (temp/'log').open('w+') as log:
                child=subprocess.Popen([sys.executable,str(root/'gateway.py')],env=env,stdout=log,stderr=subprocess.STDOUT)
                http=requests.Session();http.trust_env=False
                url=f'http://127.0.0.1:{port}/gateway/admin/maintenance';headers={'X-Gateway-Management':'test-secret'}
                try:
                    deadline=time.monotonic()+20
                    while time.monotonic()<deadline:
                        if child.poll() is not None:
                            log.seek(0);self.fail(log.read())
                        try:
                            health=http.get(url,headers=headers,timeout=8)
                            if health.status_code==200:
                                break
                        except requests.RequestException:
                            pass
                        time.sleep(.1)
                    else:
                        log.seek(0);self.fail('Engine startup timed out: ' + log.read())
                    self.assertTrue(health.json()['runtimeHealthy'])
                    self.assertFalse(health.json()['ready'])
                    self.assertTrue(health.json()['service']['draining'])
                    self.assertEqual(401,http.get(url,timeout=2).status_code)
                    self.assertEqual(401,http.get(url,headers=dict(headers,**{'Tailscale-User-Login':'owner@example.com'}),timeout=2).status_code)
                    self.assertEqual(401,http.get(url,headers=dict(headers,Origin=f'http://127.0.0.1:{port}'),timeout=2).status_code)
                    self.assertEqual(200,http.post(url,headers=headers,json={'action':'resume'},timeout=2).status_code)
                    self.assertEqual(409,http.post(url,headers=headers,json={'action':'shutdown'},timeout=2).status_code)
                    self.assertEqual(200,http.post(url,headers=headers,json={'action':'prepare'},timeout=2).status_code)
                    self.assertEqual(200,http.post(url,headers=headers,json={'action':'shutdown'},timeout=2).status_code)
                    self.assertEqual(0,child.wait(timeout=15))
                finally:
                    if child.poll() is None:
                        child.terminate();child.wait(timeout=10)


if __name__=='__main__':
    unittest.main()
