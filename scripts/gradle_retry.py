#!/usr/bin/env python3
"""Retry dependency throttling without retrying compilation or test failures."""
import os
import re
import signal
import subprocess
import sys
import threading
import time

TRANSIENT = re.compile(r'(?:HTTP|status(?: code)?)\D{0,12}(?:429|502|503|504)\b', re.I)
DEPENDENCY = re.compile(r'Could not (?:resolve|download|GET|HEAD)|Could not get resource', re.I)
DETERMINISTIC = re.compile(r'There were failing tests|\btests completed, [1-9]\d* failed|Compilation error|^e: .*\.(?:kt|java):|Lint found .* errors', re.I)


def retryable(lines):
    return any(DEPENDENCY.search(line) for line in lines) and any(TRANSIENT.search(line) for line in lines) and not any(DETERMINISTIC.search(line) for line in lines)


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


def run(arguments, attempts=3, delay_seconds=10, executable='./gradlew', timeout_seconds=None, termination_grace_seconds=15):
    if attempts < 1 or timeout_seconds is not None and timeout_seconds <= 0 or termination_grace_seconds < 0:
        raise ValueError('Attempts and timeout must be positive; termination grace must be nonnegative')
    environment = dict(os.environ)
    environment.setdefault('OBX_DISABLE_ANALYTICS', 'true')
    deadline = time.monotonic() + timeout_seconds if timeout_seconds is not None else None
    for attempt in range(attempts):
        if deadline is not None and time.monotonic() >= deadline:
            return 124
        dependency = transient = deterministic = False
        with subprocess.Popen([executable, *arguments], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace', env=environment, start_new_session=os.name == 'posix') as process:
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
        if code == 0 or attempt + 1 == attempts or not (dependency and transient) or deterministic:
            return code
        pause = delay_seconds * (attempt + 1)
        if deadline is not None:
            pause = min(pause, max(0, deadline - time.monotonic()))
        print(f'Dependency service throttled or unavailable; retry {attempt + 2}/{attempts} after {pause}s. Gradle cache is retained.', flush=True)
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
