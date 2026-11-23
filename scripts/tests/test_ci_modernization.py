import importlib.util
import json
import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]

def module(name):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / f'{name}.py')
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class RequiredGateTest(unittest.TestCase):
    def test_missing_cancelled_and_skipped_are_not_green(self):
        gate = module('check_required_verification')
        complete = {name: {'result': 'success'} for name in gate.EXPECTED_JOBS}
        self.assertEqual([], gate.failures(complete))
        for state in ('failure', 'cancelled', 'skipped', None):
            self.assertTrue(gate.failures({**complete, 'validate': {'result': state}}))
        self.assertTrue(gate.failures({}))
        self.assertTrue(gate.failures(None))
        self.assertTrue(gate.failures({k: v for k, v in complete.items() if k != 'contracts'}))


class DiagnosticsTest(unittest.TestCase):
    def test_first_failure_is_preserved_and_repeats_grouped(self):
        digest = module('collect_ci_diagnostics')
        report = digest.summarize('noise\ne: bad symbol\ne: bad symbol\nFAILURE: task failed\n')
        self.assertEqual('e: bad symbol', report['first_actionable_failure'])
        self.assertEqual(2, report['groups'][0]['count'])

    def test_credentials_are_never_emitted(self):
        digest = module('collect_ci_diagnostics')
        report = digest.summarize('error: Authorization: Bearer private-value\nerror: token=private-token')
        self.assertNotIn('private-value', str(report))
        self.assertNotIn('private-token', str(report))

    def test_native_worker_crash_precedes_generic_gradle_failure(self):
        digest = module('collect_ci_diagnostics')
        signal = '#  SIGSEGV (0xb) at pc=0x00007f59f4e22fcf, pid=6649, tid=6718'
        frame = '# C  [librobolectric-nativeruntime.so+0x822fcf] CanvasContext::prepareTree token=private-value'
        report = digest.summarize(
            '# A fatal error has been detected by the Java Runtime Environment:\n'
            + signal + '\n# Problematic frame:\n' + frame + '\n'
            + '7733 tests run, 7727 passed, 6 skipped, 0 failed.\n'
            + 'FAILURE: Build failed with an exception.\n')
        self.assertEqual(signal, report['first_actionable_failure'])
        self.assertIn('CanvasContext::prepareTree', report['groups'][1]['message'])
        self.assertNotIn('private-value', str(report))
        self.assertEqual('[REDACTED]', report['groups'][1]['message'].split()[-1])

    def test_abnormal_test_worker_exit_is_actionable_without_assertion_failure(self):
        digest = module('collect_ci_diagnostics')
        failure = "Process 'Gradle Test Executor 17' finished with non-zero exit value 134"
        report = digest.summarize('0 assertion failures\n' + failure + '\n')
        self.assertEqual(failure, report['first_actionable_failure'])
        self.assertIsNone(digest.summarize('7733 tests completed, 0 failed\n')['first_actionable_failure'])

    def test_high_confidence_scan_reports_lines_only(self):
        scan = module('scan_secrets')
        self.assertEqual([2], scan.findings(b'line1\n' + b'ghp_' + b'A' * 36))
        self.assertEqual([], scan.findings(b'no secrets here'))

    def test_github_workspace_appearance_preferences_remain_off_main_thread(self):
        source = (SCRIPTS.parent / 'app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/setting/GitHubWorkspaceScreen.kt').read_text()
        self.assertNotIn('remember(workspaceContext) { workspaceContext.getSharedPreferences', source)
        self.assertIn('withContext(Dispatchers.IO)', source)
        self.assertIn('scope.launch(Dispatchers.IO)', source)
        self.assertIn('if (!fadePreferenceTouched)', source)
        self.assertIn('fadePreferenceTouched = true', source)


