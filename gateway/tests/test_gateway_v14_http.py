"""Real Uvicorn process + local simulated llama backend, with real journal I/O."""
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import requests


class Backend(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def respond(self, body, content_type='application/json'):
        content = body.encode() if isinstance(body, str) else json.dumps(body).encode()
        self.send_response(200)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def do_GET(self):
        if self.path == '/':
            return self.respond('<html><head></head><body>Local fixture</body></html>', 'text/html')
        if self.path == '/v1/models':
            return self.respond({'object': 'list', 'data': [{'id': 'fixture', 'meta': {'n_ctx_train': 32768}}]})
        if self.path.startswith('/props'):
            return self.respond({'default_generation_settings': {'n_ctx': 32768}, 'total_slots': 1})
        self.respond({'status': 'ok'})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))) or b'{}')
        if self.path == '/tokenize':
            return self.respond({'tokens': [1, 2, 3]})
        if self.path == '/v1/chat/completions':
            if body.get('stream'):
                chunks = [
                    {'choices': [{'index': 0, 'delta': {'role': 'assistant', 'content': 'Live HTTP reply'}, 'finish_reason': 'stop'}]},
                    {'choices': [], 'usage': {'prompt_tokens': 3, 'completion_tokens': 3, 'total_tokens': 6}}]
                return self.respond(''.join('data: ' + json.dumps(c) + '\n\n' for c in chunks) + 'data: [DONE]\n\n', 'text/event-stream')
            return self.respond({'id': 'fixture', 'model': 'fixture', 'choices': [
                {'index': 0, 'message': {'role': 'assistant', 'content': 'Live HTTP reply'}, 'finish_reason': 'stop'}],
                'usage': {'prompt_tokens': 3, 'completion_tokens': 3, 'total_tokens': 6}})
        self.respond({})


class LiveHTTPTests(unittest.TestCase):
    def test_launcher_browser_admission_stream_and_saved_result_over_http(self):
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as temp:
            temp = Path(temp)
            config = temp / 'mcp.json'
            config.write_text('{"mcpServers":{}}')
            backend = ThreadingHTTPServer(('127.0.0.1', 0), Backend)
            backend_thread = threading.Thread(target=backend.serve_forever, daemon=True)
            backend_thread.start()
            with socket.socket() as reservation:
                reservation.bind(('127.0.0.1', 0))
                port = reservation.getsockname()[1]
            base = f'http://127.0.0.1:{port}'
            env = dict(os.environ, GATEWAY_PORT=str(port), LLAMA_BASE=f'http://127.0.0.1:{backend.server_port}',
                       GATEWAY_AUTH_MODE='loopback', GATEWAY_JOB_DB=str(temp/'jobs.sqlite3'),
                       GATEWAY_AUTH_DB=str(temp/'auth.sqlite3'), MCP_CONFIG_PATH=str(config))
            for name in ('GATEWAY_ALLOWED_HOSTS', 'GATEWAY_ALLOWED_ORIGINS', 'GATEWAY_TRUSTED_USERS'):
                env.pop(name, None)
            with (temp/'server.log').open('w+') as log:
                process = subprocess.Popen([sys.executable, str(root/'gateway.py')], env=env,
                                           stdout=log, stderr=subprocess.STDOUT)
                try:
                    deadline = time.monotonic() + 15
                    while True:
                        if process.poll() is not None:
                            log.seek(0)
                            self.fail(log.read())
                        try:
                            if requests.get(base + '/gateway/live', timeout=0.3).status_code == 200:
                                break
                        except requests.RequestException:
                            pass
                        if time.monotonic() > deadline:
                            self.fail('Gateway startup deadline exceeded')
                        time.sleep(0.05)
                    self.assertEqual(requests.get(base+'/gateway/ready', timeout=4).status_code, 200)
                    self.assertIn('/gateway/browser-bootstrap.js', requests.get(base+'/', timeout=4).text)
                    headers = {'Origin': base}
                    self.assertEqual(requests.post(base+'/v1/chat/completions', json={}, headers=headers, timeout=4).status_code, 403)
                    headers['X-Gateway-CSRF'] = requests.get(base+'/gateway/session', headers=headers, timeout=4).json()['csrfToken']
                    result = requests.post(base+'/v1/chat/completions', headers=headers, timeout=10, json={
                        'model': 'fixture', 'stream': True, 'messages': [{'role': 'user', 'content': 'Hi'}], 'max_tokens': 128})
                    self.assertEqual(result.status_code, 200, result.text)
                    self.assertIn('Live HTTP reply', result.text)
                    self.assertEqual(result.text.count('data: [DONE]'), 1)
                    saved = requests.get(base+'/gateway/jobs/'+result.headers['X-Gateway-Job-ID']+'/result', timeout=4)
                    self.assertEqual(saved.status_code, 200)
                    self.assertIn('Live HTTP reply', saved.text)
                finally:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill(); process.wait(timeout=5)
                    backend.shutdown()
                    backend.server_close()
                    backend_thread.join(timeout=2)


if __name__ == '__main__':
    unittest.main()
