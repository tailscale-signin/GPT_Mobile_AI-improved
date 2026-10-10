"""Tiny subprocess fixture; never contacts a network or executes real tools."""
import json
import sys
import time
from pathlib import Path

mode = sys.argv[1]


def send(message):
    print(json.dumps(message), flush=True)


for line in sys.stdin:
    request = json.loads(line)
    method = request.get('method')
    if method == 'initialize':
        send({'jsonrpc': '2.0', 'id': request['id'], 'result': {
            'protocolVersion': '2026-07-28' if mode == 'modern' else '2024-11-05',
            'capabilities': {'tools': {}}, 'serverInfo': {'name': 'fixture', 'version': '1'}}})
    elif method == 'tools/list':
        send({'jsonrpc': '2.0', 'id': request['id'], 'result': {'tools': []}})
    elif method == 'tools/call':
        if mode == 'crash':
            with Path(sys.argv[2]).open('a') as count:
                count.write('executed\n')
            sys.exit(3)
        if mode == 'oversize':
            print('x' * 4096, flush=True)
            continue
        if mode == 'flood':
            for _ in range(2000):
                send({'jsonrpc': '2.0', 'id': -1, 'result': {'padding': 'x' * 4096}})
            continue
        if mode in {'stale', 'progress'}:
            until = time.monotonic() + 3
            while time.monotonic() < until:
                message = {'jsonrpc': '2.0', 'id': -1, 'result': {}}
                if mode == 'progress':
                    message = {'jsonrpc': '2.0', 'method': 'notifications/progress', 'params': {
                        'progressToken': request['params']['_meta']['progressToken'], 'progress': 1}}
                send(message)
                time.sleep(0.01)
        elif mode == 'silent':
            time.sleep(5)
        elif mode == 'notification':
            send({'jsonrpc': '2.0', 'id': None, 'method': 'notifications/message', 'params': {'data': 'ready'}})
            send({'jsonrpc': '2.0', 'id': 'server-ping', 'method': 'ping'})
            response = json.loads(sys.stdin.readline())
            assert response == {'jsonrpc': '2.0', 'id': 'server-ping', 'result': {}}
            send({'jsonrpc': '2.0', 'id': request['id'], 'result': {'content': [{'type': 'text', 'text': 'OK'}]}})
        elif mode == 'rpc_error':
            send({'jsonrpc': '2.0', 'id': request['id'], 'error': {'code': -32602, 'message': 'Invalid input'}})
        else:
            send({'jsonrpc': '2.0', 'id': request['id'], 'result': {'content': [{'type': 'text', 'text': 'OK'}]}})