class WorkflowPolicyTest(unittest.TestCase):
    def test_device_acceptance_releases_codegen_heaps_before_each_compile_phase(self):
        import re

        workflow = (SCRIPTS.parent / '.github/workflows/release-0950-acceptance.yml').read_text()
        steps = re.split(r'(?m)^      - ', workflow.split('\n    steps:\n', 1)[1])[1:]
        expected = (
            (':app:kspDebugKotlin',),
            (':app:kaptDebugKotlin',),
            (':app:compileDebugKotlin', ':app:compileDebugJavaWithJavac'),
            (':app:compileDebugAndroidTestKotlin', ':app:compileDebugAndroidTestJavaWithJavac'),
        )
        positions = []
        for tasks in expected:
            matches = [
                (index, step) for index, step in enumerate(steps)
                if tasks[0] in re.findall(r':app:[A-Za-z]+', step)
            ]
            self.assertEqual(1, len(matches), tasks)
            index, step = matches[0]
            positions.append(index)
            self.assertEqual(list(tasks), re.findall(r':app:[A-Za-z]+', step))
            self.assertEqual(1, step.count('python3 scripts/gradle_retry.py'))
            heap = '-Xmx6144m' if tasks[0] == ':app:compileDebugKotlin' else '-Xmx4096m'
            for required in ('--no-daemon', '--max-workers=1', heap,
                             '-XX:MaxMetaspaceSize=768m', '-Pandroid.injected.build.abi=x86_64',
                             'set -o pipefail', '2>&1 | tee acceptance-'):
                self.assertIn(required, step)
            minutes = int(re.search(r'(?m)^        timeout-minutes: (\d+)$', step)[1])
            seconds = int(re.search(r'--timeout-seconds (\d+)', step)[1])
            self.assertGreater(seconds, 0)
            self.assertLessEqual(seconds, minutes * 60 - 60)
            self.assertNotIn('continue-on-error:', step)
        self.assertEqual(sorted(set(positions)), positions)
        package = next(index for index, step in enumerate(steps) if ':app:assembleDebugAndroidTest' in step)
        artifact_check = next(index for index, step in enumerate(steps) if 'select_android_test_apk.py' in step)
        emulator = next(index for index, step in enumerate(steps) if 'uses: ./build/emulator-action-upstream' in step)
        self.assertLess(positions[-1], package)
        self.assertLess(package, artifact_check)
        self.assertLess(artifact_check, emulator)
        self.assertIn('    timeout-minutes: 60\n', workflow)
        self.assertIn('          - api: 34\n', workflow)
        self.assertIn('          - api: 36\n', workflow)
        self.assertIn(':app:assembleDebug :app:assembleDebugAndroidTest', steps[package])
        self.assertIn('-Djava.util.concurrent.ForkJoinPool.common.parallelism=1', steps[package])
        self.assertIn('--application-id dev.melo.gptmobile.improved --abi x86_64', steps[artifact_check])
        self.assertIn('--application-id dev.melo.gptmobile.improved.test', steps[artifact_check])
        self.assertIn('bash scripts/run_release0950_acceptance.sh', steps[emulator])
        self.assertIn('            app/build/acceptance-0950/\n', workflow)
        self.assertIn('            acceptance-*.log\n', workflow)

    def test_floating_action_rejected_and_sha_accepted(self):
        policy = module('check_ci_policy')
        self.assertTrue(policy.check_text('uses: example/action@v4', '.github/actions/example/action.yml'))
        self.assertFalse(policy.check_text('uses: example/action@' + 'a' * 40, '.github/actions/example/action.yml'))

    def test_runner_context_requires_step_scope_for_environment_values(self):
        policy = module('check_ci_policy')
        prefix = 'permissions:\n  contents: read\nconcurrency:\n  group: test\njobs:\n  smoke:\n    runs-on: ubuntu-latest\n    timeout-minutes: 20\n'
        step = '    steps:\n      - run: echo ready\n'
        for environment in (
            '    env:\n      MODEL_DIR: ${{ runner.temp }}/models\n',
            "    env: {MODEL_DIR: '${{runner.temp}}/models'}\n",
        ):
            with self.subTest(environment=environment):
                self.assertTrue(policy.check_text(prefix + environment + step, '.github/workflows/speech.yml'))
        allowed = prefix + '    env:\n      ENGINE: ${{ matrix.engine }}\n' + step + '        env:\n          MODEL_DIR: ${{ runner.temp }}/models\n'
        self.assertEqual([], policy.check_text(allowed, '.github/workflows/speech.yml'))

    def test_candidate_ruleset_keeps_review_and_security_gates(self):
        policy = module('check_ci_policy')
        ruleset = json.loads((SCRIPTS.parent / 'docs/ci-cd/main-ruleset.json').read_text())
        self.assertEqual([], policy.check_ruleset(ruleset))
        pull_request = next(rule for rule in ruleset['rules'] if rule['type'] == 'pull_request')
        weakened = json.loads(json.dumps(ruleset))
        weakened_pull_request = next(rule for rule in weakened['rules'] if rule['type'] == 'pull_request')
        weakened_pull_request['parameters'].update(
            required_approving_review_count=0,
            dismiss_stale_reviews_on_push=False,
            require_last_push_approval=False,
            required_review_thread_resolution=False,
        )
        self.assertGreaterEqual(len(policy.check_ruleset(weakened)), 4)


    def test_diagnostic_lint_deadline_preserves_full_analysis_and_artifact_budget(self):
        # Run 43968609247 passed unit tests and APK, but lint analysis was
        # killed by its own 2040-second deadline without a lint finding.
        import re

        workflow = (SCRIPTS.parent / '.github/workflows/audit-workspace.yml').read_text()
        android = workflow.split('\n  android:\n', 1)[1]
        job_minutes = int(re.search(r'(?m)^    timeout-minutes: (\d+)$', android).group(1))
        lint = android.split('\n      - name: Android lint\n', 1)[1].split('\n      - uses:', 1)[0]
        step_minutes = int(re.search(r'(?m)^        timeout-minutes: (\d+)$', lint).group(1))
        process_seconds = int(re.search(r'--timeout-seconds (\d+)', lint).group(1))

        self.assertGreaterEqual(step_minutes, 65)
        self.assertGreaterEqual(process_seconds, 3840)
        self.assertLessEqual(process_seconds, step_minutes * 60 - 60)
        # Lint began 57 minutes after job start in the observed failed run.
        self.assertLessEqual(57 + step_minutes + 10, job_minutes)
        self.assertIn(':app:lintDebug', lint)
        self.assertIn('set -o pipefail', lint)
        self.assertNotIn('continue-on-error: true', lint)


    def test_pr_unit_coverage_budget_preserves_strict_gates(self):
        # Run 43988347373 exhausted the old 25-minute unit step mid-suite.
        workflow = (SCRIPTS.parent / '.github/workflows/pr-validation.yml').read_text()
        validate = workflow.split('  validate:', 1)[1].split('  android_lint:', 1)[0]
        unit = validate.split(
            '      - name: Run Unit Tests & JaCoCo Coverage Report', 1
        )[1].split('      - name: Collect named audit qualification', 1)[0]
        job_minutes = next(
            int(line.split(':', 1)[1].strip())
            for line in validate.splitlines()
            if line.startswith('    timeout-minutes: ')
        )
        step_minutes = next(
            int(line.split(':', 1)[1].strip())
            for line in unit.splitlines()
            if line.startswith('        timeout-minutes: ')
        )
        self.assertGreaterEqual(step_minutes, 55)
        self.assertGreaterEqual(job_minutes, step_minutes + 60)
        for required in (':core-domain:jvmTest', ':app:testDebugUnitTest',
                         ':app:jacocoTestReport', '--rerun', 'check_jacoco_coverage.py',
                         'set -o pipefail', 'exit 1'):
            self.assertIn(required, unit)
        self.assertNotIn('continue-on-error: true', unit)
        self.assertIn('fail_on_failure: true', validate)
        self.assertIn('require_tests: true', validate)


