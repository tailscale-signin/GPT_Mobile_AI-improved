"""Key-free LAN behavior, safe defaults, restart persistence and admin isolation."""
import ipaddress
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import requests
from fastapi import FastAPI
from fastapi.testclient import TestClient
from desktop.configuration import atomic_json, authority, control_host, environment, load, validate
from desktop.firewall import rule_name
from gateway_security import DeviceStore, current_device
from v14.config import Config
from v14.engineering import principal_authorized
from v14.identity import Admission


class NetworkAdmissionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = DeviceStore(Path(self.temp.name) / 'auth.sqlite3')

    def tearDown(self):
        self.temp.cleanup()

    def client(self, engineering=False, peer='192.168.1.20', host='192.168.1.100', bind='0.0.0.0'):
        config = Config(mode='open_network', host=bind, remote_engineering=engineering,
                        management_token='local-supervisor-secret', allowed_origins=(),
                        allowed_hosts=('pc.example:8090', '127.0.0.1:8090')).validate()
        app = FastAPI()
        @app.api_route('/{path:path}', methods=['GET', 'POST'])
        def echo(path: str):
            return {'principal': current_device.get()}
        app.add_middleware(Admission, config=config, store=self.store)
        return TestClient(app, base_url=f'http://{host}:8090', client=(peer, 12345))

    def test_no_token_needed_on_ip_or_explicit_hostname(self):
        for host in ['192.168.1.100', 'pc.example']:
            client = self.client(host=host)
            for method, path in [('GET', '/v1/models'), ('GET', '/gateway/capabilities'), ('POST', '/v1/chat/completions')]:
                response = client.request(method, path)
                self.assertEqual(200, response.status_code, response.text)
                self.assertEqual('network:shared', response.json()['principal'])
                self.assertEqual('no-store', response.headers['cache-control'])

    def test_headers_and_obsolete_api_key_cannot_change_shared_identity(self):
        headers = {'Tailscale-User-Login': 'admin', 'X-Forwarded-For': '127.0.0.1',
                   'X-Device-ID': 'local:single-user', 'Authorization': 'Bearer old-unused-key'}
        response = self.client().get('/v1/models', headers=headers)
        self.assertEqual('network:shared', response.json()['principal'])
        self.assertEqual(403, self.client().post('/gateway/tools/refresh', headers=headers).status_code)

    def test_unknown_dns_host_and_browser_origin_are_blocked(self):
        for headers in [{'Host': 'attacker.example:8090'}, {'Host': 'user@192.168.1.100:8090'},
                        {'Host': '192.168.1.100:8090/path'}, {'Origin': 'http://pc.example:8090'},
                        {'Origin': 'null'}]:
            self.assertEqual(403, self.client().get('/v1/models', headers=headers).status_code)

    def test_duplicate_admission_headers_are_rejected(self):
        response = self.client().get('/v1/models', headers=[('Authorization', 'one'), ('Authorization', 'two')])
        self.assertEqual(400, response.status_code)

    def test_remote_cannot_access_admin_even_with_management_secret(self):
        client = self.client()
        for path in ['/gateway/admin/maintenance', '/v1/gateway/admin/maintenance']:
            self.assertEqual(401, client.get(path, headers={'X-Gateway-Management': 'local-supervisor-secret'}).status_code)
        for path in ['/gateway/mcp/restart/tools', '/gateway/tools/refresh', '/props']:
            self.assertEqual(403, client.post(path).status_code)
        self.assertEqual(403, client.get('/gateway/health').status_code)

    def test_specific_interface_manager_uses_secret_and_cannot_be_forwarded(self):
        client = self.client(peer='192.168.1.100', bind='192.168.1.100')
        path = '/gateway/admin/maintenance'
        self.assertEqual(401, client.get(path).status_code)
        headers = {'X-Gateway-Management': 'local-supervisor-secret'}
        self.assertEqual(200, client.get(path, headers=headers).status_code)
        self.assertEqual(401, client.get(path, headers=dict(headers, **{'X-Forwarded-For': '127.0.0.1'})).status_code)

    def test_engineering_requires_explicit_enable_and_shared_identity(self):
        path = '/gateway/engineering/capabilities'
        self.assertEqual(403, self.client().get(path).status_code)
        self.assertEqual(200, self.client(engineering=True).get(path).status_code)
        self.assertFalse(principal_authorized(Config(mode='open_network'), 'network:shared', self.store))
        self.assertTrue(principal_authorized(Config(mode='open_network', remote_engineering=True), 'network:shared', self.store))
        self.assertFalse(principal_authorized(Config(mode='open_network', remote_engineering=True), 'local:single-user', self.store))

    def test_network_jobs_are_shared_but_old_private_jobs_stay_private(self):
        self.store.claim('lan-job', 'network:shared')
        self.store.claim('old-job', 'local:single-user')
        for peer in ['192.168.1.20', '192.168.1.21']:
            client = self.client(peer=peer)
            self.assertEqual(200, client.get('/gateway/jobs/lan-job/result').status_code)
            self.assertEqual(404, client.get('/gateway/jobs/old-job/result').status_code)


