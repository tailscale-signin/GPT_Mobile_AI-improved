"""Offline PowerShell bridge contract tests; no installed Gateway or model runs.

Set GATEWAY_TEST_PWSH to a PowerShell executable to enable the functional tests.
The portable stub uses a POSIX shebang; Windows parser checks remain available,
while a native Windows executable acceptance run belongs to Windows CI.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import textwrap
import time
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'benchmarks/campaign_assets/scripts/Invoke-NativeCampaign.ps1'
PWSH = os.environ.get('GATEWAY_TEST_PWSH') or shutil.which('pwsh') or shutil.which('powershell')


@unittest.skipUnless(PWSH, 'PowerShell is unavailable; set GATEWAY_TEST_PWSH for offline helper tests')
class CampaignPowerShellTest(unittest.TestCase):
    def test_script_parses_and_avoids_shell_evaluation_or_model_cli_dispatch(self):
        source = SCRIPT.read_text(encoding='utf-8')
        self.assertNotIn('Invoke-Expression', source)
        self.assertNotIn('StandardInputEncoding =', source)
        command = ('$tokens=$null; $errors=$null; [System.Management.Automation.Language.Parser]::ParseFile('
                   + "'" + str(SCRIPT).replace("'", "''") + "'"
                   + ',[ref]$tokens,[ref]$errors) > $null; if ($errors.Count) { $errors | Out-String | Write-Error; exit 1 }')
        result = subprocess.run([PWSH, '-NoLogo', '-NoProfile', '-NonInteractive', '-Command', command],
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(0, result.returncode, result.stderr)


@unittest.skipUnless(PWSH and os.name != 'nt', 'Portable executable stubs require PowerShell on a POSIX host')
class CampaignPowerShellProcessTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.area = Path(temporary.name)
        self.root = self.area / 'Gateway state with spaces "quoted"'
        self.root.mkdir()
        self.gateway = self.area / 'Gateway synthetic executable with spaces'
        self.gateway.write_text('#!' + sys.executable + '\n' + textwrap.dedent('''
            import json, pathlib, sys, time
            root = pathlib.Path(sys.argv[3])
            raw = sys.stdin.buffer.readline()
            request = json.loads(raw.decode('utf-8'))
            with (root / 'attempts.jsonl').open('a', encoding='utf-8') as log:
                log.write(json.dumps({'argv':sys.argv[1:], 'request':request}) + '\\n')
            mode = request['args'].get('mode', 'normal')
            def emit(event, value, newline=True):
                frame = {'protocol':'gptmobile.desktop.v1','event':event,'value':value}
                sys.stdout.write(json.dumps(frame, ensure_ascii=False) + ('\\n' if newline else ''))
                sys.stdout.flush()
            if mode == 'timeout':
                time.sleep(30)
            elif mode == 'error':
                sys.stderr.write('PRIVATE-SYNTHETIC-STDERR-MUST-NOT-ESCAPE')
                emit('error', {'message':'Synthetic grader observation unavailable'})
                sys.exit(3)
            elif mode == 'partial':
                emit('result', {'receipt':'synthetic'}, newline=False)
            elif mode == 'duplicate':
                emit('result', {'receipt':'first'})
                emit('result', {'receipt':'second'})
            elif mode == 'scalar':
                emit('result', 5)
            elif mode == 'nonzero':
                emit('result', {'receipt':'synthetic'})
                sys.exit(9)
            elif mode == 'unknown-event':
                emit('execute-shell', {'receipt':'not permitted'})
            else:
                sys.stderr.write('PRIVATE-SYNTHETIC-STDERR-MUST-NOT-ESCAPE' * 10000)
                emit('result', {'argv':sys.argv[1:], 'request':request, 'modelRequests':0})
        '''), encoding='utf-8')
        self.gateway.chmod(0o755)
        self.arguments = self.area / 'arguments with spaces.json'
        self.output = self.area / 'receipt with spaces.json'

    def invoke(self, payload, *, action='read', timeout=10, output=True):
        self.arguments.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')
        command = [PWSH, '-NoLogo', '-NoProfile', '-NonInteractive', '-File', str(SCRIPT),
                   '-GatewayExe', str(self.gateway), '-Root', str(self.root), '-Action', action,
                   '-ArgumentsFile', str(self.arguments), '-TimeoutSeconds', str(timeout)]
        if output:
            command += ['-OutputFile', str(self.output)]
        return subprocess.run(command, capture_output=True, text=True, timeout=20)

    def attempts(self):
        path = self.root / 'attempts.jsonl'
        return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()] if path.exists() else []

    def test_literal_utf8_json_and_quoted_paths_round_trip_without_evaluation(self):
        payload = {'id':'campaign_' + 'a' * 32, 'text':'café 日本語 "quote" `backtick` $([Environment]::Exit(9))\nnext',
                   'windowsPath':'C:\\folder with spaces\\trailing\\', 'date':'2026-11-26T17:00:00-03:00',
                   'unknown':None, 'observed':False, 'slotIndex':0, 'items':[1, 1.25, 'literal']}
        result = self.invoke(payload)
        self.assertEqual(0, result.returncode, result.stderr)
        receipt = json.loads(self.output.read_text(encoding='utf-8'))
        self.assertEqual(payload, receipt['request']['args'])
        self.assertEqual(['native-bridge','--root',str(self.root),'--quiet'], receipt['argv'])
        self.assertEqual('benchmark-campaign-read', receipt['request']['action'])
        self.assertEqual(0, receipt['modelRequests'])
        self.assertEqual(1, len(self.attempts()))
        self.assertNotIn('PRIVATE-SYNTHETIC-STDERR', result.stdout + result.stderr + self.output.read_text())
        self.assertFalse(self.output.read_bytes().startswith(b'\xef\xbb\xbf'))

    def test_existing_output_receipt_prevents_process_start(self):
        self.output.write_text('previous receipt', encoding='utf-8')
        result = self.invoke({})
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.attempts())
        self.assertEqual('previous receipt', self.output.read_text())

    def test_remote_error_is_private_bounded_and_never_replayed(self):
        result = self.invoke({'mode':'error'}, action='grade')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Synthetic grader observation unavailable', result.stderr)
        self.assertNotIn('PRIVATE-SYNTHETIC-STDERR', result.stdout + result.stderr)
        self.assertEqual(1, len(self.attempts()))
        self.assertFalse(self.output.exists())

    def test_timeout_preserves_unknown_admission_without_a_second_attempt(self):
        started = time.monotonic()
        result = self.invoke({'mode':'timeout'}, action='grade', timeout=1)
        self.assertLess(time.monotonic() - started, 10)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('uncertain grading submission must not be repeated', result.stderr)
        self.assertEqual(1, len(self.attempts()))
        self.assertFalse(self.output.exists())

    def test_partial_duplicate_scalar_nonzero_and_unknown_frames_never_save_a_receipt(self):
        for mode in ('partial', 'duplicate', 'scalar', 'nonzero', 'unknown-event'):
            with self.subTest(mode=mode):
                before = len(self.attempts())
                result = self.invoke({'mode':mode})
                self.assertNotEqual(0, result.returncode)
                self.assertEqual(before + 1, len(self.attempts()))
                self.assertFalse(self.output.exists())

    def test_invalid_or_excessive_arguments_never_launch_the_runtime(self):
        for value in (None, [], 'literal', {'oversize':'x' * 262145}):
            with self.subTest(kind=type(value).__name__):
                result = self.invoke(value)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(self.output.exists())
                self.assertEqual([], self.attempts())

    def test_product_generation_action_is_rejected_before_runtime_start(self):
        result = self.invoke({}, action='generate')
        self.assertNotEqual(0, result.returncode)
        self.assertEqual([], self.attempts())
        self.assertFalse(self.output.exists())


if __name__ == '__main__':
    unittest.main()
