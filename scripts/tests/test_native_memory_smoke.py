import importlib.util
from pathlib import Path
import tempfile
import unittest
import json

from scripts import check_release_scope as release_scope

spec = importlib.util.spec_from_file_location("native_smoke", Path(__file__).resolve().parents[1] / "check_native_memory_smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class NativeMemorySmokeReportTest(unittest.TestCase):
    def check_xml(self, content):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, "TEST-emulator.xml").write_text(content)
            smoke.check_reports(directory)

    def test_zero_tests_or_other_tests_cannot_pass(self):
        for content in ['<testsuite tests="0"/>', '<testsuite><testcase classname="Other" name="test"/></testsuite>']:
            with self.subTest(content=content), self.assertRaisesRegex(ValueError, "did not execute"):
                self.check_xml(content)

    def test_missing_report_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory, self.assertRaisesRegex(ValueError, "no JUnit XML"):
            smoke.check_reports(directory)

    def test_actual_encoder_case_passes_and_failures_skips_do_not(self):
        for outcome in ["", "<failure/>", "<error/>", "<skipped/>"]:
            content = f'<testsuite><testcase classname="{smoke.TEST_CLASS}" name="{smoke.TEST_METHOD}">{outcome}</testcase></testsuite>'
            if outcome:
                with self.subTest(outcome=outcome), self.assertRaises(ValueError):
                    self.check_xml(content)
            else:
                self.check_xml(content)


