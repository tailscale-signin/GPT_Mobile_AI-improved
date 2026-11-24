#!/usr/bin/env python3
"""Retry qualified dependency and SDK provisioning failures within the build deadline."""
import os
import re
import signal
import subprocess
import sys
import threading
import time

TRANSIENT = re.compile(r'(?:HTTP|status(?: code)?|response code:)\D{0,12}(?:429|500|502|503|504)\b', re.I)
DEPENDENCY = re.compile(
    r'Could not (?:resolve|download|GET|HEAD)|Could not get resource|'
    r'Downloading https?://|services\.gradle\.org/distributions|gradle-distributions/releases/download',
    re.I,
)
# Gradle can hide transport failures behind UnknownPluginException with no HTTP
# status. These exact artifacts are independently published on Maven Central;
# a missing version, authentication denial, or HTTP 404 is never retried.
# Match each plugin and module together, not one plugin from a different error.
PUBLISHED_MARKERS = (
    ('org.jetbrains.kotlin.plugin.compose', '2.4.20'),
    ('org.jetbrains.kotlin.plugin.parcelize', '2.4.20'),
    ('org.jetbrains.kotlin.plugin.serialization', '2.4.20'),
    ('com.google.devtools.ksp', '2.3.12'),
)
KNOWN_PUBLISHED_PLUGINS = (
    (
        re.compile(
            r"Plugin \[id: 'org\.jetbrains\.kotlin\.multiplatform', version: '2\.4\.20', "
            r"artifact: 'org\.jetbrains\.kotlin:kotlin-gradle-plugin:2\.4\.20', apply: false\] was not found",
            re.I,
        ),
        re.compile(r"could not resolve plugin artifact 'org\.jetbrains\.kotlin:kotlin-gradle-plugin:2\.4\.20'", re.I),
    ),
    (
        re.compile(
            r"Plugin \[id: 'io\.objectbox', version: '5\.4\.2', "
            r"artifact: 'io\.objectbox:objectbox-gradle-plugin:5\.4\.2', apply: false\] was not found",
            re.I,
        ),
        re.compile(r"could not resolve plugin artifact 'io\.objectbox:objectbox-gradle-plugin:5\.4\.2'", re.I),
    ),
    (
        re.compile(
            r"Plugin \[id: 'com\.google\.dagger\.hilt\.android', version: '2\.60\.1', "
            r"artifact: 'com\.google\.dagger:hilt-android-gradle-plugin:2\.60\.1', apply: false\] was not found",
            re.I,
        ),
        re.compile(r"could not resolve plugin artifact 'com\.google\.dagger:hilt-android-gradle-plugin:2\.60\.1'", re.I),
    ),
) + tuple(
    (
        re.compile(rf"Plugin \[id: '{re.escape(marker)}', version: '{re.escape(version)}', apply: false\] was not found", re.I),
        re.compile(rf"could not resolve plugin artifact '{re.escape(marker)}:{re.escape(marker)}\.gradle\.plugin:{re.escape(version)}'", re.I),
    )
    for marker, version in PUBLISHED_MARKERS
)
NON_TRANSIENT_HTTP = re.compile(r'(?:HTTP|status(?: code)?|response code:)\D{0,12}(?:401|403|404)\b', re.I)
DETERMINISTIC = re.compile(r'There were failing tests|\btests completed, [1-9]\d* failed|Compilation error|^e: .*\.(?:kt|java):|Lint found .* errors', re.I)
FAILED_TASK = re.compile(r"Execution failed for task '([^']+)'")
CMAKE_TASK = re.compile(r':configureCMake\w+\[[^\]]+\]$')
CMAKE_PACKAGE_WARNING = 'An error occurred while preparing SDK package CMake 3.22.1: Error reading Zip content from a SeekableByteChannel'
CMAKE_PACKAGE_ERRORS = (
    CMAKE_PACKAGE_WARNING,
    'java.util.zip.ZipException: Archive is not a ZIP archive',
    '"Install CMake 3.22.1 v.3.22.1" failed.',
    'com.android.builder.sdk.InstallFailedException: Failed to install the following SDK components:',
    'cmake;3.22.1 CMake 3.22.1',
)
SDK_BLOCKER = re.compile(
    r'dependency verification failed|(?:checksum|sha-?256) (?:verification failed|mismatch|does not match)|'
    r'licen[cs]es? (?:not accepted|ha(?:s|ve) not been accepted)|CMake Error(?: at| in|:)|ninja: build stopped|'
    r'(?:HTTP|status(?: code)?|response code:)\D{0,12}4\d\d\b',
    re.I,
)


