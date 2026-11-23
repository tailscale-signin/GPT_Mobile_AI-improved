import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from v14 import workspace_lsp
from v14.workspace_process import Processes


SERVER = r'''
import json, os, sys, time
docs = {}
while True:
    header = sys.stdin.buffer.readline()
    if not header:
        break
    size = int(header.split(b':')[1])
    sys.stdin.buffer.readline()
    msg = json.loads(sys.stdin.buffer.read(size))
    method, params = msg.get('method'), msg.get('params', {})
    if method == 'textDocument/didOpen':
        doc = params['textDocument']; docs[doc['uri']] = (doc['text'], doc['version'])
    if method == 'textDocument/didChange':
        doc = params['textDocument']; docs[doc['uri']] = (params['contentChanges'][0]['text'], doc['version'])
    if 'id' not in msg:
        continue
    if method == 'initialize':
        result = {'capabilities': {'definitionProvider': True, 'referencesProvider': True}}
    else:
        text, version = docs[params['textDocument']['uri']]
        if 'BLOCK_SERVER' in text:
            time.sleep(10)
        result = {'text': text, 'version': version, 'pid': os.getpid()}
    raw = json.dumps({'jsonrpc': '2.0', 'id': msg['id'], 'result': result}).encode()
    sys.stdout.buffer.write(b'Content-Length: ' + str(len(raw)).encode() + b'\r\n\r\n' + raw)
    sys.stdout.buffer.flush()
'''


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

    def test_warm_document_ignores_stale_and_unversioned_diagnostics_after_change(self):
        import io
        from types import SimpleNamespace
        chunks = []
        for version in (1, None, 2):
            params = {'uri': 'file:///use.ts', 'diagnostics': [{'message': str(version)}]}
            if version is not None:
                params['version'] = version
            raw = json.dumps({'method': 'textDocument/publishDiagnostics', 'params': params}).encode()
            chunks.append(b'Content-Length: ' + str(len(raw)).encode() + b'\r\n\r\n' + raw)
        client = workspace_lsp.Client(SimpleNamespace(stdin=io.BytesIO(), stdout=io.BytesIO(b''.join(chunks))))
        client.document_versions['file:///use.ts'] = 2
        for _ in range(2):
            client.receive()
            self.assertNotIn('file:///use.ts', client.diagnostics)
        client.receive()
        self.assertEqual([{'message': '2'}], client.diagnostics['file:///use.ts'])


