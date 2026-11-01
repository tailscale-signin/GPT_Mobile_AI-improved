#!/usr/bin/env python3
"""Shared, allowlisted Android build phases. No shell evaluation or publication."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time


def command_for(runtime, variant, phase):
    if runtime not in ('legacy', 'geniex') or variant not in ('debug', 'release', 'nativeSmoke'):
        raise ValueError('Unknown runtime or build variant')
    suffix = variant[0].upper() + variant[1:]
    tasks = {'debug': ':app:assembleDebug', 'shrink': f':app:minify{suffix}WithR8',
             'package': f':app:assemble{suffix}', 'bundle': ':app:bundleRelease', 'ui': ':app:testDebugUnitTest'}
    if phase not in tasks or (phase == 'shrink' and variant == 'debug') or (phase == 'bundle' and variant != 'release'):
        raise ValueError('Incompatible build phase/variant')
    command = [f'-PgeniexRuntime={str(runtime == "geniex").lower()}', tasks[phase],
               '--no-daemon', '--max-workers=1', '--console=plain', '--stacktrace', '--profile',
               '-Pkotlin.compiler.execution.strategy=in-process']
    if variant == 'nativeSmoke':
        command.append('-PnativeMemorySmoke=true')
    if phase == 'bundle':
        command.append('-PenableAbiSplits=false')
    if phase == 'ui':
        command += ['--tests', '*RenderTest']
    # Release compiler/R8 memory before the separate packaging process. Bound the
    # common pool as well as Gradle workers; ApkFlinger uses both on cold builds.
    parallelism = 2 if phase in ('package', 'bundle', 'debug') else 1
    command.append('-Dorg.gradle.jvmargs=-Xmx4096m -XX:MaxMetaspaceSize=768m '
                   f'-Djava.util.concurrent.ForkJoinPool.common.parallelism={parallelism} -XX:+UseG1GC -Dfile.encoding=UTF-8')
    return command


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


def execute(runtime, variant, phase, output, run=subprocess.run):
    command = command_for(runtime, variant, phase)
    metrics = output.with_name(output.stem + '-metrics.json')
    checkpoint = {'schema_version': 1, 'commit_sha': os.getenv('GITHUB_SHA'),
                  'runtime': runtime, 'variant': variant, 'phase': phase, 'status': 'RUNNING',
                  'started_at_epoch_ms': int(time.time() * 1000), 'metrics': str(metrics),
                  'remote_state_changed': False}
    atomic_json(output, checkpoint)
    try:
        code = run([sys.executable, 'scripts/build_metrics.py', '--label', f'{runtime}-{variant}-{phase}',
                    '--output', str(metrics), '--', sys.executable, 'scripts/gradle_retry.py',
                    '--timeout-seconds', str(5160 if phase in ('package', 'bundle') else 1380), '--', *command]).returncode
        checkpoint.update(status='COMPLETED' if code == 0 else 'FAILED', exit_code=code)
        return code
    finally:
        checkpoint.update(finished_at_epoch_ms=int(time.time() * 1000))
        if checkpoint['status'] == 'RUNNING':
            checkpoint['status'] = 'INTERRUPTED'
        atomic_json(output, checkpoint)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', choices=('legacy', 'geniex'), default='legacy')
    parser.add_argument('--variant', choices=('debug', 'release', 'nativeSmoke'), default='debug')
    parser.add_argument('--phase', choices=('debug', 'shrink', 'package', 'bundle', 'ui'), required=True)
    parser.add_argument('--output', type=Path, default=Path('build/reports/pipeline/checkpoint.json'))
    parser.add_argument('--plan', action='store_true', help='Validate and print the exact command without running it')
    args = parser.parse_args()
    if args.plan:
        print(json.dumps({'gradle_arguments': command_for(args.runtime, args.variant, args.phase),
                          'remote_state_changed': False}))
        return 0
    return execute(args.runtime, args.variant, args.phase, args.output)


if __name__ == '__main__':
    raise SystemExit(main())
