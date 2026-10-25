import importlib.util
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    'jacoco_coverage', Path(__file__).parents[1] / 'check_jacoco_coverage.py')
COVERAGE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COVERAGE)


class JacocoCoverageTest(unittest.TestCase):
    def validate(self, xml):
        with tempfile.TemporaryDirectory() as directory:
            report = Path(directory) / 'coverage.xml'
            report.write_text(xml, encoding='utf-8')
            return COVERAGE.validate_report(report)

    def entry(self, name='dev/chungjungsoo/gptmobile/data/agent/CombinedDeliverySession',
              covered='7', missed='3'):
        return (f'<class name="{name}"><counter type="INSTRUCTION" '
                f'covered="{covered}" missed="{missed}"/></class>')

    def report(self, classes, counters=''):
        return f'<report name="app"><package name="fixture">{classes}</package>{counters}</report>'

    def test_counts_executed_application_classes_without_foreign_instructions(self):
        classes = (self.entry() + self.entry('org/example/Library', '100', '0') +
                   self.entry('dev/chungjungsoo/gptmobile/data/Unused', '0', '12'))
        # JaCoCo's standard external doctype must not require any network access.
        result = self.validate('<!DOCTYPE report SYSTEM "https://example.invalid/report.dtd">' +
                               self.report(classes))
        self.assertEqual({
            'application_classes': 2,
            'executed_application_classes': 1,
            'covered_instructions': 7,
            'missed_instructions': 15,
        }, result)

    def test_session_only_or_aggregate_only_reports_are_not_coverage(self):
        for xml in (
            '<report name="app"><sessioninfo id="worker" start="1" dump="2"/></report>',
            self.report('', '<counter type="INSTRUCTION" covered="9999" missed="0"/>'),
        ):
            with self.subTest(xml=xml), self.assertRaisesRegex(ValueError, 'no application classes'):
                self.validate(xml)

    def test_foreign_coverage_cannot_hide_unexecuted_application_classes(self):
        for classes in (
            self.entry(covered='0'),
            self.entry(covered='0') + self.entry('org/example/Library', '100', '0'),
            '<class name="dev/chungjungsoo/gptmobile/data/Unused"/>',
        ):
            with self.subTest(classes=classes), self.assertRaisesRegex(ValueError, 'no executed application'):
                self.validate(self.report(classes))
        with self.assertRaisesRegex(ValueError, 'no application classes'):
            self.validate(self.report(self.entry('dev/chungjungsoo/gptmobileOther/Library')))

    def test_missing_or_malformed_reports_fail(self):
        for xml in ('', '<report>', '<unrelated/>'):
            with self.subTest(xml=xml), self.assertRaises(ValueError):
                self.validate(xml)
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            COVERAGE.validate_report(Path(directory) / 'missing.xml')

    def test_invalid_counters_and_duplicate_classes_fail(self):
        for classes in (
            self.entry(covered='-1'), self.entry(missed='-1'),
            self.entry(covered='not-a-number'), self.entry() + self.entry(),
            '<class name="dev/chungjungsoo/gptmobile/data/Invalid"><counter type="INSTRUCTION"/></class>',
        ):
            with self.subTest(classes=classes), self.assertRaises(ValueError):
                self.validate(self.report(classes))


if __name__ == '__main__':
    unittest.main()
