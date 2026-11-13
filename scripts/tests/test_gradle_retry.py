import importlib.util
import os
import pathlib
import select
import tempfile
import time
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'gradle_retry.py'
SPEC = importlib.util.spec_from_file_location('gradle_retry', SCRIPT)
retry = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(retry)


class GradleRetryTest(unittest.TestCase):
    @unittest.skipUnless(hasattr(os, 'pidfd_open'), 'Descendant exit qualification requires Linux process handles')
    def test_deadline_terminates_a_stalled_descendant_and_never_retries_it(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            pid_file, counter = root / 'child.pid', root / 'attempts'
            fake = root / 'gradlew'
            fake.write_text('#!/usr/bin/env python3\nimport subprocess,sys,time\n' +
                            f'open({str(counter)!r}, "a").write("attempt\\n")\n' +
                            'print("Could not resolve dependency. HTTP 503", flush=True)\n' +
                            f'child = subprocess.Popen([sys.executable, "-c", "import signal,time; signal.signal(signal.SIGTERM, signal.SIG_IGN); time.sleep(60)"])\nopen({str(pid_file)!r}, "w").write(str(child.pid))\n' +
                            'time.sleep(60)\n')
            fake.chmod(0o755)
            started = time.monotonic()
            self.assertEqual(124, retry.run(['packageNativeSmoke'], executable=str(fake), timeout_seconds=.5, termination_grace_seconds=.1, delay_seconds=0))
            self.assertLess(time.monotonic() - started, 3)
            self.assertEqual(['attempt'], counter.read_text().splitlines())
            child = int(pid_file.read_text())
            # A kernel process handle avoids /proc read/exit races and still
            # addresses the correct PID inside a container's PID namespace.
            try:
                descriptor = os.pidfd_open(child)
            except ProcessLookupError:
                pass  # The kernel has already reaped the terminated child.
            else:
                try:
                    exited, _, _ = select.select([descriptor], [], [], 1)
                    self.assertTrue(exited, 'Timed-out descendant is still running')
                finally:
                    os.close(descriptor)

    def test_dependency_backoff_uses_the_same_total_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            counter, fake = root / 'count', root / 'gradlew'
            fake.write_text(f'#!/bin/sh\necho attempt >> "{counter}"\necho "Could not GET resource. HTTP 429"\nexit 7\n')
            fake.chmod(0o755)
            started = time.monotonic()
            self.assertEqual(124, retry.run(['help'], executable=str(fake), timeout_seconds=.1, delay_seconds=10))
            self.assertLess(time.monotonic() - started, 2)
            self.assertEqual(1, len(counter.read_text().splitlines()))

    def test_dependency_throttling_is_retryable(self):
        self.assertTrue(retry.retryable(['Could not resolve dependency.', 'Received status code 429 from server: Too Many Requests']))
        self.assertTrue(retry.retryable(['Could not GET resource.', 'HTTP 503']))
        self.assertTrue(retry.retryable([
            'Downloading https://services.gradle.org/distributions/gradle-9.8.0-bin.zip',
            'java.io.IOException: Server returned HTTP response code: 500 for URL: '
            'https://github.com/gradle/gradle-distributions/releases/download/v9.8.0/gradle-9.8.0-bin.zip',
        ]))

    def test_compilation_assertions_and_authentication_are_not_retried(self):
        for lines in [
            ['There were failing tests.'],
            ['Could not resolve dependency.', 'HTTP 401'],
            ['Could not GET resource.', 'HTTP 429', 'Compilation error'],
            ['Could not GET resource.', 'HTTP 429', '5216 tests completed, 1 failed'],
            ["Plugin [id: 'io.objectbox', version: 'missing'] was not found", "Could not resolve plugin artifact"],
        ]:
            self.assertFalse(retry.retryable(lines))

    def test_codeql_build_keeps_tasks_and_retries_only_transient_plugin_downloads(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            counter = root / 'count'
            fake = root / 'gradlew'
            fake.write_text('#!/bin/sh\n' +
                            f'echo "$*" >> "{counter}"\n' +
                            f'if [ "$(wc -l < "{counter}")" -eq 1 ]; then\n' +
                            ' echo "Could not resolve plugin artifact. HTTP 503"\n exit 1\nfi\nexit 0\n')
            fake.chmod(0o755)
            arguments = [':app:compileDebugKotlin', '--rerun', ':app:compileDebugJavaWithJavac', '--rerun', '-Pkotlin.incremental=false', '--no-build-cache', '--info']
            self.assertEqual(0, retry.run(arguments, delay_seconds=0, executable=str(fake)))
            self.assertEqual([' '.join(arguments)] * 2, counter.read_text().splitlines())

    def test_real_process_recovers_once_and_preserves_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            counter = root / 'count'
            fake = root / 'gradlew'
            fake.write_text('#!/bin/sh\n' +
                            'test "$OBX_DISABLE_ANALYTICS" = true || exit 9\n' +
                            f'echo "$*" >> "{counter}"\n' +
                            f'if [ "$(wc -l < "{counter}")" -eq 1 ]; then\n' +
                            ' echo "Could not resolve dependency. HTTP 429"\n exit 1\nfi\nexit 0\n')
            fake.chmod(0o755)
            self.assertEqual(0, retry.run(['testDebugUnitTest', '--no-daemon'], delay_seconds=0, executable=str(fake)))
            self.assertEqual(['testDebugUnitTest --no-daemon'] * 2, counter.read_text().splitlines())

    def test_gradle_wrapper_distribution_500_recovers_without_losing_arguments(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            counter = root / 'count'
            fake = root / 'gradlew'
            fake.write_text('#!/bin/sh\n' +
                            f'echo "$*" >> "{counter}"\n' +
                            f'if [ "$(wc -l < "{counter}")" -eq 1 ]; then\n' +
                            ' echo "Downloading https://services.gradle.org/distributions/gradle-9.8.0-bin.zip"\n' +
                            ' echo "Server returned HTTP response code: 500 for URL: https://github.com/gradle/gradle-distributions/releases/download/v9.8.0/gradle-9.8.0-bin.zip"\n' +
                            ' exit 1\nfi\nexit 0\n')
            fake.chmod(0o755)
            arguments = [':app:prepareMemoryModel', ':core-domain:jvmTest', '--configuration-cache']
            self.assertEqual(0, retry.run(arguments, delay_seconds=0, executable=str(fake)))
            self.assertEqual([' '.join(arguments)] * 2, counter.read_text().splitlines())

    def test_persistent_throttling_stops_after_three_attempts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            counter = root / 'count'
            fake = root / 'gradlew'
            fake.write_text(f'#!/bin/sh\necho attempt >> "{counter}"\necho "Could not GET resource. HTTP 429"\nexit 7\n')
            fake.chmod(0o755)
            self.assertEqual(7, retry.run(['help'], delay_seconds=0, executable=str(fake)))
            self.assertEqual(3, len(counter.read_text().splitlines()))
