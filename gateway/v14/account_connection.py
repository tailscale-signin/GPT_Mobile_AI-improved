"""Resolve a direct Tailscale connection using the local daemon's account and node identity."""
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import subprocess
import threading
import time

NETWORKS = (ipaddress.ip_network("100.64.0.0/10"), ipaddress.ip_network("fd7a:115c:a1e0::/48"))
PREFIX = 'tailnet:device-'


def overlay(address):
    try:
        ip = ipaddress.ip_address(address)
        return str(ip) if any(ip in net for net in NETWORKS) else None
    except ValueError:
        return None


def listener_accepts_overlay(bind_host, destination):
    ip = overlay(destination)
    return bool(ip and bind_host in {ip, '::' if ':' in ip else '0.0.0.0'})


def local_cli(*args):
    paths = ([Path(os.environ.get('ProgramFiles', r'C:\Program Files')) / 'Tailscale' / 'tailscale.exe']
             if os.name == 'nt' else [Path('/usr/bin/tailscale'), Path('/usr/local/bin/tailscale')])
    binary = next((path for path in paths if path.is_file()), None)
    if not binary:
        return {}
    try:
        run = subprocess.run([str(binary), *args], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                             stderr=subprocess.DEVNULL, timeout=2, check=True,
                             creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        value = json.loads(run.stdout) if len(run.stdout) <= 4_000_000 else None
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError, subprocess.SubprocessError):
        return {}


class AccountConnections:
    def __init__(self, cli=local_cli, clock=time.monotonic):
        self.cli, self.clock = cli, clock
        self.lock = threading.Lock()
        self.until = 0
        self.peers = {}
        self.verified = {}

    def _refresh(self, host):
        at = self.clock()
        if at < self.until and host == getattr(self, 'host', None):
            return
        self.host, self.until, self.peers, self.verified = host, at + 5, {}, {}
        status = self.cli('status', '--json')
        own = status.get('Self') or {}
        if (not isinstance(own, dict) or status.get('BackendState') != 'Running'
                or not isinstance(own.get('TailscaleIPs'), list)
                or not any(listener_accepts_overlay(host, ip) for ip in own['TailscaleIPs'] if isinstance(ip, str))):
            return
        user = own.get('UserID')
        if type(user) is not int or user <= 0 or own.get('Tags') or own.get('Expired') or not own.get('ID'):
            return
        nodes = status.get('Peer')
        if not isinstance(nodes, dict):
            return
        for peer in nodes.values():
            if not isinstance(peer, dict) or peer.get('UserID') != user or peer.get('Tags') or peer.get('ShareeNode') or peer.get('Expired'):
                continue
            node = peer.get('ID')
            if not isinstance(node, str) or not node or len(node) > 160:
                continue
            principal = PREFIX + hashlib.sha256(f"{own['ID']}:{user}:{node}".encode()).hexdigest()[:32]
            if not isinstance(peer.get('TailscaleIPs'), list):
                continue
            for ip in peer['TailscaleIPs']:
                if isinstance(ip, str) and overlay(ip):
                    self.peers[ip] = (principal, user, node)

    def _principal(self, client):
        candidate = self.peers.get(client)
        if not candidate:
            return None
        if client not in self.verified:
            who = self.cli('whois', '--json', client)
            profile, node = who.get('UserProfile'), who.get('Node')
            self.verified[client] = (isinstance(profile, dict) and isinstance(node, dict)
                                     and not node.get('Tags') and not node.get('Expired')
                                     and profile.get('ID') == candidate[1] and node.get('StableID') == candidate[2])
        return candidate[0] if self.verified[client] else None

    def resolve(self, host, client):
        with self.lock:
            self._refresh(host)
            return self._principal(client)

    def authorized(self, host, principal):
        with self.lock:
            self._refresh(host)
            return any(row[0] == principal and self._principal(ip) == principal for ip, row in self.peers.items())


accounts = AccountConnections()
