#!/usr/bin/env python3
"""Shared, allowlisted Android build phases. No shell evaluation or publication."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time

import check_android_compile_memory as compile_memory


def command_for(runtime, variant, phase):
    if runtime not in ('legacy', 'geniex') or variant not in ('debug', 'release', 'nativeSmoke'):
        raise ValueError('Unknown runtime or build variant')
    suffix = variant[0].upper() + variant[1:]
    tasks = {'debug': [':app:assembleDebug'], 'shrink': [f':app:minify{suffix}WithR8'], 'lint': [f':app:lintVital{suffix}'],
             'package': [f':app:assemble{suffix}'], 'bundle': [':app:bundleRelease'], 'ui': [':app:testDebugUnitTest'],
             'ksp': [f':app:ksp{suffix}Kotlin'], 'kapt': [f':app:kapt{suffix}Kotlin'],
             'compile': [f':app:compile{suffix}Kotlin', f':app:compile{suffix}JavaWithJavac']}
    if phase not in tasks or (phase in ('shrink', 'lint') and variant == 'debug') or (phase == 'bundle' and variant != 'release'):
        raise ValueError('Incompatible build phase/variant')
    command = [f'-PgeniexRuntime={str(runtime == "geniex").lower()}', *tasks[phase],
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
    # Fresh production compilation exhausted 4 GiB after code generation on the
    # combined source. Keep every other phase's existing bounded heap unchanged.
    heap = 6144 if phase == 'compile' else 4096
    command.append(f'-Dorg.gradle.jvmargs=-Xmx{heap}m -XX:MaxMetaspaceSize=768m '
                   f'-Djava.util.concurrent.ForkJoinPool.common.parallelism={parallelism} -XX:+UseG1GC -Dfile.encoding=UTF-8')
    return command


def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


def production_gc_argument(output):
    path = output.with_name(output.stem + '-gc.log').resolve()
    return f' "-Xlog:gc:file={path}:time,level,tags:filecount=2,filesize=8M"'


def execute(runtime, variant, phase, output, run=subprocess.run):
    command = command_for(runtime, variant, phase)
    metrics = output.with_name(output.stem + '-metrics.json')
    checkpoint = {'schema_version': 1, 'commit_sha': os.getenv('GITHUB_SHA'),
                  'runtime': runtime, 'variant': variant, 'phase': phase, 'status': 'RUNNING',
                  'started_at_epoch_ms': int(time.time() * 1000), 'metrics': str(metrics),
                  'remote_state_changed': False}
    atomic_json(output, checkpoint)
    try:
        if phase == 'compile':
            # A cold runner must release each generator's heap before compiling
            # production sources. Each prerequisite retains its own checkpoint.
            checkpoint['generation_checkpoints'] = {}
            for generation in ('ksp', 'kapt'):
                generation_output = output.with_name(output.stem + f'-{generation}.json')
                checkpoint['generation_checkpoints'][generation] = str(generation_output)
                atomic_json(output, checkpoint)
                code = execute(runtime, variant, generation, generation_output, run)
                if code != 0:
                    checkpoint.update(status='FAILED', exit_code=code, failed_phase=generation)
                    return code
            # Observe headroom after generator processes have exited, immediately
            # before the fixed production allowance. Preserve a bounded receipt.
            memory_output = output.with_name(output.stem + '-memory.json')
            memory = compile_memory.observe()
            atomic_json(memory_output, memory)
            checkpoint['memory_preflight'] = str(memory_output)
            atomic_json(output, checkpoint)
            print(json.dumps(memory, sort_keys=True), flush=True)
            if memory['status'] != 'SUFFICIENT':
                checkpoint.update(status='FAILED', exit_code=1, failed_phase='memory_preflight')
                return 1
            gc_log = output.with_name(output.stem + '-gc.log').resolve()
            checkpoint['gc_log'] = str(gc_log)
            atomic_json(output, checkpoint)
            command[-1] += production_gc_argument(output)
        if phase == 'package' and variant in ('release', 'nativeSmoke'):
            # A cold assemble runs lint analysis before ApkFlinger in the same JVM.
            # Like R8, lint retains its heap until that process exits. Complete the
            # mandatory lint tasks first, then package with their outputs up to date.
            lint_output = output.with_name(output.stem + '-lint.json')
            checkpoint['lint_checkpoint'] = str(lint_output)
            atomic_json(output, checkpoint)
            code = execute(runtime, variant, 'lint', lint_output, run)
            if code != 0:
                checkpoint.update(status='FAILED', exit_code=code, failed_phase='lint')
                return code
        code = run([sys.executable, 'scripts/build_metrics.py', '--label', f'{runtime}-{variant}-{phase}',
                    '--output', str(metrics), '--', sys.executable, 'scripts/gradle_retry.py',
                    '--timeout-seconds', str(5160 if phase in ('package', 'bundle') else
                                             540 if phase == 'ksp' else 1140 if phase == 'kapt' else 1380),
                    '--', *command]).returncode
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
    parser.add_argument('--phase', choices=('debug', 'shrink', 'lint', 'package', 'bundle', 'ui', 'compile'), required=True)
    parser.add_argument('--output', type=Path, default=Path('build/reports/pipeline/checkpoint.json'))
    parser.add_argument('--plan', action='store_true', help='Validate and print the exact command without running it')
    args = parser.parse_args()
    if args.plan:
        command = command_for(args.runtime, args.variant, args.phase)
        if args.phase == 'compile':
            command[-1] += production_gc_argument(args.output)
        print(json.dumps({'gradle_arguments': command,
                          'prerequisite_gradle_arguments': [command_for(args.runtime, args.variant, stage)
                                                            for stage in ('ksp', 'kapt')]
                          if args.phase == 'compile' else [command_for(args.runtime, args.variant, 'lint')]
                          if args.phase == 'package' and args.variant in ('release', 'nativeSmoke') else [],
                          'remote_state_changed': False}))
        return 0
    return execute(args.runtime, args.variant, args.phase, args.output)


if __name__ == '__main__':
    raise SystemExit(main())