class NetworkSettingsTests(unittest.TestCase):
    def test_default_and_legacy_settings_remain_local_and_keep_other_values(self):
        self.assertEqual('127.0.0.1', validate({})['bind_host'])
        legacy = {'access': 'tailscale', 'tailnet_host': 'pc.tailnet.ts.net', 'tailnet_user': 'owner',
                  'port': 9010, 'workers': 3, 'active_chats': 9, 'update_mode': 'off', 'engineering': True}
        migrated = validate(legacy)
        self.assertEqual('loopback', migrated['access'])
        self.assertEqual('127.0.0.1', migrated['bind_host'])
        for key, value in legacy.items():
            if key != 'access':
                self.assertEqual(value, migrated[key])
        self.assertEqual(migrated, validate(migrated))

    def test_network_settings_persist_and_environment_is_not_inherited(self):
        for host in ['0.0.0.0', '192.168.1.100', '::', 'fd00::1', '127.0.0.2']:
            with self.subTest(host=host), tempfile.TemporaryDirectory() as temp:
                cfg = validate({'bind_host': host, 'port': 9100, 'network_hostnames': 'MY-PC.local, custom.example'})
                atomic_json(Path(temp) / 'settings.json', cfg)
                self.assertEqual(cfg, load(temp))
                with patch.dict(os.environ, {'GATEWAY_AUTH_MODE': 'trusted_proxy', 'GATEWAY_LEGACY_BEARER': 'true'}):
                    env = environment(temp, load(temp), 'manager')
                self.assertNotIn('GATEWAY_LEGACY_BEARER', env)
                with patch.dict(os.environ, env, clear=True):
                    runtime = Config.from_env()
                self.assertEqual(host, runtime.host)
                self.assertEqual('loopback' if ipaddress.ip_address(host).is_loopback else 'open_network', runtime.mode)
                self.assertIn('my-pc.local:9100', runtime.allowed_hosts)
                self.assertNotIn('GATEWAY_TRUSTED_USERS', env)

    def test_invalid_listener_and_hostnames_fail_before_bind(self):
        for host in ['hostname', '', '224.0.0.1', '255.255.255.255', 'fe80::1%eth0', None, True]:
            with self.subTest(host=host), self.assertRaises(ValueError):
                validate({'bind_host': host})
        for names in ['*.example', 'https://pc', 'pc:8090', 'pc/path', 'a..b']:
            with self.subTest(names=names), self.assertRaises(ValueError):
                validate({'network_hostnames': names})
        with self.assertRaises(ValueError):
            Config(host='0.0.0.0').validate()
        with self.assertRaises(ValueError):
            Config(mode='trusted_proxy', host='0.0.0.0').validate()

    def test_control_destination_and_rule_identity_are_stable(self):
        self.assertEqual('127.0.0.1', control_host(validate({'bind_host': '0.0.0.0'})))
        self.assertEqual('::1', control_host(validate({'bind_host': '::'})))
        self.assertEqual('192.168.1.100', control_host(validate({'bind_host': '192.168.1.100'})))
        self.assertEqual('[::1]:8090', authority('::1', 8090))
        self.assertEqual(rule_name('/one'), rule_name('/one'))
        self.assertNotEqual(rule_name('/one'), rule_name('/two'))


class LiveNetworkTests(unittest.TestCase):
    def test_all_interface_socket_without_token_and_persisted_restart(self):
        self.exercise_listener('127.0.0.1', ['0.0.0.0'])

    def test_actual_lan_and_specific_interface_without_token(self):
        candidates = [entry[4][0] for entry in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)]
        interface = None
        for candidate in candidates:
            if ipaddress.ip_address(candidate).is_loopback:
                continue
            try:
                with socket.socket() as sock:
                    sock.bind((candidate, 0))
                interface = candidate
                break
            except OSError:
                pass
        if interface is None:
            self.skipTest('No bindable LAN IPv4 interface available on this host')
        self.exercise_listener(interface, ['0.0.0.0', interface])

    def exercise_listener(self, interface, bindings):
        http = requests.Session()
        http.trust_env = False
        gateway = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with socket.socket() as sock:
                sock.bind((interface, 0))
                port = sock.getsockname()[1]
            for bind in bindings:
                cfg = validate({'bind_host': bind, 'port': port, 'backend': 'http://127.0.0.1:1',
                                'update_mode': 'off', 'start_at_login': False})
                atomic_json(root / 'settings.json', cfg)
                # Each interface mode is restarted from disk; no credentials exist.
                for attempt in range(2):
                    with self.subTest(bind=bind, attempt=attempt), tempfile.TemporaryFile(mode='w+') as log:
                        env = environment(root, load(root), 'manager-test-secret')
                        child = subprocess.Popen([sys.executable, str(gateway / 'desktop' / 'entry.py'),
                                                  'serve-engine', '--root', str(root)], env=env, stdout=log, stderr=log)
                        url = f'http://{interface}:{port}/v1/gateway/capabilities'
                        try:
                            deadline = time.monotonic() + 25
                            while time.monotonic() < deadline:
                                if child.poll() is not None:
                                    log.seek(0)
                                    self.fail(log.read())
                                try:
                                    response = http.get(url, timeout=2)
                                    if response.status_code == 200:
                                        break
                                except requests.RequestException:
                                    pass
                                time.sleep(.1)
                            else:
                                log.seek(0)
                                self.fail('Network listener startup timed out: ' + log.read())
                            caps = response.json()
                            self.assertFalse(caps['auth']['apiKeyRequired'])
                            self.assertEqual('open_network', caps['auth']['mode'])
                            self.assertEqual('shared-network', caps['identityScope'])
                            self.assertTrue(caps['features']['keyFreeNetworkAccess'])
                            self.assertEqual(403, http.get(url, headers={'Host': 'untrusted.example'}, timeout=2).status_code)
                            control = 'http://' + authority(control_host(cfg), port) + '/gateway/admin/maintenance'
                            self.assertEqual(401, http.get(control, timeout=2).status_code)
                            self.assertEqual(200, http.get(control, headers={'X-Gateway-Management': 'manager-test-secret'}, timeout=2).status_code)
                            self.assertEqual(cfg, load(root))
                        finally:
                            child.terminate()
                            child.wait(timeout=20)
        http.close()


if __name__ == '__main__':
    unittest.main()
