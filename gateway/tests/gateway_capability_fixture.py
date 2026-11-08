"""Capture the complete capability body through a real local gateway HTTP route.

Only build.sourceCommit, instanceId and installation gatewayId are normalized. The application router,
middleware, response encoder, package validation and startup lifecycle stay real.
"""
import copy
import json
import os
import re
import socket
import subprocess
import sys
import tempfile
import threading
import time
from contextlib import contextmanager
from http.server import ThreadingHTTPServer
from pathlib import Path

import requests

from test_gateway_v14_http import Backend


GATEWAY_ROOT = Path(__file__).resolve().parents[1]
RESOURCE_ROOT = GATEWAY_ROOT / 'contracts/v15'
FULL_FIXTURE = RESOURCE_ROOT / 'gateway-v15-capabilities-full.json'
LEGACY_FIXTURE = RESOURCE_ROOT / 'gateway-v15-capabilities-legacy-mixed.json'


def normalized_capabilities(payload):
    """Retain every response field, replacing only per-fixture identity values."""
    result = copy.deepcopy(payload)
    if not re.fullmatch(r'[a-f0-9]{40}', result['build']['sourceCommit']):
        raise AssertionError('Capability build sourceCommit must be a SHA-1 identity')
    if not re.fullmatch(r'[a-f0-9]{32}', result['instanceId']):
        raise AssertionError('Capability instanceId must be a generated instance identity')
    if not re.fullmatch(r'gw_[a-f0-9]{32}', result['gatewayId']):
        raise AssertionError('Capability gatewayId must be a persisted installation identity')
    result['build']['sourceCommit'] = '0' * 40
    result['instanceId'] = '0' * 32
    result['gatewayId'] = 'gw_' + '0' * 32
    return result


@contextmanager
def running_capability_gateway():
    """Use isolated local state and an empty MCP inventory; never live user config."""
    Backend.model_requests = 0
    with tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)
        mcp_config = temp / 'mcp.json'
        mcp_config.write_text('{"mcpServers":{}}', encoding='utf-8')
        backend = ThreadingHTTPServer(('127.0.0.1', 0), Backend)
        backend_thread = threading.Thread(target=backend.serve_forever, daemon=True)
        backend_thread.start()
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0))
            port = reservation.getsockname()[1]
        base = f'http://127.0.0.1:{port}'
        # The fixture represents this explicit loopback configuration. These are
        # test inputs, not response substitutions or weakened admission rules.
        env = dict(os.environ, GATEWAY_PORT=str(port), GATEWAY_HOST='127.0.0.1',
                   LLAMA_BASE=f'http://127.0.0.1:{backend.server_port}',
                   GATEWAY_AUTH_MODE='loopback', GATEWAY_LEGACY_BEARER='false',
                   GATEWAY_MANAGEMENT_TOKEN='', GATEWAY_TRUSTED_USERS='{}',
                   GATEWAY_MAX_ACTIVE_CHATS='8', GATEWAY_MAX_WORKERS='2',
                   GATEWAY_MAX_BODY_BYTES=str(16 * 1024 * 1024),
                   GATEWAY_BODY_TIMEOUT='30', GATEWAY_SHUTDOWN_GRACE='10',
                   GATEWAY_START_QUIESCED='0', GATEWAY_REMOTE_ENGINEERING='false',
                   GATEWAY_DURABLE_JOBS='true', GATEWAY_LLAMA_ADAPTIVE_REASONING='true',
                   GATEWAY_GITHUB_FULL_ACCESS='false', GATEWAY_GITHUB_ENABLE_ALL_TOOLSETS='true',
                   GATEWAY_JOB_DB=str(temp / 'jobs.sqlite3'), GATEWAY_AUTH_DB=str(temp / 'auth.sqlite3'),
                   GATEWAY_DEBUG_LOG=str(temp / 'debug.jsonl'), GATEWAY_DEBUG_MODE='false',
                   GATEWAY_ACTIVITY_STATE=str(temp / 'activity.json'), MCP_CONFIG_PATH=str(mcp_config))
        for key in ('GATEWAY_ALLOWED_HOSTS', 'GATEWAY_ALLOWED_ORIGINS'):
            env.pop(key, None)
        process = None
        try:
            with (temp / 'server.log').open('w+', encoding='utf-8') as log:
                process = subprocess.Popen([sys.executable, str(GATEWAY_ROOT / 'gateway.py')],
                                           env=env, stdout=log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 15
                while True:
                    if process.poll() is not None:
                        log.seek(0)
                        raise AssertionError('Capability fixture gateway exited during startup:\n' + log.read())
                    try:
                        if requests.get(base + '/gateway/live', timeout=0.3).status_code == 200:
                            break
                    except requests.RequestException:
                        pass
                    if time.monotonic() > deadline:
                        raise AssertionError('Capability fixture gateway startup deadline exceeded')
                    time.sleep(0.05)
                yield base, Backend
        finally:
            if process is not None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
            backend.shutdown()
            backend.server_close()
            backend_thread.join(timeout=2)


def capture_capabilities(base, path='/v1/gateway/capabilities'):
    response = requests.get(base + path, timeout=4)
    if response.status_code != 200:
        raise AssertionError(f'Capability route {path} returned HTTP {response.status_code}')
    if 'application/json' not in response.headers.get('content-type', ''):
        raise AssertionError('Capability route must return JSON')
    return normalized_capabilities(response.json())


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: gateway_capability_fixture.py OUTPUT_JSON')
    with running_capability_gateway() as (base, backend):
        captured = capture_capabilities(base)
        if backend.model_requests:
            raise AssertionError('Capability capture dispatched model inference')
    Path(sys.argv[1]).write_text(json.dumps(captured, indent=2, sort_keys=True) + '\n', encoding='utf-8')
    print(f'Captured complete capability response: {len(captured)} top-level fields, {len(captured["features"])} feature entries, zero model inference requests')
