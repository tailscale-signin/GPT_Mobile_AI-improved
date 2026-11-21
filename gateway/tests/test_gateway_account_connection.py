import asyncio
import sys
import tempfile
from dataclasses import replace
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.account_connection import AccountConnections
from desktop.configuration import account_engineering, atomic_json
from v14.config import Config
from v14.identity import PrincipalResolver, account_authorized


class SameAccountTests(unittest.TestCase):
    def test_authenticated_account_defaults_on_and_honors_saved_host_choice(self):
        with tempfile.TemporaryDirectory() as path:
            self.assertTrue(account_engineering(path))
            preference = Path(path) / 'data' / 'account-engineering.json'
            preference.parent.mkdir()
            atomic_json(preference, {'enabled': False})
            self.assertFalse(account_engineering(path))
            preference.write_text('{', encoding='utf-8')
            self.assertFalse(account_engineering(path))

    def test_same_account_plain_address_edits_shared_store_and_reconciles_original_receipt(self):
        from fastapi import FastAPI
        from fastapi.testclient import TestClient
        from desktop.control_store import ControlStore
        from v14.control_settings import install_connected_settings
        from v14.identity import Admission
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            atomic_json(root / "settings.json", {})
            store = ControlStore(root)
            with store.tx() as db:
                db.execute('CREATE TABLE gateway_installation (key TEXT PRIMARY KEY,value TEXT NOT NULL)')
                db.execute("INSERT INTO gateway_installation VALUES ('gateway_id','integration-pc')")
            store.observe_runtime(True, [])
            host, phone, principal = '100.64.1.1', '100.64.1.2', 'tailnet:device-' + 'a' * 32
            config = Config(mode='open_network', host=host, account_engineering=True, allowed_hosts=(host + ':8090',))
            app = FastAPI()
            install_connected_settings(app, root, config)
            app.add_middleware(Admission, config=config)
            headers = {'X-Gateway-Installation-ID': 'integration-pc', 'X-Gateway-Principal-ID': principal}
            path = '/v1/gateway/engineering/connected/settings'
            with patch('v14.identity.accounts.resolve', return_value=principal), patch('v14.identity.accounts.authorized', return_value=True):
                with TestClient(app, base_url='http://' + host + ':8090', client=(phone, 4231)) as client:
                    initial = client.get(path, headers=headers)
                    self.assertEqual(200, initial.status_code, initial.text)
                    self.assertTrue(initial.json()['effective']['engineering_enabled'])
                    command = dict(installation_id='integration-pc', expected_revision=initial.json()['revision'],
                                   command_id='direct-command-001', changes={'workers': 3, 'active_chats': 10})
                    receipt = client.patch(path, headers=headers, json=command)
                    self.assertEqual(200, receipt.status_code, receipt.text)
                    replay = client.get(path + '/commands/direct-command-001', headers=headers).json()
                    self.assertEqual(receipt.json()['command_id'], replay['command_id'])
                    self.assertEqual(receipt.json()['revision'], replay['revision'])
                    self.assertEqual(3, store.snapshot(local=True)['desired']['workers'])
                    self.assertEqual(409, client.patch(path, headers=dict(headers, **{'X-Gateway-Installation-ID': 'other-pc'}), json=command).status_code)
                    self.assertEqual(403, client.get(path, headers=dict(headers, Origin='http://browser.test')).status_code)
                with TestClient(app, base_url='http://' + host + ':8090', client=('192.168.0.2', 4231)) as stranger:
                    self.assertEqual(403, stranger.get(path, headers=headers).status_code)
            self.assertEqual([], store.devices())

    def test_plain_private_ip_shows_operational_settings_without_creating_a_grant(self):
        from desktop.configuration import shared_settings_overview
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            host, phone = '192.168.1.41', '192.168.1.17'
            atomic_json(root / 'settings.json', {'bind_host': host, 'workers': 3, 'tailnet_user': 'private-owner',
                                                'network_hostnames': 'internal-host', 'backend': 'https://private-backend.test'})
            values = shared_settings_overview(root, host, phone)
            self.assertEqual(3, values['workers'])
            self.assertEqual(8090, values['listen_port'])
            self.assertFalse(values['mcp_configured'])
            self.assertEqual({'listen_port', 'workers', 'active_chats', 'local_validation_workers', 'batch_mode',
                             'mcp_configured', 'public_repository_maps', 'debug_mode', 'start_at_login', 'update_mode',
                             'update_interval_hours', 'shutdown_seconds'}, set(values))
            for destination, peer in [('192.168.1.42', phone), (host, '8.8.8.8'), (host, '127.0.0.1')]:
                self.assertEqual({}, shared_settings_overview(root, destination, peer))
            self.assertFalse((root / 'data' / 'jobs.sqlite3').exists())

    def test_installer_selects_interface_once_and_repair_preserves_explicit_choices(self):
        from desktop.configuration import installer_settings
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            plan = root / 'plan.json'
            atomic_json(plan, {'bind_host': 'auto'})
            with patch('desktop.configuration.setup_interface', return_value='100.64.8.1') as discover:
                self.assertEqual('100.64.8.1', installer_settings(root, plan)['bind_host'])
                atomic_json(root / 'settings.json', {'bind_host': '127.0.0.1', 'workers': 4})
                self.assertEqual(4, installer_settings(root, plan)['workers'])
                self.assertEqual('127.0.0.1', installer_settings(root, plan)['bind_host'])
                discover.assert_called_once()

    def test_repair_can_recover_damaged_pointer_and_refuses_older_installer(self):
        from desktop.entry import installed_before_setup
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            pointer = root / 'current.json'
            pointer.write_bytes(b'{broken')
            self.assertIsNone(installed_before_setup(root, '18.1.0', save_recovery=True))
            backup, = (root / 'data' / 'setup-recovery').iterdir()
            self.assertEqual(pointer.read_bytes(), backup.read_bytes())
            newer = root / 'releases' / '18.2.0'
            newer.mkdir(parents=True)
            atomic_json(newer / 'release.json', {'version': '18.2.0'})
            with self.assertRaises(ValueError):
                installed_before_setup(root, '18.1.0')
            atomic_json(pointer, {'version': '18.0.0'})
            self.assertEqual('18.0.0', installed_before_setup(root, '18.1.0'))

    def test_only_current_same_account_peer_on_the_actual_overlay_interface_gets_engineering(self):
        host, phone = '100.64.1.1', '100.64.1.2'
        peer = {'UserID': 12, 'ID': 'node-phone', 'TailscaleIPs': [phone]}
        view = {'BackendState': 'Running', 'Self': {'ID': 'node-pc', 'UserID': 12, 'TailscaleIPs': [host]}, 'Peer': {'key': peer}}
        who = {'Node': {'StableID': 'node-phone'}, 'UserProfile': {'ID': 12}}
        clock = [5]
        accounts = AccountConnections(lambda cmd, *args: view if cmd == 'status' else who, lambda: clock[0])
        config = Config(mode='open_network', host=host, account_engineering=True)
        resolver = PrincipalResolver(config, None)
        def resolve(address=phone, local=host, headers=None):
            return asyncio.run(resolver.resolve({'path': '/gateway/identity', 'server': (local, 8090),
                                                 'client': (address, 40000)}, headers or {}))
        with patch('v14.identity.accounts', accounts):
            owner = resolve()
            self.assertTrue(owner.engineering_allowed)
            self.assertFalse(owner.local_admin)
            self.assertTrue(account_authorized(config, owner.id))
            resolver.config = replace(config, host='0.0.0.0')
            self.assertEqual(owner.id, resolve().id)
            self.assertTrue(account_authorized(resolver.config, owner.id))
            self.assertFalse(resolve(local='192.168.1.5').engineering_allowed)
            self.assertFalse(resolve(local='100.64.9.9').engineering_allowed)
            resolver.config = config
            for denied in [resolve('192.168.2.1'), resolve(local='127.0.0.1'), resolve(headers={'tailscale-user-login': 'self'}),
                           resolve(headers={'x-forwarded-for': phone}), resolve(headers={'origin': 'https://untrusted.test'})]:
                self.assertFalse(denied.engineering_allowed)
            for changes in [{'Tags': ['tag:service']}, {'Expired': True}, {'UserID': 15}, {'ShareeNode': True}]:
                clock[0] += 6
                peer.update(changes)
                self.assertFalse(resolve().engineering_allowed)
                self.assertFalse(account_authorized(config, owner.id))
                peer.clear()
                peer.update(UserID=12, ID='node-phone', TailscaleIPs=[phone])
            clock[0] += 6
            who['Node']['StableID'] = 'different-node'
            self.assertFalse(resolve().engineering_allowed)
            self.assertFalse(account_authorized(config, owner.id))
            resolver.config = replace(config, account_engineering=False)
            self.assertFalse(resolve().engineering_allowed)
