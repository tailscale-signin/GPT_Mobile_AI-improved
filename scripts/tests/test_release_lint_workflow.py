import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / '.github/workflows/release-build.yml'


def lint_step():
    return next(step for step in re.split(r'(?m)^      - ', WORKFLOW.read_text())
                if step.startswith('name: Verify release compatibility and lint\n'))


def lint_command():
    step = lint_step()
    block = re.search(r'(?m)^        run: \|\n((?:          .*\n|\n)*)', step)
    if block:
        return textwrap.dedent(block[1])
    return re.search(r'(?m)^        run: (.+)$', step)[1]


class ReleaseLintWorkflowTest(unittest.TestCase):
    def execute(self, *, available_mib=7168, unknown=False, gradle_exit=0):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        scripts, binary, runtime = (root / name for name in ('scripts', 'bin', 'runtime'))
        for path in (scripts, binary, runtime):
            path.mkdir()
        for name in ('check_android_compile_memory.py', 'check_android_lint_memory.py', 'gradle_retry.py'):
            shutil.copy2(ROOT / 'scripts' / name, scripts / name)

        files = {
            '/proc/meminfo': f'MemTotal: {16_384 * 1024} kB\nMemAvailable: {available_mib * 1024} kB\nSwapTotal: 65536000 kB\nSwapFree: 65536000 kB',
            '/proc/self/cgroup': '0::/actions/job',
            '/proc/self/mountinfo': '31 24 0:25 / /sys/fs/cgroup rw - cgroup2 cgroup rw',
            '/sys/fs/cgroup/cgroup.controllers': 'memory cpu',
            '/sys/fs/cgroup/memory.current': '0',
            '/sys/fs/cgroup/actions/memory.max': 'max',
            '/sys/fs/cgroup/actions/memory.current': '0',
            '/sys/fs/cgroup/actions/job/memory.max': 'max',
            '/sys/fs/cgroup/actions/job/memory.current': '0',
        }
        if unknown:
            files.pop('/proc/self/cgroup')
        observations = root / 'observations.json'
        observations.write_text(json.dumps(files))
        # Run the actual guard and parser, substituting only proc/cgroup reads.
        # Every other Python command, including gradle_retry, executes unchanged.
        python = binary / 'python3'
        python.write_text(f'#!{sys.executable}\n' + textwrap.dedent('''\
            import json, os, sys
            from pathlib import Path
            script = Path(sys.argv[1]).resolve()
            if script.name == 'check_android_lint_memory.py':
                sys.path.insert(0, str(script.parent))
                import check_android_lint_memory as guard
                files = json.loads(Path(os.environ['LINT_TEST_OBSERVATIONS']).read_text())
                observe = guard.observe
                guard.observe = lambda: observe(files.get)
                raise SystemExit(guard.main())
            os.execv(sys.executable, [sys.executable, *sys.argv[1:]])
            '''))
        python.chmod(0o755)
        gradle = root / 'gradlew'
        gradle.write_text(f'#!{sys.executable}\n' + textwrap.dedent('''\
            import json, os, sys
            from pathlib import Path
            Path(os.environ['LINT_TEST_INVOCATION']).write_text(json.dumps(sys.argv[1:]))
            code = int(os.environ['LINT_TEST_GRADLE_EXIT'])
            print('Lint found 1 errors' if code else 'Controlled Gradle child completed')
            raise SystemExit(code)
            '''))
        gradle.chmod(0o755)
        invocation = root / 'gradle-invocation.json'
        environment = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ['PATH'],
                           RUNNER_TEMP=str(runtime), LINT_TEST_OBSERVATIONS=str(observations),
                           LINT_TEST_INVOCATION=str(invocation), LINT_TEST_GRADLE_EXIT=str(gradle_exit))
        result = subprocess.run(['bash', '-e', '-o', 'pipefail', '-c', lint_command()],
                                cwd=root, env=environment, capture_output=True, text=True, timeout=20)
        return result, root, invocation

    def test_unknown_or_insufficient_headroom_prevents_gradle_launch(self):
        for options, expected in (({'available_mib': 7167}, 'INSUFFICIENT'), ({'unknown': True}, 'UNKNOWN')):
            with self.subTest(expected=expected):
                result, root, invocation = self.execute(**options)
                self.assertFalse(invocation.exists(), 'Refused admission must prevent the Gradle child from starting')
                self.assertEqual(1, result.returncode, result.stdout + result.stderr)
                receipt = json.loads((root / 'release-lint-memory.log').read_text())
                self.assertEqual(expected, receipt['status'])
                self.assertFalse((root / 'release-lint.log').exists())

    def test_admitted_budget_reaches_both_tasks_and_retains_phase_output(self):
        result, root, invocation = self.execute()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        arguments = json.loads(invocation.read_text())
        self.assertEqual([':app:checkReleaseAarMetadata', ':app:lintRelease'], arguments[:2])
        for required in ('--no-daemon', '--max-workers=1', '--console=plain'):
            self.assertIn(required, arguments)
        jvm = [argument for argument in arguments if argument.startswith('-Dorg.gradle.jvmargs=')]
        self.assertEqual(1, len(jvm))
        self.assertEqual(['4096'], re.findall(r'-Xmx(\d+)m', jvm[0]))
        self.assertEqual(['1024'], re.findall(r'-XX:MaxMetaspaceSize=(\d+)m', jvm[0]))
        self.assertIn(f'-Xlog:gc:file={root}/runtime/release-lint-gc.log:time,level,tags:filecount=2,filesize=8M', jvm[0])
        receipt = json.loads((root / 'release-lint-memory.log').read_text())
        self.assertEqual('SUFFICIENT', receipt['status'])
        self.assertEqual(receipt['requiredBytes'], receipt['availableBytes'])
        self.assertIn('Controlled Gradle child completed', (root / 'release-lint.log').read_text())

    def test_gradle_failure_propagates_through_tee_without_retry_or_success(self):
        result, root, invocation = self.execute(gradle_exit=7)
        self.assertEqual(7, result.returncode, result.stdout + result.stderr)
        self.assertTrue(invocation.exists())
        log = (root / 'release-lint.log').read_text()
        self.assertIn('Lint found 1 errors', log)
        self.assertNotIn('retry 2/', log)

    def test_deadline_cleanup_headroom_and_failure_diagnostics_are_retained(self):
        step = lint_step()
        outer_minutes = int(re.search(r'(?m)^        timeout-minutes: (\d+)$', step)[1])
        inner_seconds = int(re.search(r'--timeout-seconds (\d+)', lint_command())[1])
        self.assertEqual(45, outer_minutes)
        self.assertEqual(2640, inner_seconds)
        self.assertGreater(outer_minutes * 60 - inner_seconds, 15)
        for bypass in ('continue-on-error:', '|| true', '--continue', ' -x '):
            self.assertNotIn(bypass, step)
        uploads = [candidate for candidate in re.split(r'(?m)^      - ', WORKFLOW.read_text())
                   if 'actions/upload-artifact@' in candidate and 'if: always()' in candidate]
        self.assertTrue(any('release-lint-gc.log*' in candidate and 'release-*.log' in candidate for candidate in uploads))


if __name__ == '__main__':
    unittest.main()
