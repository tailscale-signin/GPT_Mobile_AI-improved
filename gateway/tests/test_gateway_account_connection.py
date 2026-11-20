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
