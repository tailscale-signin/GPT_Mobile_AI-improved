import json
import subprocess
import sys
import unittest
from unittest.mock import patch

from v14 import workspace_lsp


class PushDiagnosticTests(unittest.TestCase):
    def observe(self, messages, timeout):
        # The server publishes asynchronously after the discovery request has finished.
        body = b''
        for uri, diagnostics in messages:
            chunk = json.dumps({'method': 'textDocument/publishDiagnostics',
                                'params': {'uri': uri, 'diagnostics': diagnostics}}).encode()
            body += b'Content-Length: ' + str(len(chunk)).encode() + b'\r\n\r\n' + chunk
        producer = 'import os,sys,time; time.sleep(0.06); os.write(sys.stdout.fileno(),bytes.fromhex(sys.argv[1])); time.sleep(10)'
        with subprocess.Popen([sys.executable, '-c', producer, body.hex()], stdin=subprocess.PIPE, stdout=subprocess.PIPE) as proc:
            try:
                with patch.object(workspace_lsp, 'PUSH_DIAGNOSTICS_TIMEOUT', timeout):
                    return workspace_lsp.Client(proc).pushed_diagnostics('file:///use.ts')
            finally:
                proc.kill()

    def test_delayed_diagnostics_are_scoped_to_the_open_file(self):
        finding = {'code': 2322, 'message': 'Type mismatch'}
        self.assertEqual([finding], self.observe([('file:///other.ts', []), ('file:///use.ts', [finding])], 4))

    def test_clean_file_and_no_server_result_are_distinct(self):
        self.assertEqual([], self.observe([('file:///use.ts', [])], 4))
        self.assertIsNone(self.observe([], 0.2))