class NativeMemoryInstrumentationTest(unittest.TestCase):
    START = f"""INSTRUMENTATION_STATUS: class={smoke.TEST_CLASS}
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: test={smoke.TEST_METHOD}
INSTRUMENTATION_STATUS_CODE: 1
"""
    SUCCESS = f"""INSTRUMENTATION_STATUS: class={smoke.TEST_CLASS}
INSTRUMENTATION_STATUS: test={smoke.TEST_METHOD}
INSTRUMENTATION_STATUS_CODE: 0
"""
    FINISH = "INSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n"

    def check_output(self, content, **kwargs):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "instrumentation.txt")
            path.write_text(content)
            smoke.check_instrumentation(path, **kwargs)

    def test_optional_gpu_assumption_keeps_cpu_success_required(self):
        optional = ("GgufTest", "gpuTest")
        gpu = "INSTRUMENTATION_STATUS: class=GgufTest\nINSTRUMENTATION_STATUS: test=gpuTest\nINSTRUMENTATION_STATUS_CODE: -4\n"
        self.check_output(self.START + self.SUCCESS + gpu + self.FINISH, optional_assumptions={optional})
        with self.assertRaisesRegex(ValueError, "did not execute"):
            self.check_output(gpu + self.FINISH, optional_assumptions={optional})
        for code in [-1, -2, -3]:
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, "failed or was skipped"):
                self.check_output(self.START + self.SUCCESS + gpu.replace("CODE: -4", f"CODE: {code}") + self.FINISH, optional_assumptions={optional})
        with self.assertRaisesRegex(ValueError, "failed or was skipped"):
            self.check_output(self.START + self.SUCCESS + gpu + self.FINISH, optional_assumptions={("OtherClass", "gpuTest")})
        with self.assertRaisesRegex(ValueError, "failed or was skipped"):
            self.check_output(self.START + self.SUCCESS.replace("CODE: 0", "CODE: -4") + self.FINISH, optional_assumptions={(smoke.TEST_CLASS, smoke.TEST_METHOD)})

    def test_real_test_protocol_passes_with_unix_or_adb_line_endings(self):
        output = self.START + self.SUCCESS + self.FINISH
        self.check_output(output)
        self.check_output(output.replace("\n", "\r\n"))

    def test_explicit_acceptance_identity_requires_its_own_packets(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "instrumentation.txt")
            path.write_text((self.START + self.SUCCESS + self.FINISH).replace(smoke.TEST_CLASS, "AcceptanceTest").replace(smoke.TEST_METHOD, "measured"))
            smoke.check_instrumentation(path, "AcceptanceTest", "measured")
            with self.assertRaisesRegex(ValueError, "did not execute"):
                smoke.check_instrumentation(path, "AcceptanceTest", "different")

    def test_zero_tests_wrong_test_and_incomplete_crash_cannot_pass(self):
        for output in [self.FINISH, self.START, self.START + self.FINISH, self.START + self.SUCCESS, (self.START + self.SUCCESS + self.FINISH).replace(smoke.TEST_CLASS, "OtherTest")]:
            with self.subTest(output=output), self.assertRaisesRegex(ValueError, "did not execute"):
                self.check_output(output)

    def test_assertions_errors_and_skips_cannot_pass(self):
        for code in [-1, -2, -3, -4]:
            output = self.START + self.SUCCESS.replace("STATUS_CODE: 0", f"STATUS_CODE: {code}") + self.FINISH
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, "failed or was skipped"):
                self.check_output(output)

    def test_aborted_or_invalid_completion_cannot_pass(self):
        output = self.START + self.SUCCESS
        for ending in ["INSTRUMENTATION_FAILED: Unable to find instrumentation info", "INSTRUMENTATION_ABORTED: System has crashed", "INSTRUMENTATION_CODE: 0", "INSTRUMENTATION_CODE: -2"]:
            with self.subTest(ending=ending), self.assertRaises(ValueError):
                self.check_output(output + ending)
        for invalid in [self.SUCCESS + self.FINISH, self.START + self.START + self.SUCCESS + self.FINISH, self.START + self.SUCCESS + self.SUCCESS + self.FINISH]:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.check_output(invalid)


    def test_aosp_no_english_tts_is_only_an_exactly_identified_prerequisite_skip(self):
        voice_class = 'dev.chungjungsoo.gptmobile.presentation.ui.voice.LocalVoicePluginInstrumentedTest'
        voice_test = 'kokoroDelegateFallsBackToRealAndroidTextToSpeechWhenModelIsMissing'
        reason = 'The Android device has no available English TTS voice'
        identity = (voice_class, voice_test)
        # Reproduces API34 AndroidJUnitRunner: start(1), exact assumption(-4),
        # and completed instrumentation(-1). The old checker rejected this.
        started = (f'INSTRUMENTATION_STATUS: class={voice_class}\n'
                   f'INSTRUMENTATION_STATUS: test={voice_test}\n'
                   'INSTRUMENTATION_STATUS_CODE: 1\n')
        skipped = (f'INSTRUMENTATION_STATUS: class={voice_class}\n'
                   f'INSTRUMENTATION_STATUS: stack=org.junit.AssumptionViolatedException: {reason}\n'
                   f'INSTRUMENTATION_STATUS: test={voice_test}\n'
                   'INSTRUMENTATION_STATUS_CODE: -4\n')
        approved = {identity: reason}
        self.check_output(started + skipped + self.FINISH, test_class=voice_class, test_method=voice_test,
                          optional_assumption_reasons=approved)
        # API36, different exceptions and unrelated failures remain hard errors.
        with self.assertRaisesRegex(ValueError, 'failed or was skipped'):
            self.check_output(started + skipped + self.FINISH, test_class=voice_class, test_method=voice_test)
        for bad in (skipped.replace(reason, 'Unrelated instrumentation failure'),
                    skipped.replace('CODE: -4', 'CODE: -2'),
                    skipped.replace('org.junit.AssumptionViolatedException:', 'java.lang.IllegalStateException:')):
            with self.subTest(bad=bad), self.assertRaisesRegex(ValueError, 'failed or was skipped'):
                self.check_output(started + bad + self.FINISH, test_class=voice_class, test_method=voice_test,
                                  optional_assumption_reasons=approved)
        for bad in (skipped + self.FINISH,
                    started + skipped + skipped + self.FINISH,
                    started + skipped + f'INSTRUMENTATION_STATUS: class={voice_class}\n'
                                        f'INSTRUMENTATION_STATUS: test={voice_test}\n'
                                        'INSTRUMENTATION_STATUS_CODE: 0\n' + self.FINISH):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                self.check_output(bad, test_class=voice_class, test_method=voice_test,
                                  optional_assumption_reasons=approved)
        with self.assertRaisesRegex(ValueError, 'did not execute'):
            self.check_output(self.FINISH, test_class=voice_class, test_method=voice_test,
                              optional_assumption_reasons=approved)
        with self.assertRaisesRegex(ValueError, 'did not execute'):
            self.check_output(started + skipped + self.FINISH, optional_assumption_reasons=approved)


    def test_release_acceptance_executes_and_checks_real_native_memory_on_each_emulator(self):
        acceptance = (Path(__file__).resolve().parents[2] / 'scripts/run_release0950_acceptance.sh').read_text()
        self.assertIn(smoke.TEST_CLASS + '#' + smoke.TEST_METHOD, acceptance)
        self.assertIn('native_memory_status=${PIPESTATUS[0]}', acceptance)
        self.assertIn('if [ "$native_memory_status" -ne 0 ]; then exit "$native_memory_status"; fi', acceptance)
        self.assertIn("check_instrumentation(folder / 'native-memory-instrumentation.txt')", acceptance)
        self.assertIn('native-memory-instrumentation.txt', acceptance)

    def test_release_acceptance_verifies_all_current_conversation_glass_cases(self):
        # API34 and API36 executed real tests successfully but rejected a stale
        # method name. Keep the verifier in sync with every instrumented case.
        import re
        root = Path(__file__).resolve().parents[2]
        instrumented = (root / 'app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ConversationGlassInstrumentedTest.kt').read_text()
        acceptance = (root / 'scripts/run_release0950_acceptance.sh').read_text()
        declared = re.findall(r'@Test\s+fun\s+(\w+)\s*\(', instrumented)
        block = re.search(
            r"for test in \(([^)]*)\):\n    check_instrumentation\(folder / 'conversation-glass-instrumentation.txt'",
            acceptance,
        )
        self.assertTrue(declared, 'No instrumented conversation-glass methods were discovered')
        self.assertIsNotNone(block, 'Acceptance verifier must check concrete conversation-glass methods')
        checked = re.findall(r"'(\w+)'", block.group(1))
        self.assertEqual(len(checked), len(set(checked)), 'Duplicate case in acceptance verifier')
        self.assertCountEqual(declared, checked, 'Acceptance verifier must track every real glass test')
        self.assertIn('conversation_glass_status=', acceptance)
        self.assertIn('if [ "$conversation_glass_status" -ne 0 ]; then exit "$conversation_glass_status"; fi', acceptance)

    def test_aosp_tts_prerequisite_scope_is_bound_to_ci_emulator_matrix(self):
        root = Path(__file__).resolve().parents[2]
        workflow = (root / '.github/workflows/release-0950-acceptance.yml').read_text()
        acceptance = (root / 'scripts/run_release0950_acceptance.sh').read_text()
        self.assertIn('ACCEPTANCE_EMULATOR_API=${{ matrix.api }} ACCEPTANCE_EMULATOR_TARGET=${{ matrix.target }}', workflow)
        self.assertIn("os.environ.get('ACCEPTANCE_EMULATOR_API') == '34'", acceptance)
        self.assertIn("os.environ.get('ACCEPTANCE_EMULATOR_TARGET') == 'default'", acceptance)
        self.assertIn('optional_assumption_reasons=aosp_tts_assumptions', acceptance)


    def test_current_scoped_release_includes_exact_aosp_smoke_fixes_and_no_generic_scripts(self):
        root = Path(__file__).resolve().parents[2]
        manifest = json.loads((root / 'release-scope.json').read_text())
        required = ['scripts/check_native_memory_smoke.py', 'scripts/tests/test_native_memory_smoke.py']
        self.assertEqual('scoped', release_scope.validate(root, manifest, required))
        for path in required:
            with self.subTest(path=path):
                self.assertIn(path, manifest['allowedPaths'])
        self.assertNotIn('scripts/', manifest['allowedPaths'])
        with self.assertRaisesRegex(ValueError, 'Changes outside release scope'):
            release_scope.validate(root, manifest, required + ['scripts/unrelated-regression.py'])


if __name__ == "__main__":
    unittest.main()
