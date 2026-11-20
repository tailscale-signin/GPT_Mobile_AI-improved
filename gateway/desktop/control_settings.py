"""One settings owner shared by native local IPC and the paired phone listener."""
import ipaddress
import json
from pathlib import Path
import socket
import threading
import time
from urllib.parse import urlsplit

import requests

from desktop.configuration import atomic_json, authority, control_host, load, save_public_repository_maps, settings_write_lock
from desktop.control_store import ControlStore, ControlError, FIELDS, RUNTIME_FIELDS


class Controller:
    def __init__(self, supervisor):
        self.supervisor = supervisor
        self.store = ControlStore(supervisor.root, listener=lambda: (
            supervisor.cfg['bind_host'], supervisor.cfg['port'],
            supervisor.cfg['network_hostnames'].split(',') + [socket.gethostname()]))
        self.lock = threading.RLock()
        self.server = None
        self.endpoint = self.fingerprint = ''
        self.allowed_hosts = set()
        self.secret = None
        self.last_prerequisites = []
        self.restart_requested = False

    def runtime(self, values=None):
        target = 'http://' + authority(control_host(self.supervisor.cfg), self.supervisor.cfg['port']) + '/gateway/admin/settings'
        with requests.Session() as client:
            client.trust_env = False
            response = client.request('POST' if values is not None else 'GET', target, json=values,
                                      headers={'X-Gateway-Management': self.supervisor.token}, timeout=(3, 15), allow_redirects=False)
            if response.status_code not in {200, 409}:
                response.raise_for_status()
            return response.json()

    def start_saved(self):
        path = self.supervisor.root / 'data' / 'control-listener.json'
        if path.exists():
            value = json.loads(path.read_text(encoding='utf-8'))
            self.enable(value, persist=False)

    def enable(self, payload, persist=True):
        if not isinstance(payload, dict) or set(payload) != {'host', 'port'}:
            raise ControlError('invalid_control_listener')
        host, port = payload['host'], payload['port']
        try:
            address = ipaddress.ip_address(host)
        except (ValueError, TypeError):
            raise ControlError('invalid_control_listener') from None
        if address.is_unspecified or address.is_multicast or type(port) is not int or not 1024 <= port <= 65535 or port == self.supervisor.cfg['port']:
            raise ControlError('invalid_control_listener')
        if self.server:
            if self.endpoint == 'https://' + authority(host, port):
                return self.description()
            raise ControlError('control_listener_restart_required', 409, 'Restart Gateway before changing the paired listener')
        from desktop.control_security import server_secret, tls_identity
        from desktop.control_server import ControlServer
        # Bind before creating credentials or advertising support. Local TLS is
        # not automatically opened to the LAN or added to the firewall.
        with socket.socket(socket.AF_INET6 if address.version == 6 else socket.AF_INET) as check:
            check.bind((host, port))
        secret = server_secret(self.supervisor.root)
        tls = tls_identity(self.supervisor.root, host, secret)
        self.secret, self.fingerprint = secret, tls['fingerprint']
        self.endpoint = 'https://' + authority(host, port)
        self.allowed_hosts = {authority(host, port)}
        self.server = ControlServer(self, host, port, tls)
        self.server.start()
        if persist:
            atomic_json(self.supervisor.root / 'data' / 'control-listener.json', payload)
        return self.description()

    def description(self):
        ready = self.server is not None and self.server.server.started and self.server.thread.is_alive()
        return {'supported': True, 'enabled': ready, 'endpoint': self.endpoint if ready else None,
                'fingerprint': self.fingerprint if ready else None, 'protocol': 'controlSettings.v1'}

    def close(self):
        if self.server:
            self.server.close()

    def reconcile(self):
        with self.lock:
            self.restart_requested = False
            snapshot = self.store.snapshot(local=True)
            desired = dict(snapshot['desired'])
            desired.pop('engineering_enabled')
            pending = []
            try:
                status = self.runtime()
                self.last_prerequisites = status.get('engineering_prerequisites', [])
                self.store.observe_runtime(status.get('engineering_ready') is True, self.last_prerequisites)
                runtime_values = {key: desired[key] for key in RUNTIME_FIELDS}
                needs_runtime = any(runtime_values[key] != status['effective'].get(key) for key in RUNTIME_FIELDS)
                # Batch changes also wait for already accepted work. Its saved
                # binding and policy are never rewritten by a new preference.
                batch_changed = any(desired[key] != snapshot['effective'][key] for key in ('batch_mode', 'local_validation_workers'))
                profile_changed = desired['validation_profile'] != status['effective'].get('validation_profile', 'disabled')
                if needs_runtime or batch_changed or profile_changed:
                    status = self.runtime(runtime_values)
                blockers = status.get('blocking_jobs', [])
                applied = status.get('status', 'applied') == 'applied'
                effective = dict(snapshot['effective'])
                effective.pop('engineering_enabled')
                effective.update(status['effective'])
                effective['validation_profile'] = status['effective'].get('validation_profile', 'disabled')
                for key in RUNTIME_FIELDS:
                    if effective[key] != desired[key]:
                        pending.append({'field': key, 'reason': status.get('reason', 'waiting_for_jobs'), 'blocking_jobs': blockers})
                if applied:
                    from v14.engineering_batch_policy import load_policy, save_policy
                    batch_path = self.supervisor.root / 'data' / 'execution-policy.json'
                    batch = load_policy(batch_path)
                    batch.update(default_mode=desired['batch_mode'], full_enabled=desired['batch_mode'] == 'full',
                                 local_validation_workers=desired['local_validation_workers'])
                    save_policy(batch_path, batch)
                    for key in ('batch_mode', 'local_validation_workers'):
                        effective[key] = desired[key]
                else:
                    for key in ('batch_mode', 'local_validation_workers'):
                        if desired[key] != effective[key]:
                            pending.append({'field': key, 'reason': 'waiting_for_jobs', 'blocking_jobs': blockers})
                if desired['validation_profile'] != effective['validation_profile']:
                    reason = 'waiting_for_jobs'
                    if applied:
                        from desktop.phone_policies import activate
                        try:
                            activate(self.store, desired['validation_profile'])
                            self.restart_requested = True
                            reason = 'awaiting_restart'
                        except ControlError as error:
                            reason = error.code
                    pending.append({'field': 'validation_profile', 'reason': reason, 'blocking_jobs': blockers})
                from desktop.manager import startup_registration
                try:
                    if desired['start_at_login'] != effective['start_at_login']:
                        startup_registration(self.supervisor.root, desired['start_at_login'])
                    effective['start_at_login'] = desired['start_at_login']
                except OSError:
                    pending.append({'field': 'start_at_login', 'reason': 'host_action_required', 'blocking_jobs': []})
                for key in ('update_mode', 'update_interval_hours'):
                    effective[key] = desired[key]
                # Persist only observed effective values to the rollback-readable
                # legacy files. The revisioned desired record remains authoritative.
                shared_cfg = ('workers', 'active_chats', 'backend', 'start_at_login', 'update_mode', 'update_interval_hours', 'debug_mode')
                with settings_write_lock(self.supervisor.root):
                    cfg = load(self.supervisor.root)
                    for key in shared_cfg:
                        cfg[key] = effective[key]
                    atomic_json(self.supervisor.root / 'settings.json', cfg, retry_windows_sharing=True)
                # A saved host port/address is a restart preference. Continue
                # addressing the currently running engine until that restart.
                self.supervisor.cfg.update({key: effective[key] for key in shared_cfg})
                save_public_repository_maps(self.supervisor.root, effective['public_repository_maps'])
                self.store.apply_observed(snapshot['revision'], effective, pending)
            except (requests.RequestException, OSError, ValueError, KeyError):
                effective = dict(snapshot['effective'])
                effective.pop('engineering_enabled')
                pending = [{'field': key, 'reason': 'runtime_unavailable', 'blocking_jobs': []}
                           for key, value in desired.items() if value != effective[key]]
                self.store.apply_observed(snapshot['revision'], effective, pending)

    def phone_snapshot(self, principal):
        snapshot = self.store.snapshot(principal)
        status = getattr(self.supervisor, 'status', {})
        if status.get('version'):
            snapshot['updates'] = {'installed': status['version'], 'state': status.get('updateState', 'unchecked'),
                                   'available': status.get('availableVersion')}
        return snapshot

    def phone_update_action(self, payload):
        principal = payload.get('principal')
        grant = self.store.grant(principal) if isinstance(principal, str) else None
        cfg = self.supervisor.cfg
        from v14.account_connection import PREFIX, accounts, overlay
        from desktop.configuration import account_engineering
        direct = ((principal == 'local:single-user' and cfg['access'] == 'loopback') or
                  (isinstance(principal, str) and principal.startswith(PREFIX) and overlay(cfg['bind_host'])
                   and account_engineering(self.supervisor.root) and accounts.authorized(cfg['bind_host'], principal)))
        if not direct and (not grant or grant['role'] != 'owner' or 'update_mode' not in json.loads(grant['fields'])):
            raise ControlError('update_permission_required', 403)
        if set(payload) == {'principal', 'action'} and payload['action'] == 'check':
            return 'check'
        if (set(payload) != {'principal', 'action', 'target'} or payload['action'] != 'install' or
                not isinstance(payload['target'], str) or self.supervisor.status.get('availableVersion') != payload['target'] or
                self.supervisor.status.get('updateState') != 'available'):
            raise ControlError('update_target_changed', 409)
        return 'install'

    def patch(self, principal, payload):
        with self.lock:
            return self.store.patch(principal, payload)

    def connection_port(self):
        # A private invitation can advertise inference only on its own interface.
        cfg = self.supervisor.cfg
        host = urlsplit(self.endpoint).hostname
        if host and cfg['access'] == 'network' and cfg['bind_host'] in {host, '0.0.0.0', '::'}:
            return cfg['port']
        return None

    def local(self, action, payload):
        payload = dict(payload or {})
        owner = payload.pop('owner_principal', 'local:single-user')
        if owner != 'local:single-user' and not self.store.grant(owner):
            raise ControlError('owner_not_found', 404)
        with self.lock:
            if action == 'control-settings-read':
                value = self.store.snapshot(owner, local=True)
                value['listener'] = self.description()
                from desktop.configuration import account_engineering
                from v14.account_connection import overlay
                cfg = self.supervisor.cfg
                value['gateway_host'] = cfg['bind_host']
                value['gateway_port'] = cfg['port']
                if overlay(cfg['bind_host']) and account_engineering(self.supervisor.root):
                    value['account_connection_url'] = 'http://' + authority(cfg['bind_host'], cfg['port']) + '/v1'

                from desktop.validation_policy import load_policy_snapshot
                policy = load_policy_snapshot(self.supervisor.root)
                value['connection_repositories'] = sorted(policy['repositories']) if policy['state'] == 'READY' else []
                value['engineering_prerequisites'] = self.last_prerequisites
                return value
            if action == 'control-settings-patch':
                return self.store.patch(owner, payload, local=True)
            if action == 'control-settings-command':
                return self.store.receipt(owner, payload['command_id'], local=True)
            if action == 'control-enable':
                return self.enable(payload)
            if action == 'control-connect-address':
                from desktop.control_server import pairing_link
                from desktop.validation_policy import load_policy_snapshot
                cfg = self.supervisor.cfg
                host = payload.get('host', cfg['bind_host'])
                ip = ipaddress.ip_address(host)
                if (set(payload) - {'host'} or cfg['access'] != 'network' or
                        cfg['bind_host'] not in {host, '0.0.0.0', '::'} or ip.is_loopback or ip.is_unspecified or ip.is_multicast):
                    raise ControlError('choose_gateway_network_interface', 409)
                if not self.server:
                    self.enable({'host': host, 'port': 8091 if cfg['port'] != 8091 else 8092})
                deadline = time.monotonic() + 3
                while not self.description()['enabled'] and self.server.thread.is_alive() and time.monotonic() < deadline:
                    time.sleep(.03)
                if not self.description()['enabled'] or urlsplit(self.endpoint).hostname != host or not self.connection_port():
                    raise ControlError('control_listener_unavailable', 409)
                policy = load_policy_snapshot(self.supervisor.root)
                repos = sorted(policy['repositories']) if policy['state'] == 'READY' else []
                issued = self.store.pairing_create({'role': 'owner', 'fields': sorted(FIELDS), 'repositories': repos}, address=True)
                issued['gateway_port'] = cfg['port']
                fragment = pairing_link(issued, self.endpoint, self.fingerprint).split('?', 1)[1]
                return {'address': 'http://' + authority(host, cfg['port']) + '/v1#' + fragment}
            if action in {'control-pairing-create', 'control-connect-create'}:
                if not self.description()['enabled']:
                    raise ControlError('control_listener_required', 412)
                from desktop.control_server import pairing_link
                value = self.store.pairing_create(payload, connect=action == 'control-connect-create')
                value.update(endpoint=self.endpoint, fingerprint=self.fingerprint)
                if action == 'control-connect-create' and (port := self.connection_port()):
                    value['gateway_port'] = port
                value['link'] = pairing_link(value, self.endpoint, self.fingerprint)
                return value
            if action == 'control-pairing-list':
                return {'items': self.store.pairings()}
            if action == 'control-pairing-confirm':
                return self.store.pairing_confirm(payload['pairing_id'], payload['approved'])
            if action == 'control-devices':
                return {'items': self.store.devices()}
            if action == 'control-device-revoke':
                return self.store.revoke(payload['device_id'])
            if action == 'control-validation-approve':
                if payload:
                    raise ControlError('invalid_validation_approval')
                from desktop.phone_policies import approve_current
                return approve_current(self.store)
            if action == 'control-host-requests':
                if payload:
                    raise ControlError('invalid_host_request')
                return self.store.host_requests()
            if action == 'control-host-request-review':
                return self.store.review_host_request(payload)
            if action == 'control-backend-approve':
                if set(payload) != {'backend'}:
                    raise ControlError('invalid_backend')
                from desktop.control_store import validate_changes
                from v14.control_settings import probe_backend
                snapshot = self.store.snapshot(local=True)
                validate_changes(payload, snapshot['desired'], local=True, listener=self.store.listener())
                try:
                    probe_backend(payload['backend'], '')
                except (requests.RequestException, ValueError):
                    raise ControlError('backend_probe_failed', 412) from None
                return self.store.approve_backend(payload['backend'])
            raise ControlError('unknown_control_action')
