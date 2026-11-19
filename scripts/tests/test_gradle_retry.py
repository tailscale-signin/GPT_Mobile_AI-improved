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

    def test_hilt_recovery_requires_the_published_version_and_preserves_failures(self):
        lines = [
            "Plugin [id: 'com.google.dagger.hilt.android', version: '2.60.1', "
            "artifact: 'com.google.dagger:hilt-android-gradle-plugin:2.60.1', apply: false] was not found",
            "Plugin Repositories (could not resolve plugin artifact 'com.google.dagger:hilt-android-gradle-plugin:2.60.1')",
        ]
        self.assertTrue(retry.retryable(lines))
        self.assertFalse(retry.retryable([line.replace("2.60.1", "2.60.99") for line in lines]))
        self.assertFalse(retry.retryable(lines + ["HTTP 404"]))
        self.assertFalse(retry.retryable(lines + ["Compilation error"]))

    def test_published_kotlin_plugin_lookup_without_status_retries_only_exact_version(self):
        lines = [
            "Plugin [id: 'org.jetbrains.kotlin.multiplatform', version: '2.4.20', "
            "artifact: 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20', apply: false] was not found",
            "Plugin Repositories (could not resolve plugin artifact 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20')",
        ]
        self.assertTrue(retry.retryable(lines))
        self.assertFalse(retry.retryable([lines[0]]))
        self.assertFalse(retry.retryable([lines[1]]))
        self.assertFalse(retry.retryable([line.replace('2.4.20', '2.4.21') for line in lines]))
        self.assertFalse(retry.retryable(lines + ["Received HTTP response code: 404"]))

        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            counter, fake = root / 'attempts', root / 'gradlew'
            fake.write_text(
                '#!/bin/sh\n'
                + f'echo attempt >> "{counter}"\n'
                + f'if [ "$(wc -l < "{counter}")" -eq 1 ]; then\n'
                + "echo \"Plugin [id: 'org.jetbrains.kotlin.multiplatform', version: '2.4.20', "
                + "artifact: 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20', apply: false] was not found\"\n"
                + "echo \"Plugin Repositories (could not resolve plugin artifact 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20')\"\n"
                + 'exit 1\nfi\nexit 0\n'
            )
            fake.chmod(0o755)
            self.assertEqual(0, retry.run([':app:lintDebug'], delay_seconds=0, executable=str(fake)))
            self.assertEqual(2, len(counter.read_text().splitlines()))

    def test_published_objectbox_plugin_lookup_is_bounded_and_exact(self):
        lines = [
            "Plugin [id: 'io.objectbox', version: '5.4.2', "
            "artifact: 'io.objectbox:objectbox-gradle-plugin:5.4.2', apply: false] was not found",
            "Plugin Repositories (could not resolve plugin artifact 'io.objectbox:objectbox-gradle-plugin:5.4.2')",
        ]
        self.assertTrue(retry.retryable(lines))
        self.assertFalse(retry.retryable([lines[0]]))
        self.assertFalse(retry.retryable([lines[1]]))
        self.assertFalse(retry.retryable([line.replace('5.4.2', '5.4.3') for line in lines]))
        self.assertFalse(retry.retryable(lines + ["Received HTTP response code: 404"]))
        kotlin_plugin = [
            "Plugin [id: 'org.jetbrains.kotlin.multiplatform', version: '2.4.20', "
            "artifact: 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20', apply: false] was not found",
            "Plugin Repositories (could not resolve plugin artifact 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20')",
        ]
        self.assertFalse(retry.retryable([kotlin_plugin[0], lines[1]]))
        self.assertFalse(retry.retryable([lines[0], kotlin_plugin[1]]))

    def test_bounded_retry_recovers_when_published_plugin_failure_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            counter, fake = root / 'attempts', root / 'gradlew'
            fake.write_text(
                '#!/bin/sh\n'
                + f'echo attempt >> "{counter}"\n'
                + f'case "$(wc -l < "{counter}")" in\n'
                + ' 1|2)\n'
                + "echo \"Plugin [id: 'org.jetbrains.kotlin.multiplatform', version: '2.4.20', "
                  "artifact: 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20', apply: false] was not found\"\n"
                + "echo \"Plugin Repositories (could not resolve plugin artifact 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20')\"\n"
                + 'exit 1;;\n'
                + ' 3)\n'
                + "echo \"Plugin [id: 'io.objectbox', version: '5.4.2', "
                  "artifact: 'io.objectbox:objectbox-gradle-plugin:5.4.2', apply: false] was not found\"\n"
                + "echo \"Plugin Repositories (could not resolve plugin artifact 'io.objectbox:objectbox-gradle-plugin:5.4.2')\"\n"
                + 'exit 1;;\n'
                + 'esac\n'
                + 'exit 0\n'
            )
            fake.chmod(0o755)
            self.assertEqual(0, retry.run([':app:compileDebugKotlin'], delay_seconds=0, executable=str(fake)))
            self.assertEqual(4, len(counter.read_text().splitlines()))

    def test_published_plugin_recovery_refreshes_metadata_without_changing_tasks(self):
        with tempfile.TemporaryDirectory() as directory:
            record = pathlib.Path(directory) / 'calls'
            fake = pathlib.Path(directory) / 'gradlew'
            fake.write_text('#!/bin/sh\n' + f'echo "${{GPTMOBILE_PLUGIN_RESOLUTION_RECOVERY:-0}} $*" >> "{record}"\n'
                            + 'if [ "${GPTMOBILE_PLUGIN_RESOLUTION_RECOVERY:-0}" != 1 ]; then\n'
                            + "echo \"Plugin [id: 'org.jetbrains.kotlin.multiplatform', version: '2.4.20', artifact: 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20', apply: false] was not found\"\n"
                            + "echo \"Plugin Repositories (could not resolve plugin artifact 'org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20')\"\n"
                            + 'exit 1\nfi\n')
            fake.chmod(0o755)
            args = [':app:compileDebugKotlin', '--stacktrace']
            self.assertEqual(0, retry.run(args, delay_seconds=0, executable=str(fake)))
            self.assertEqual(['0 :app:compileDebugKotlin --stacktrace',
                              '1 :app:compileDebugKotlin --stacktrace --refresh-dependencies --info'], record.read_text().splitlines())
            self.assertEqual([':app:compileDebugKotlin', '--stacktrace'], args)
            self.assertEqual(1, retry.run(args + ['--offline'], delay_seconds=0, executable=str(fake)))
            self.assertEqual(3, len(record.read_text().splitlines()))

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

    def test_persistent_throttling_stops_after_four_attempts(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            counter = root / 'count'
            fake = root / 'gradlew'
            fake.write_text(f'#!/bin/sh\necho attempt >> "{counter}"\necho "Could not GET resource. HTTP 429"\nexit 7\n')
            fake.chmod(0o755)
            self.assertEqual(7, retry.run(['help'], delay_seconds=0, executable=str(fake)))
            self.assertEqual(4, len(counter.read_text().splitlines()))
