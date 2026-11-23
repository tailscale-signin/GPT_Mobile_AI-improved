import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import android_pipeline as pipeline


class IsolatedProductionCompileTest(unittest.TestCase):
    def setUp(self):
        self.memory = patch.object(pipeline.compile_memory, 'observe', return_value={'status': 'SUFFICIENT'})
        self.observe = self.memory.start()
        self.addCleanup(self.memory.stop)

    def test_every_runtime_and_variant_releases_both_generator_processes_before_compilation(self):
        for runtime in ('legacy', 'geniex'):
            for variant in ('debug', 'release', 'nativeSmoke'):
                with self.subTest(runtime=runtime, variant=variant), tempfile.TemporaryDirectory() as directory:
                    output = Path(directory) / 'compile.json'
                    run = Mock(return_value=subprocess.CompletedProcess([], 0))
                    self.assertEqual(0, pipeline.execute(runtime, variant, 'compile', output, run))
                    commands = [call.args[0] for call in run.call_args_list]
                    suffix = variant[0].upper() + variant[1:]
                    self.assertEqual(
                        [[f':app:ksp{suffix}Kotlin'], [f':app:kapt{suffix}Kotlin'],
                         [f':app:compile{suffix}Kotlin', f':app:compile{suffix}JavaWithJavac']],
                        [[arg for arg in command if arg.startswith(':app:')] for command in commands],
                    )
                    for command, heap in zip(commands, (4096, 4096, 6144)):
                        self.assertEqual(1, command.count('--no-daemon'))
                        self.assertEqual(1, command.count('--max-workers=1'))
                        self.assertIn('-Pkotlin.compiler.execution.strategy=in-process', command)
                        self.assertIn(f'-PgeniexRuntime={str(runtime == "geniex").lower()}', command)
                        self.assertEqual(variant == 'nativeSmoke', '-PnativeMemorySmoke=true' in command)
                        jvm = [arg for arg in command if arg.startswith('-Dorg.gradle.jvmargs=')]
                        self.assertEqual(1, len(jvm))
                        self.assertIn(f'-Xmx{heap}m ', jvm[0])
                        self.assertIn('-XX:MaxMetaspaceSize=768m ', jvm[0])
                        self.assertNotIn('-x', command)
                        self.assertNotIn('--continue', command)
                    self.assertTrue(any('-Xlog:gc:file=' in arg and 'filecount=2,filesize=8M' in arg
                                        for arg in commands[2]))
                    self.assertFalse(any('-Xlog:' in arg for command in commands[:2] for arg in command))
                    state = json.loads(output.read_text())
                    self.assertEqual('COMPLETED', state['status'])
                    self.assertFalse(state['remote_state_changed'])
                    self.assertEqual({'ksp', 'kapt'}, set(state['generation_checkpoints']))
                    for checkpoint in state['generation_checkpoints'].values():
                        self.assertEqual('COMPLETED', json.loads(Path(checkpoint).read_text())['status'])
                    self.assertEqual('SUFFICIENT', json.loads(Path(state['memory_preflight']).read_text())['status'])

    def test_failed_generation_stops_before_any_later_generator_or_compiler(self):
        for failed_index, failed_phase in enumerate(('ksp', 'kapt')):
            with self.subTest(phase=failed_phase), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'compile.json'
                outcomes = [subprocess.CompletedProcess([], 0)] * failed_index
                outcomes.append(subprocess.CompletedProcess([], 29))
                run = Mock(side_effect=outcomes)
                self.assertEqual(29, pipeline.execute('legacy', 'release', 'compile', output, run))
                self.assertEqual(failed_index + 1, run.call_count)
                state = json.loads(output.read_text())
                self.assertEqual('FAILED', state['status'])
                self.assertEqual(failed_phase, state['failed_phase'])
                self.assertEqual(29, state['exit_code'])
                self.assertFalse(state['remote_state_changed'])
                child = json.loads(Path(state['generation_checkpoints'][failed_phase]).read_text())
                self.assertEqual('FAILED', child['status'])
                self.assertEqual(29, child['exit_code'])

    def test_compiler_failure_retains_successful_generation_and_original_exit_code(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'compile.json'
            run = Mock(side_effect=[subprocess.CompletedProcess([], code) for code in (0, 0, 37)])
            self.assertEqual(37, pipeline.execute('geniex', 'nativeSmoke', 'compile', output, run))
            self.assertEqual(3, run.call_count)
            state = json.loads(output.read_text())
            self.assertEqual('FAILED', state['status'])
            self.assertEqual(37, state['exit_code'])
            for checkpoint in state['generation_checkpoints'].values():
                self.assertEqual('COMPLETED', json.loads(Path(checkpoint).read_text())['status'])

    def test_interruption_in_any_process_does_not_start_the_next_or_claim_completion(self):
        for interrupted_index in range(3):
            with self.subTest(index=interrupted_index), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'compile.json'
                outcomes = [subprocess.CompletedProcess([], 0)] * interrupted_index + [KeyboardInterrupt()]
                run = Mock(side_effect=outcomes)
                with self.assertRaises(KeyboardInterrupt):
                    pipeline.execute('legacy', 'debug', 'compile', output, run)
                self.assertEqual(interrupted_index + 1, run.call_count)
                state = json.loads(output.read_text())
                self.assertEqual('INTERRUPTED', state['status'])
                self.assertNotIn('exit_code', state)
                self.assertFalse(state['remote_state_changed'])
                if interrupted_index < 2:
                    phase = ('ksp', 'kapt')[interrupted_index]
                    child = json.loads(Path(state['generation_checkpoints'][phase]).read_text())
                    self.assertEqual('INTERRUPTED', child['status'])

    def test_production_allowance_does_not_change_other_phase_limits(self):
        cases = [('debug', 'debug'), ('debug', 'ui'), ('release', 'shrink'),
                 ('release', 'lint'), ('release', 'package'), ('release', 'bundle'),
                 ('nativeSmoke', 'shrink'), ('nativeSmoke', 'lint'), ('nativeSmoke', 'package')]
        for runtime in ('legacy', 'geniex'):
            for variant, phase in cases:
                with self.subTest(runtime=runtime, variant=variant, phase=phase):
                    command = pipeline.command_for(runtime, variant, phase)
                    jvm = next(arg for arg in command if arg.startswith('-Dorg.gradle.jvmargs='))
                    self.assertIn('-Xmx4096m ', jvm)
                    self.assertIn('-XX:MaxMetaspaceSize=768m ', jvm)
                    self.assertNotIn('-Xmx6144m', jvm)
                    parallelism = 2 if phase in ('package', 'bundle', 'debug') else 1
                    self.assertIn(f'ForkJoinPool.common.parallelism={parallelism} ', jvm)

    def test_insufficient_or_unknown_headroom_stops_before_compilation_with_retained_receipt(self):
        for status in ('INSUFFICIENT', 'UNKNOWN'):
            with self.subTest(status=status), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / 'compile.json'
                self.observe.return_value = {'status': status, 'swapCountsAsHeadroom': False}
                run = Mock(return_value=subprocess.CompletedProcess([], 0))
                self.assertEqual(1, pipeline.execute('legacy', 'release', 'compile', output, run))
                self.assertEqual(2, run.call_count)
                state = json.loads(output.read_text())
                self.assertEqual('FAILED', state['status'])
                self.assertEqual('memory_preflight', state['failed_phase'])
                self.assertEqual(1, state['exit_code'])
                self.assertEqual(self.observe.return_value, json.loads(Path(state['memory_preflight']).read_text()))
                self.assertFalse(state['remote_state_changed'])
                self.assertNotIn('gc_log', state)

    def test_compile_plan_reports_ordered_prerequisites_without_executing_them(self):
        result = subprocess.run(
            [sys.executable, str(Path(pipeline.__file__).resolve()), '--runtime', 'geniex',
             '--variant', 'release', '--phase', 'compile', '--plan'],
            check=True, capture_output=True, text=True,
        )
        plan = json.loads(result.stdout)
        self.assertFalse(plan['remote_state_changed'])
        self.assertEqual(2, len(plan['prerequisite_gradle_arguments']))
        self.assertIn(':app:kspReleaseKotlin', plan['prerequisite_gradle_arguments'][0])
        self.assertIn(':app:kaptReleaseKotlin', plan['prerequisite_gradle_arguments'][1])
        self.assertIn(':app:compileReleaseKotlin', plan['gradle_arguments'])
        self.assertIn(':app:compileReleaseJavaWithJavac', plan['gradle_arguments'])


if __name__ == '__main__':
    unittest.main()