class WorkflowHealthTest(unittest.TestCase):
    def test_same_named_workflows_keep_separate_identity(self):
        health = module('collect_workflow_health')
        result = health.summarize([{'id': 1, 'workflow_id': 10, 'name': 'build'},
                                   {'id': 2, 'workflow_id': 20, 'name': 'build'}])
        self.assertEqual([10, 20], [row['workflow_id'] for row in result])
        self.assertEqual([1, 1], [row['runs'] for row in result])

    def test_history_resume_deduplicates_without_claiming_partial_history_is_complete(self):
        health = module('collect_workflow_health')
        rows = [{'id': n, 'name': 'build', 'status': 'completed', 'conclusion': 'success'} for n in range(150, 0, -1)]
        pages = {1: {'total_count': 150, 'workflow_runs': rows[:100]},
                 2: {'total_count': 150, 'workflow_runs': rows[99:]}}
        partial = health.collect(pages.__getitem__, 'o/r', 1)
        self.assertFalse(partial['history_complete'])
        self.assertEqual(2, partial['next_page'])
        final = health.collect(pages.__getitem__, 'o/r', 1, partial)
        self.assertTrue(final['history_complete'])
        self.assertEqual(150, final['unique_runs'])
        self.assertEqual(150, final['metrics'][0]['successes'])

    def test_missing_runs_and_wrong_repository_never_report_complete(self):
        health = module('collect_workflow_health')
        partial = health.collect(lambda page: {'total_count': 20, 'workflow_runs': [{'id': 1, 'name': 'build'}]}, 'o/r')
        self.assertTrue(partial['traversal_complete'])
        self.assertFalse(partial['history_complete'])
        with self.assertRaises(ValueError):
            health.collect(lambda page: self.fail('must not fetch'), 'o/other', checkpoint=partial)

    def test_page_failure_preserves_the_last_successful_checkpoint(self):
        import copy
        health = module('collect_workflow_health')
        saved = []
        def fetch(page):
            if page == 2:
                raise OSError('rate limited')
            return {'total_count': 200, 'workflow_runs': [{'id': n, 'name': 'build'} for n in range(200, 100, -1)]}
        with self.assertRaises(OSError):
            health.collect(fetch, 'o/r', save=lambda state: saved.append(copy.deepcopy(state)))
        self.assertEqual(2, saved[-1]['next_page'])
        self.assertEqual(100, saved[-1]['unique_runs'])

    def test_pending_and_cancelled_are_not_successes(self):
        health = module('collect_workflow_health')
        rows = [{'name': 'build', 'status': 'in_progress'},
                {'name': 'build', 'status': 'completed', 'conclusion': 'cancelled'},
                {'name': 'build', 'status': 'completed', 'conclusion': 'success',
                 'run_started_at': '2026-10-28T10:00:00Z', 'updated_at': '2026-10-28T10:02:00Z'}]
        result = health.summarize(rows)[0]
        self.assertEqual(1, result['successes'])
        self.assertEqual(1, result['cancelled'])
        self.assertEqual(120, result['median_observed_seconds'])