class BuildFailure:
    def __init__(self):
        self.dependency = self.transient = self.central_unavailable = self.blocked = False
        self.published = set()
        self.artifacts = set()
        self.cmake_errors = set()
        self.cmake_task = self.other_task = self.sdk_blocked = False

    def observe(self, line):
        self.dependency |= bool(DEPENDENCY.search(line))
        self.transient |= bool(TRANSIENT.search(line))
        self.central_unavailable |= 'repo.maven.apache.org/maven2/' in line
        self.blocked |= bool(DETERMINISTIC.search(line) or NON_TRANSIENT_HTTP.search(line))
        for index, (plugin, artifact) in enumerate(KNOWN_PUBLISHED_PLUGINS):
            if plugin.search(line):
                self.published.add(index)
            if artifact.search(line):
                self.artifacts.add(index)
        self.cmake_errors.update(error for error in CMAKE_PACKAGE_ERRORS if error in line)
        task = FAILED_TASK.search(line)
        if task:
            if CMAKE_TASK.search(task[1]):
                self.cmake_task = True
            else:
                self.other_task = True
        self.sdk_blocked |= bool(SDK_BLOCKER.search(line))

    def recovery(self):
        if self.blocked:
            return None
        if CMAKE_PACKAGE_WARNING in self.cmake_errors:
            if (len(self.cmake_errors) == len(CMAKE_PACKAGE_ERRORS) and self.cmake_task
                    and not self.other_task and not self.sdk_blocked):
                return 'android-cmake'
            return None
        if self.published & self.artifacts or self.dependency and self.transient and self.central_unavailable:
            return 'maven-mirror'
        if self.dependency and self.transient:
            return 'dependency'
        return None


def retryable(lines):
    failure = BuildFailure()
    for line in lines:
        failure.observe(line)
    return failure.recovery() is not None


def stop_process_group(process, force=False):
    try:
        if os.name == 'posix':
            os.killpg(process.pid, signal.SIGKILL if force else signal.SIGTERM)
        elif force:
            process.kill()
        else:
            process.terminate()
    except ProcessLookupError:
        pass


def run(arguments, attempts=4, delay_seconds=10, executable='./gradlew', timeout_seconds=None, termination_grace_seconds=15):
    if attempts < 1 or timeout_seconds is not None and timeout_seconds <= 0 or termination_grace_seconds < 0:
        raise ValueError('Attempts and timeout must be positive; termination grace must be nonnegative')
    environment = dict(os.environ)
    environment.setdefault('OBX_DISABLE_ANALYTICS', 'true')
    deadline = time.monotonic() + timeout_seconds if timeout_seconds is not None else None
    command_args = list(arguments)
    retried_cmake = False
    for attempt in range(attempts):
        if deadline is not None and time.monotonic() >= deadline:
            return 124
        failure = BuildFailure()
        with subprocess.Popen([executable, *command_args], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace', env=environment, start_new_session=os.name == 'posix') as process:
            finished, timed_out = threading.Event(), threading.Event()

            def watchdog():
                if finished.wait(max(0, deadline - time.monotonic())):
                    return
                timed_out.set()
                print('Build process deadline expired; terminating its process group. No automatic retry.', flush=True)
                stop_process_group(process)
                # A descendant can keep stdout open after the launcher exits.
                if not finished.wait(termination_grace_seconds):
                    stop_process_group(process, force=True)

            monitor = threading.Thread(target=watchdog, daemon=True) if deadline is not None else None
            if monitor is not None:
                monitor.start()
            try:
                for line in process.stdout:
                    print(line, end='', flush=True)
                    failure.observe(line)
                code = process.wait()
            except BaseException:
                stop_process_group(process)
                try:
                    process.wait(timeout=termination_grace_seconds)
                except subprocess.TimeoutExpired:
                    stop_process_group(process, force=True)
                    process.wait()
                stop_process_group(process, force=True)
                raise
            finally:
                finished.set()
                if monitor is not None:
                    monitor.join()
            if timed_out.is_set():
                # Kill surviving descendants even if the parent exited on SIGTERM.
                stop_process_group(process, force=True)
                return 124
        recovery = failure.recovery()
        if code == 0 or attempt + 1 == attempts or '--offline' in arguments or recovery is None:
            return code
        if recovery == 'android-cmake':
            if retried_cmake:
                return code
            retried_cmake = True
            print('Android SDK CMake 3.22.1 installation received a non-ZIP archive. Retrying the original build once.', flush=True)
        if recovery == 'maven-mirror' and environment.get('GPTMOBILE_PLUGIN_RESOLUTION_RECOVERY') != '1':
            environment['GPTMOBILE_PLUGIN_RESOLUTION_RECOVERY'] = '1'
            command_args.extend(flag for flag in ('--refresh-dependencies', '--info') if flag not in command_args)
            print('Rechecking dependency metadata with the Maven Central mirror. Dependency verification remains enabled.', flush=True)
        pause = delay_seconds * (attempt + 1)
        if deadline is not None:
            pause = min(pause, max(0, deadline - time.monotonic()))
        print(f'Retry {attempt + 2}/{attempts} after {pause}s. Gradle cache is retained.', flush=True)
        time.sleep(pause)
    raise AssertionError('Unreachable retry state')


if __name__ == '__main__':
    arguments = sys.argv[1:]
    timeout_seconds = None
    if arguments[:1] == ['--timeout-seconds']:
        timeout_seconds = float(arguments[1])
        arguments = arguments[2:]
    if arguments[:1] == ['--']:
        arguments = arguments[1:]
    if not arguments:
        raise SystemExit('Pass Gradle tasks and options after --')
    raise SystemExit(run(arguments, timeout_seconds=timeout_seconds))