@unittest.skipUnless(os.name == 'posix', 'Warm workspace servers run inside the isolated Linux container and require POSIX process groups')
class WarmSessionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.folder = Path(self.temp.name)
        self.root = self.folder / 'checkout'
        self.root.mkdir()
        (self.folder / 'home').mkdir()
        self.control = self.folder / 'control'
        self.control.mkdir()
        (self.root / 'use.py').write_text('value = 1\n')
        self.pool = workspace_lsp.SessionPool(self.root, 'scope', 'a' * 40, time.time() + 30,
                    servers={'python': [sys.executable, '-u', '-c', SERVER]}, query_seconds=1)
        self.action = {'language': 'python', 'operation': 'definition', 'path': 'use.py',
                       'line': 1, 'column': 1, 'workspace_identity': 'scope', 'source_sha': 'a' * 40}

    def tearDown(self):
        self.pool.close()
        self.temp.cleanup()

    def test_repeated_queries_reuse_server_and_send_changed_document_version(self):
        first = self.pool.query(self.action)
        second = self.pool.query(self.action)
        self.assertFalse(first['cache_hit'])
        self.assertTrue(second['cache_hit'])
        self.assertEqual(first['result']['pid'], second['result']['pid'])
        (self.root / 'use.py').write_text('value = 2\n')
        changed = self.pool.query(self.action)
        self.assertEqual('value = 2\n', changed['result']['text'])
        self.assertEqual(2, changed['result']['version'])
        self.assertNotEqual(first['input_sha256'], changed['input_sha256'])
        self.assertFalse(changed['runtime_verified'])

    def test_changed_scope_or_revision_and_precancellation_never_start_server(self):
        for field, value in (('workspace_identity', 'other'), ('source_sha', 'b' * 40)):
            with self.assertRaises(PermissionError):
                self.pool.query(dict(self.action, **{field: value}))
        cancel = threading.Event()
        cancel.set()
        with self.assertRaises(InterruptedError):
            self.pool.query(self.action, cancel)
        self.assertEqual({}, self.pool.rows)

    def test_blocked_server_is_cancelled_and_cannot_remain_warm(self):
        (self.root / 'use.py').write_text('BLOCK_SERVER\n')
        cancel = threading.Event()
        timer = threading.Timer(.15, cancel.set)
        timer.start()
        started = time.monotonic()
        try:
            with self.assertRaises(InterruptedError):
                self.pool.query(self.action, cancel)
        finally:
            timer.cancel()
            timer.join()
        self.assertLess(time.monotonic() - started, 2)
        self.assertEqual({}, self.pool.rows)

    def test_idle_expiry_and_close_reap_children(self):
        self.pool.idle_seconds = .1
        first = self.pool.query(self.action)
        until = time.monotonic() + 2
        while self.pool.rows and time.monotonic() < until:
            time.sleep(.02)
        self.assertEqual({}, self.pool.rows)
        with self.assertRaises(ProcessLookupError):
            os.kill(first['result']['pid'], 0)
        second = self.pool.query(self.action)
        self.assertFalse(second['cache_hit'])
        self.pool.close()
        with self.assertRaises(ProcessLookupError):
            os.kill(second['result']['pid'], 0)
        with self.assertRaises(InterruptedError):
            self.pool.query(self.action)

    def test_normal_terminal_command_invalidates_warm_checkout_generation(self):
        processes = Processes(self.root, self.control, time.time() + 30, lsp_pool=self.pool)
        def finish(name):
            deadline = time.monotonic() + 4
            while time.monotonic() < deadline:
                row = processes.dispatch({'action': 'terminal_poll', 'process_id': name})
                if row['exit_code'] is not None:
                    return row
                time.sleep(.01)
            self.fail('Process did not finish')
        try:
            processes.dispatch(dict(self.action, action='code_intelligence', process_id='first'))
            first = json.loads(finish('first')['output'])
            processes.dispatch(dict(self.action, action='code_intelligence', process_id='second'))
            second = json.loads(finish('second')['output'])
            self.assertTrue(second['cache_hit'])
            processes.dispatch({'action': 'terminal_start', 'process_id': 'edit', 'argv': [sys.executable, '-c',
                                'from pathlib import Path; Path("use.py").write_text("value = 9\\n")']})
            self.assertEqual(0, finish('edit')['exit_code'])
            processes.dispatch(dict(self.action, action='code_intelligence', process_id='third'))
            third = json.loads(finish('third')['output'])
            self.assertFalse(third['cache_hit'])
            self.assertEqual('value = 9\n', third['result']['text'])
            self.assertNotEqual(first['result']['pid'], third['result']['pid'])
        finally:
            processes.close()

    def _setup_failure(self, stage):
        children = []
        original_popen = subprocess.Popen
        failure = RuntimeError(stage + '-fault')

        def launch(*args, **kwargs):
            child = original_popen(*args, **kwargs)
            children.append(child)
            return child

        def cleanup():
            for child in children:
                if child.poll() is None:
                    child.kill()
                child.wait(timeout=3)
                for stream in (child.stdin, child.stdout):
                    if stream is not None:
                        stream.close()

        self.addCleanup(cleanup)
        if stage == 'client':
            fault = patch.object(workspace_lsp, 'Client', side_effect=failure)
        elif stage == 'watcher-construction':
            fault = patch.object(workspace_lsp.threading, 'Thread', side_effect=failure)
        else:
            fault = patch.object(workspace_lsp.threading.Thread, 'start', side_effect=failure)
        with patch.object(workspace_lsp.subprocess, 'Popen', side_effect=launch), fault:
            with self.assertRaises(RuntimeError) as caught:
                self.pool.query(self.action)
        self.assertIs(failure, caught.exception)
        self.assertEqual(1, len(children))
        self.assertIsNotNone(children[0].poll(), 'Failed query setup left an unowned child')
        self.assertEqual({}, self.pool.rows)
        replacement = self.pool.query(self.action)
        self.assertFalse(replacement['cache_hit'])
        self.assertNotEqual(children[0].pid, replacement['result']['pid'])

    def test_client_construction_failure_reaps_child_and_frees_session(self):
        self._setup_failure('client')

    def test_watcher_construction_failure_reaps_child_and_frees_session(self):
        self._setup_failure('watcher-construction')

    def test_watcher_start_failure_reaps_child_and_frees_session(self):
        self._setup_failure('watcher-start')

    def _late_reply(self, boundary):
        self.pool.query(self.action)
        original_row = next(iter(self.pool.rows.values()))
        original_operation = workspace_lsp._operation
        original_event, original_start = threading.Event, threading.Thread.start
        operation_done, release_operation = original_event(), original_event()
        release_watcher, cancel = original_event(), original_event()
        events, delayed_watchers, returned = [], [], {}
        clock = [time.time()]

        def operation(*args, **kwargs):
            result = original_operation(*args, **kwargs)
            if threading.current_thread() is worker:
                operation_done.set()
                if not release_operation.wait(5):
                    raise AssertionError('Test did not release the actual server reply')
            return result

        def event():
            value = original_event()
            events.append(value)
            return value

        def start(thread):
            target = thread._target
            if (getattr(target, '__qualname__', '').endswith('SessionPool.query.<locals>.watch') and
                    not delayed_watchers):
                delayed_watchers.append(thread)
                def delayed():
                    if not release_watcher.wait(5):
                        raise AssertionError('Test did not release the query watcher')
                    target()
                thread._target = delayed
            return original_start(thread)

        def query():
            try:
                returned['result'] = self.pool.query(self.action, cancel)
            except BaseException as failure:
                returned['failure'] = failure

        worker = threading.Thread(target=query)
        replacement = None
        with patch.object(workspace_lsp, '_operation', side_effect=operation), \
                patch.object(workspace_lsp.threading, 'Event', side_effect=event), \
                patch.object(workspace_lsp.threading.Thread, 'start', new=start), \
                patch.object(workspace_lsp.time, 'time', side_effect=lambda: clock[0]):
            try:
                worker.start()
                self.assertTrue(operation_done.wait(3), 'Actual server did not return a reply')
                if boundary == 'close':
                    self.pool.close()
                elif boundary == 'invalidate':
                    self.pool.invalidate()
                    fresh = self.pool.query(self.action)
                    self.assertFalse(fresh['cache_hit'])
                    replacement = next(iter(self.pool.rows.values()))
                    self.assertIsNot(original_row, replacement)
                elif boundary == 'cancel':
                    cancel.set()
                else:
                    clock[0] += self.pool.query_seconds + 1
                release_operation.set()
                # finished is the first query-owned Event. Release the delayed
                # watcher only after result admission has already been decided.
                self.assertTrue(events[0].wait(3), 'Query did not reach final cleanup')
                release_watcher.set()
                worker.join(timeout=3)
                self.assertFalse(worker.is_alive())
            finally:
                release_operation.set()
                release_watcher.set()
                worker.join(timeout=3)
        self.assertNotIn('result', returned, 'A stopped or expired query published a late success')
        self.assertIsInstance(returned.get('failure'), InterruptedError)
        self.assertIsNotNone(original_row['proc'].poll())
        if replacement is not None:
            self.assertEqual([replacement], list(self.pool.rows.values()))
            self.assertIsNone(replacement['proc'].poll(), 'Late cleanup removed the new session owner')
        else:
            self.assertEqual({}, self.pool.rows)

    def test_completed_close_rejects_a_late_reply_with_delayed_watcher(self):
        self._late_reply('close')

    def test_invalidation_rejects_late_reply_and_preserves_replacement_owner(self):
        self._late_reply('invalidate')

    def test_cancellation_rejects_a_late_reply_with_delayed_watcher(self):
        self._late_reply('cancel')

    def test_query_deadline_rejects_a_late_reply_with_delayed_watcher(self):
        self._late_reply('deadline')

    def test_observer_start_failure_retains_failure_without_using_active_allowance(self):
        processes = Processes(self.root, self.control, time.time() + 30, lsp_pool=self.pool)
        failure = RuntimeError('observer-start-fault')
        try:
            with patch.object(threading.Thread, 'start', side_effect=failure):
                for number in range(4):
                    action = dict(self.action, action='code_intelligence', process_id='failed-' + str(number))
                    with self.assertRaises(RuntimeError) as caught:
                        processes.dispatch(action)
                    self.assertIs(failure, caught.exception)
                    retained = processes.dispatch(action)
                    self.assertEqual(('EXITED', 1), (retained['state'], retained['exit_code']))
            self.assertEqual({}, self.pool.rows)
            self.assertFalse(any(row['exit_code'] is None for row in processes.jobs.values()))
            finished = threading.Event()
            original_observe = processes.observe_lsp
            def observe(*args):
                try:
                    return original_observe(*args)
                finally:
                    finished.set()
            with patch.object(processes, 'observe_lsp', side_effect=observe):
                processes.dispatch(dict(self.action, action='code_intelligence', process_id='next-query'))
                self.assertTrue(finished.wait(3), 'Failed observer attempts retained the active allowance')
            self.assertEqual(0, processes.dispatch({'action': 'terminal_poll', 'process_id': 'next-query'})['exit_code'])
        finally:
            processes.close()

    def test_late_observer_cannot_overwrite_a_recorded_start_failure(self):
        processes = Processes(self.root, self.control, time.time() + 30, lsp_pool=self.pool)
        failure = RuntimeError('observer-start-after-launch-fault')
        query_finished = threading.Event()
        original_start, original_query = threading.Thread.start, self.pool.query
        observers = []

        def query(*args, **kwargs):
            result = original_query(*args, **kwargs)
            query_finished.set()
            return result

        def start(thread):
            target = thread._target
            original_start(thread)
            if getattr(target, '__self__', None) is processes:
                observers.append(thread)
                if not query_finished.wait(3):
                    raise AssertionError('Actual server query did not finish before the injected start failure')
                raise failure

        action = dict(self.action, action='code_intelligence', process_id='late-observer')
        try:
            with patch.object(self.pool, 'query', side_effect=query), patch.object(threading.Thread, 'start', new=start):
                with self.assertRaises(RuntimeError) as caught:
                    processes.dispatch(action)
                self.assertIs(failure, caught.exception)
                self.assertEqual(1, len(observers))
                observers[0].join(timeout=3)
                self.assertFalse(observers[0].is_alive())
            retained = processes.dispatch({'action': 'terminal_poll', 'process_id': 'late-observer'})
            self.assertEqual(('EXITED', 1), (retained['state'], retained['exit_code']))
            self.assertEqual(0, retained['output_bytes'])
            self.assertEqual('', retained['output'])
            self.assertTrue(processes.jobs['late-observer']['cancel'].is_set())
        finally:
            processes.close()
            for observer in observers:
                observer.join(timeout=3)

    def _helper_server(self, extra=''):
        server = (
            "import subprocess, signal, sys\n"
            "helper = subprocess.Popen([sys.executable, '-c', 'import signal; signal.pause()'], start_new_session=True)\n"
            "with open(sys.argv[1], 'w') as pid_file: pid_file.write(str(helper.pid))\n" + extra + SERVER
        ).replace("'pid': os.getpid()", "'pid': os.getpid(), 'helper_pid': helper.pid")
        return server.replace('time.sleep(10)', """raw = json.dumps({'jsonrpc': '2.0', 'method': '$/probeBlocked'}).encode()
            sys.stdout.buffer.write(b'Content-Length: ' + str(len(raw)).encode() + b'\\r\\n\\r\\n' + raw)
            sys.stdout.buffer.flush()
            signal.pause()""")

    def _kill_helper(self, pid_file):
        if pid_file.exists():
            try:
                os.killpg(int(pid_file.read_text()), signal.SIGKILL)
            except ProcessLookupError:
                pass

    def test_cancellation_finishes_while_helper_retains_stdout(self):
        pid_file = self.folder / 'stdout-helper.pid'
        self.pool.servers['python'] = (sys.executable, '-u', '-c', self._helper_server(), str(pid_file))
        self.pool.query_seconds = 5
        cancel, reached = threading.Event(), threading.Event()
        returned = {}
        original_receive = workspace_lsp.Client.receive

        def receive(client):
            message = original_receive(client)
            if message.get('method') == '$/probeBlocked':
                reached.set()
            return message

        def query():
            try:
                returned['result'] = self.pool.query(self.action, cancel)
            except BaseException as failure:
                returned['failure'] = failure

        worker = None
        try:
            first = self.pool.query(self.action)
            helper_pid = first['result']['helper_pid']
            direct = next(iter(self.pool.rows.values()))['proc']
            self.assertNotEqual(direct.pid, os.getpgid(helper_pid))
            (self.root / 'use.py').write_text('BLOCK_SERVER\n')
            worker = threading.Thread(target=query, daemon=True)
            with patch.object(workspace_lsp.Client, 'receive', new=receive):
                worker.start()
                self.assertTrue(reached.wait(3), 'Actual server did not reach its blocked reply')
                cancel.set()
                worker.join(timeout=2)
                self.assertFalse(worker.is_alive(), 'Cancellation waited for inherited stdout to close')
            self.assertNotIn('result', returned)
            self.assertIsInstance(returned.get('failure'), InterruptedError)
            self.assertIsNotNone(direct.poll())
            self.assertEqual({}, self.pool.rows)
            os.kill(helper_pid, 0)  # The inherited pipe is still open when cancellation completes.
        finally:
            cancel.set()
            self._kill_helper(pid_file)
            if worker is not None:
                worker.join(timeout=4)

    def test_fragmented_frames_preserve_initialized_queries(self):
        framed_write = "sys.stdout.buffer.write(b'Content-Length: ' + str(len(raw)).encode() + b'\\r\\n\\r\\n' + raw)"
        fragmented = """frame = b'Content-Length: ' + str(len(raw)).encode() + b'\\r\\n\\r\\n' + raw
    for offset in range(0, len(frame), 3):
        sys.stdout.buffer.write(frame[offset:offset + 3])
        sys.stdout.buffer.flush()"""
        self.assertIn(framed_write, SERVER)
        self.pool.servers['python'] = (sys.executable, '-u', '-c', SERVER.replace(framed_write, fragmented))
        text = 'value = "λ語🙂"\n'
        (self.root / 'use.py').write_text(text)
        original_popen, original_read = subprocess.Popen, os.read
        descriptors = set()

        def launch(*args, **kwargs):
            child = original_popen(*args, **kwargs)
            descriptors.add(child.stdout.fileno())
            return child

        def read(fd, size):
            return original_read(fd, min(size, 3) if fd in descriptors else size)

        with patch.object(workspace_lsp.subprocess, 'Popen', side_effect=launch), patch.object(workspace_lsp.os, 'read', side_effect=read):
            first = self.pool.query(self.action)
            again = self.pool.query(self.action)
        self.assertEqual(text, first['result']['text'])
        self.assertEqual(first['result'], again['result'])
        self.assertFalse(first['cache_hit'])
        self.assertTrue(again['cache_hit'])

    def test_cancellation_finishes_with_full_stdin_inherited_by_helper(self):
        import selectors
        read_fd, write_fd = os.pipe()
        pid_file = self.folder / 'stdin-helper.pid'
        pause_input = """import os
def pause_input(signum, frame):
    os.write(int(sys.argv[2]), b'R')
    signal.pause()
signal.signal(signal.SIGUSR1, pause_input)
"""
        self.pool.servers['python'] = (sys.executable, '-u', '-c', self._helper_server(pause_input), str(pid_file), str(write_fd))
        self.pool.query_seconds = 5
        original_popen, original_send = subprocess.Popen, workspace_lsp.Client.send
        cancel, writing = threading.Event(), threading.Event()
        returned = {}
        worker = None

        def launch(*args, **kwargs):
            return original_popen(*args, **dict(kwargs, pass_fds=(write_fd,)))

        def send(client, message):
            if message.get('method') == 'textDocument/didChange':
                writing.set()
            return original_send(client, message)

        def query():
            try:
                returned['result'] = self.pool.query(self.action, cancel)
            except BaseException as failure:
                returned['failure'] = failure

        try:
            with patch.object(workspace_lsp.subprocess, 'Popen', side_effect=launch):
                first = self.pool.query(self.action)
            helper_pid = first['result']['helper_pid']
            direct = next(iter(self.pool.rows.values()))['proc']
            self.assertNotEqual(direct.pid, os.getpgid(helper_pid))
            os.kill(direct.pid, signal.SIGUSR1)
            with selectors.DefaultSelector() as selector:
                selector.register(read_fd, selectors.EVENT_READ)
                self.assertTrue(selector.select(3), 'Actual server did not stop reading stdin')
                self.assertEqual(b'R', os.read(read_fd, 1))
            descriptor = direct.stdin.fileno()
            blocking = os.get_blocking(descriptor)
            os.set_blocking(descriptor, False)
            try:
                filled = 0
                while filled < 2 * 1024 * 1024:
                    try:
                        filled += os.write(descriptor, b' ' * 4096)
                    except BlockingIOError:
                        break
                else:
                    self.fail('Actual server unexpectedly drained the bounded stdin fixture')
                self.assertGreater(filled, 0)
            finally:
                os.set_blocking(descriptor, blocking)
            (self.root / 'use.py').write_text('value = "' + 'x' * 256_000 + '"\n')
            worker = threading.Thread(target=query, daemon=True)
            with patch.object(workspace_lsp.Client, 'send', new=send):
                worker.start()
                self.assertTrue(writing.wait(3), 'Query did not attempt its document write')
                cancel.set()
                worker.join(timeout=2)
                self.assertFalse(worker.is_alive(), 'Cancellation waited for inherited stdin to drain')
            self.assertNotIn('result', returned)
            self.assertIsInstance(returned.get('failure'), InterruptedError)
            self.assertIsNotNone(direct.poll())
            self.assertEqual({}, self.pool.rows)
            os.kill(helper_pid, 0)
        finally:
            cancel.set()
            self._kill_helper(pid_file)
            if worker is not None:
                worker.join(timeout=4)
            os.close(read_fd)
            os.close(write_fd)
