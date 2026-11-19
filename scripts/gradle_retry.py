#!/usr/bin/env python3
"""Retry dependency throttling without retrying compilation or test failures."""
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


def retryable(lines):
    lines = list(lines)
    throttled = any(DEPENDENCY.search(line) for line in lines) and any(TRANSIENT.search(line) for line in lines)
    known_plugin = any(
        any(plugin.search(line) for line in lines) and
        any(artifact.search(line) for line in lines)
        for plugin, artifact in KNOWN_PUBLISHED_PLUGINS
    )
    return (throttled or known_plugin) and not any(
        DETERMINISTIC.search(line) or NON_TRANSIENT_HTTP.search(line) for line in lines
    )


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
    for attempt in range(attempts):
        if deadline is not None and time.monotonic() >= deadline:
            return 124
        dependency = transient = deterministic = denied = False
        published = [False] * len(KNOWN_PUBLISHED_PLUGINS)
        artifact = [False] * len(KNOWN_PUBLISHED_PLUGINS)
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
                    dependency |= bool(DEPENDENCY.search(line))
                    transient |= bool(TRANSIENT.search(line))
                    for index, (plugin_pattern, artifact_pattern) in enumerate(KNOWN_PUBLISHED_PLUGINS):
                        published[index] |= bool(plugin_pattern.search(line))
                        artifact[index] |= bool(artifact_pattern.search(line))
                    denied |= bool(NON_TRANSIENT_HTTP.search(line))
                    deterministic |= bool(DETERMINISTIC.search(line))
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
        published_plugin_missing = any(p and a for p, a in zip(published, artifact))
        if code == 0 or attempt + 1 == attempts or '--offline' in arguments or not ((dependency and transient) or published_plugin_missing) or deterministic or denied:
            return code
        if published_plugin_missing and environment.get('GPTMOBILE_PLUGIN_RESOLUTION_RECOVERY') != '1':
            environment['GPTMOBILE_PLUGIN_RESOLUTION_RECOVERY'] = '1'
            command_args.extend(flag for flag in ('--refresh-dependencies', '--info') if flag not in command_args)
            print('Rechecking published plugin metadata with the Maven Central mirror. Dependency verification remains enabled.', flush=True)
        pause = delay_seconds * (attempt + 1)
        if deadline is not None:
            pause = min(pause, max(0, deadline - time.monotonic()))
        print(f'Qualified dependency resolution failure; retry {attempt + 2}/{attempts} after {pause}s. Gradle cache is retained.', flush=True)
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
