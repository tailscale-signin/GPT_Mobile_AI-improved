import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
import xml.etree.ElementTree as ET
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop.acceptance_discovery import add_discovered, discover, parse_tests, preset
from desktop.repository_validation import defaults, SetupCancelled


class AcceptanceDiscoveryTests(unittest.TestCase):
    SOURCE = 'import unittest\nclass Behavior(unittest.TestCase):\n    def test_answer(self):\n        self.assertEqual(2, 1 + 1)\n'

    def test_discovery_pins_real_mirror_and_never_imports_test_modules(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'test_behavior.py').write_text('raise RuntimeError("must not import")\n' + self.SOURCE)
            env = {key: value for key, value in os.environ.items() if not key.upper().startswith('GIT_')}
            env.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1')
            for command in (['git', 'init', '-q', directory], ['git', '-C', directory, 'add', '.'],
                            ['git', '-C', directory, '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                             '-c', 'commit.gpgsign=false', 'commit', '-qm', 'Test']):
                subprocess.run(command, check=True, capture_output=True, env=env)
            result = discover({'mirror': str(root / '.git')}, 'test_behavior')
            expected = subprocess.check_output(['git', '-C', directory, 'rev-parse', 'HEAD'], text=True, env=env).strip()
            self.assertEqual(expected, result['head_sha'])
            self.assertFalse(result['executed'])
            self.assertEqual('test_behavior.Behavior.test_answer', result['candidates'][0]['case_id'])
            cancel = threading.Event()
            cancel.set()
            with self.assertRaises(SetupCancelled):
                discover({'mirror': str(root / '.git')}, cancel=cancel)

    def test_python_preset_emits_actual_fresh_named_case_success_failure_and_skip(self):
        candidate = parse_tests('test_behavior.py', self.SOURCE)[0]
        command, report = preset(candidate)
        for suffix, expected in (('', 0), ('\n        self.fail("broken")', 1), ('\n        self.skipTest("unavailable")', 1)):
            with self.subTest(suffix=suffix), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                (root / 'test_behavior.py').write_text(self.SOURCE.rstrip() + suffix + '\n')
                result = subprocess.run([sys.executable, *command[1:]], cwd=root, capture_output=True)
                self.assertEqual(expected, result.returncode, result.stderr.decode())
                case = ET.parse(root / report).find('testcase')
                self.assertEqual(candidate['case_id'], case.get('classname') + '.' + case.get('name'))
                self.assertEqual(expected != 0, case.find('failure') is not None)

    def test_discovered_mapping_preserves_options_and_uses_normal_complete_validator(self):
        with tempfile.TemporaryDirectory() as directory:
            options = defaults(directory)
            before = json.dumps(options, sort_keys=True)
            candidate = parse_tests('gateway/tests/test_behavior.py', self.SOURCE)[0]
            result = add_discovered(options, candidate, objective='Fix the answer', requirement_id='answer',
                                    source_path='gateway/answer.py', ci_check='Required verification')
            self.assertEqual(before, json.dumps(options, sort_keys=True))
            self.assertEqual(4, len(result['commands']))
            self.assertEqual(3, result['acceptance_map'][0]['command_index'])
            self.assertEqual(candidate['case_id'], result['acceptance_templates'][0]['requirements'][0]['evidence'][0]['name'])
            with self.assertRaises(ValueError):
                add_discovered(result, candidate, objective='Fix the answer', requirement_id='answer',
                               source_path='gateway/answer.py', ci_check='Required verification')

    def test_kotlin_candidate_selects_exact_named_junit_case(self):
        text = 'package example\nclass AnswerTest {\n@Test\nfun `returns an answer`() {}\n}'
        candidate = parse_tests('app/src/test/kotlin/example/AnswerTest.kt', text)[0]
        command, report = preset(candidate)
        self.assertEqual('example.AnswerTest.returns an answer', command[-1])
        self.assertEqual('app/build/test-results/testDebugUnitTest/TEST-example.AnswerTest.xml', report)
        self.assertEqual([], parse_tests('test_invalid.py', 'invalid python !!'))


if __name__ == '__main__':
    unittest.main()