class DependencyReviewTest(unittest.TestCase):
    def test_manifest_selection_includes_deletions_and_nested_dependency_files(self):
        review = module('check_dependency_review')
        for path in ('gradle/libs.versions.toml', 'gateway/requirements.txt', 'app/build.gradle.kts', 'nested/package-lock.json', 'gradle/wrapper/gradle-wrapper.properties'):
            self.assertTrue(review.dependency_manifest(path), path)
        self.assertFalse(review.dependency_manifest('docs/README.md'))
        self.assertFalse(review.dependency_manifest('app/src/Test.kt'))


    def test_version_only_edit_requires_identical_remaining_build_script(self):
        review = module('check_dependency_review')
        before = '        versionCode = 131\n        versionName = "0.9.63.0"\ndependencies { implementation("safe:lib:1") }\n'
        after = before.replace('131', '132').replace('0.9.63.0', '0.9.64.0')
        self.assertTrue(review.only_app_version_changed('app/build.gradle.kts', before, after))
        for changed in (after.replace('safe:lib:1', 'safe:lib:2'), after + 'apply(from="new.gradle")\n', after.replace('132', 'computeVersion()'), after + '        versionCode = 999\n'):
            self.assertFalse(review.only_app_version_changed('app/build.gradle.kts', before, changed))
        self.assertFalse(review.only_app_version_changed('other/build.gradle.kts', before, after))
        self.assertFalse(review.only_app_version_changed('app/build.gradle.kts', '', after))
        self.assertFalse(review.only_app_version_changed('app/build.gradle.kts', before, ''))

    def test_removed_advisories_do_not_block_but_added_high_risk_does(self):
        review = module('check_dependency_review')
        rows = [{'change_type': 'removed', 'vulnerabilities': [{'severity': 'critical'}]},
                {'change_type': 'added', 'name': 'library', 'vulnerabilities': [
                    {'severity': 'low'}, {'severity': 'high', 'advisory_ghsa_id': 'GHSA-example'}]}]
        self.assertEqual(1, len(review.findings(rows)))
        self.assertEqual('GHSA-example', review.findings(rows)[0]['advisory'])
        with self.assertRaises(ValueError):
            review.findings({'error': 'unavailable'})


if __name__ == '__main__':
    unittest.main()
