#!/usr/bin/env python3
"""Retry dependency throttling without retrying compilation or test failures."""
import re
import subprocess
import sys
import time

TRANSIENT = re.compile(r'(?:HTTP|status(?: code)?)\D{0,12}(?:429|502|503|504)\b', re.I)
DEPENDENCY = re.compile(r'Could not (?:resolve|download|GET|HEAD)|Could not get resource', re.I)
DETERMINISTIC = re.compile(r'There were failing tests|\btests completed, [1-9]\d* failed|Compilation error|^e: .*\.(?:kt|java):|Lint found .* errors', re.I)


def retryable(lines):
    return any(DEPENDENCY.search(line) for line in lines) and any(TRANSIENT.search(line) for line in lines) and not any(DETERMINISTIC.search(line) for line in lines)


def run(arguments, attempts=3, delay_seconds=10, executable='./gradlew'):
    for attempt in range(attempts):
        dependency = transient = deterministic = False
        with subprocess.Popen([executable, *arguments], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors='replace') as process:
            for line in process.stdout:
                print(line, end='', flush=True)
                dependency |= bool(DEPENDENCY.search(line))
                transient |= bool(TRANSIENT.search(line))
                deterministic |= bool(DETERMINISTIC.search(line))
            code = process.wait()
        if code == 0 or attempt + 1 == attempts or not (dependency and transient) or deterministic:
            return code
        pause = delay_seconds * (attempt + 1)
        print(f'Dependency service throttled or unavailable; retry {attempt + 2}/{attempts} after {pause}s. Gradle cache is retained.', flush=True)
        time.sleep(pause)
    raise AssertionError('Unreachable retry state')


if __name__ == '__main__':
    arguments = sys.argv[1:]
    if arguments[:1] == ['--']:
        arguments = arguments[1:]
    if not arguments:
        raise SystemExit('Pass Gradle tasks and options after --')
    raise SystemExit(run(arguments))
