import importlib.util
import pathlib
import tempfile
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / 'gradle_retry.py'
SPEC = importlib.util.spec_from_file_location('gradle_retry', SCRIPT)
retry = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(retry)


class GradleRetryTest(unittest.TestCase):
    def test_dependency_throttling_is_retryable(self):
        self.assertTrue(retry.retryable(['Could not resolve dependency.', 'Received status code 429 from server: Too Many Requests']))
        self.assertTrue(retry.retryable(['Could not GET resource.', 'HTTP 503']))

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
            arguments = [':app:clean', ':app:compileDebugKotlin', ':app:compileDebugJavaWithJavac', '--no-build-cache', '--rerun-tasks', '--info']
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

    def test_persistent_throttling_stops_after_three_attempts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            counter = root / 'count'
            fake = root / 'gradlew'
            fake.write_text(f'#!/bin/sh\necho attempt >> "{counter}"\necho "Could not GET resource. HTTP 429"\nexit 7\n')
            fake.chmod(0o755)
            self.assertEqual(7, retry.run(['help'], delay_seconds=0, executable=str(fake)))
            self.assertEqual(3, len(counter.read_text().splitlines()))
