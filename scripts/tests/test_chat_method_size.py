import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

from scripts.tests.test_mediapipe_jni import dex_fixture, uleb


SPEC = importlib.util.spec_from_file_location('chat_size', Path(__file__).parents[1] / 'check_chat_method_size.py')
SIZE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SIZE)

CHAT = 'Ldev/chungjungsoo/gptmobile/presentation/ui/chat/'
SCREEN = CHAT + 'ChatScreenKt;'
VOICE = 'Ldev/chungjungsoo/gptmobile/presentation/ui/voice/VoiceConversationContentKt;'
PRIMARY = ('ChatScreen-WN4HLf4', 'ChatScreenContent-WN4HLf4')
COMMIT = '0123456789abcdef0123456789abcdef01234567'


def executable_fixture(definitions, references=()):
    """Add bounded real code_item bodies to the independent JNI DEX fixture.

    Every fixture signature is ()V. References without class-data definitions
    stay in method_ids, allowing a regression for the false-positive lookup.
    """
    classes = {owner: {'methods': [(name, '()V', 9) for name in methods]}
               for owner, methods in definitions.items()}
    declared = {}
    for owner, name in references:
        declared.setdefault(owner, {'methods': []})['methods'].append((name, '()V', 9))
    data = bytearray(dex_fixture(classes, declared))
    # With a single shared prototype, DEX method identifiers sort by owner/name.
    method_ids = {row: index for index, row in enumerate(sorted({
        *((owner, name) for owner, methods in definitions.items() for name in methods),
        *references,
    }))}

    def emit(content, aligned=False):
        if aligned:
            data.extend(bytes((-len(data)) % 4))
        offset = len(data)
        data.extend(content)
        return offset

    class_table = struct.unpack_from('<I', data, 100)[0]
    for index, (owner, methods) in enumerate(sorted(definitions.items())):
        bodies = {}
        for name, units in methods.items():
            # A None body is an identifier/definition without executable code.
            bodies[name] = 0 if units is None else emit(
                struct.pack('<HHHHII', 1, 0, 0, 0, 0, units)
                + b'\0\0' * (units - 1) + b'\x0e\0', aligned=True,
            )
        content = bytearray(uleb(0) + uleb(0) + uleb(len(methods)) + uleb(0))
        previous = 0
        for name in sorted(methods):
            identifier = method_ids[(owner, name)]
            content.extend(uleb(identifier - previous) + uleb(9) + uleb(bodies[name]))
            previous = identifier
        struct.pack_into('<I', data, class_table + index * 32 + 24, emit(content))
    struct.pack_into('<I', data, 32, len(data))
    return bytes(data)


def primary_definitions(units=8):
    return {SCREEN: {name: units for name in PRIMARY}}


class ChatMethodSizeTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.apk = Path(self.directory.name) / 'app-debug.apk'

    def archive(self, *dexes):
        with ZipFile(self.apk, 'w') as archive:
            for index, dex in enumerate(dexes):
                archive.writestr(f"classes{index + 1 if index else ''}.dex", dex)
        return self.apk

    def report(self, *dexes):
        return SIZE.report_apk(self.archive(*dexes), COMMIT)

    def test_actual_packaged_code_units_respect_art_boundary(self):
        for units, status in ((16382, 'PASS'), (16383, 'FAIL'), (16384, 'FAIL'), (16385, 'FAIL')):
            with self.subTest(code_units=units):
                report = self.report(executable_fixture(primary_definitions(units)))
                self.assertEqual(status, report['status'], report)
                actual = report['primary_methods']['ChatScreenContent'][0]
                self.assertEqual(units, actual['code_units'])
                self.assertEqual(2 * units, actual['dex_instruction_bytes'])
                self.assertEqual(2 if units >= 16383 else 0, len(report['oversized_methods']))

    def test_references_accessors_defaults_and_lambda_wrappers_cannot_replace_actual_definition(self):
        for replacement in (
            {SCREEN: {PRIMARY[0]: 8, 'access$' + PRIMARY[1]: 8}},
            {SCREEN: {PRIMARY[0]: 8, PRIMARY[1] + '$default': 8}},
            {SCREEN: {PRIMARY[0]: 8, PRIMARY[1] + '$lambda$1': 8}},
            {SCREEN: {PRIMARY[0]: 8}, CHAT + 'ChatScreenKt$ChatScreenContent$1;': {'invoke': 8}},
            {SCREEN: {PRIMARY[0]: 8, PRIMARY[1]: None}},
        ):
            with self.subTest(replacement=replacement):
                report = self.report(executable_fixture(replacement, ((SCREEN, PRIMARY[1]),)))
                self.assertEqual('FAIL', report['status'], report)
                self.assertIn('actual ChatScreenContent definition', report['errors'][0])

    def test_oversized_related_chat_and_voice_methods_fail_across_dex_files(self):
        report = self.report(
            executable_fixture(primary_definitions()),
            executable_fixture({
                CHAT + 'ChatScreenKt$Content$1;': {'invoke': 20000},
                VOICE: {'VoiceConversationScreen': 16383, 'SmallControl': 12},
            }),
        )
        self.assertEqual('FAIL', report['status'], report)
        self.assertEqual([20000, 16383], [row['code_units'] for row in report['oversized_methods']])
        self.assertTrue(all(row['dex'] == 'classes2.dex' for row in report['oversized_methods']))
        self.assertEqual([], report['errors'])

    def test_unrelated_large_method_does_not_expand_the_gate_scope(self):
        report = self.report(executable_fixture({**primary_definitions(), 'Lother/Screen;': {'ChatScreenContent': 20000}}))
        self.assertEqual('PASS', report['status'], report)
        self.assertEqual(2, report['method_count'])

    def test_missing_duplicate_and_truncated_dex_fail_closed(self):
        valid = executable_fixture(primary_definitions())
        malformed = bytearray(valid)
        struct.pack_into('<I', malformed, 92, len(valid) + 4)
        for dexes in ((), (valid, valid), (valid[:100],), (valid[:-1],), (bytes(malformed),)):
            with self.subTest(dex_count=len(dexes)):
                report = self.report(*dexes)
                self.assertEqual('ERROR', report['status'], report)
                self.assertTrue(report['errors'])

    def test_full_method_identifier_table_is_valid_in_multidex_apk(self):
        # R8 can fill a DEX with exactly 65,536 referenced methods.
        references = tuple((VOICE, f'reference{index:05d}') for index in range(65534))
        report = self.report(executable_fixture(primary_definitions(), references))
        self.assertEqual('PASS', report['status'], report)
        self.assertEqual(2, report['method_count'])

    def test_dex_payload_is_bounded_before_decompression(self):
        valid = executable_fixture(primary_definitions())
        self.archive(valid)
        with patch.object(SIZE, 'MAX_DEX_BYTES', len(valid) - 1), patch.object(ZipFile, 'open', side_effect=AssertionError('must reject before reading DEX')):
            report = SIZE.report_apk(self.apk, COMMIT)
        self.assertEqual('ERROR', report['status'])
        self.assertIn('bounded audit size', report['errors'][0])

    def test_truncated_code_item_cannot_claim_a_smaller_body(self):
        data = bytearray(executable_fixture(primary_definitions()))
        # Locate the fixture's unique header followed by eight code units.
        header = struct.pack('<HHHHII', 1, 0, 0, 0, 0, 8)
        offset = data.index(header, 112)
        struct.pack_into('<I', data, offset + 12, len(data))
        report = self.report(bytes(data))
        self.assertEqual('ERROR', report['status'], report)
        self.assertIn('range extends outside', report['errors'][0])

    def test_empty_executable_code_item_cannot_falsely_pass_the_size_gate(self):
        data = bytearray(executable_fixture(primary_definitions()))
        header = struct.pack('<HHHHII', 1, 0, 0, 0, 0, 8)
        offset = data.index(header, 112)
        struct.pack_into('<I', data, offset + 12, 0)
        report = self.report(bytes(data))
        self.assertEqual('ERROR', report['status'], report)
        self.assertIn('empty code item', report['errors'][0])

    def test_cli_writes_failure_report_with_actual_apk_digest_and_revision(self):
        self.archive(executable_fixture(primary_definitions(16383)))
        output = Path(self.directory.name) / 'report.json'
        with contextlib.redirect_stdout(io.StringIO()):
            code = SIZE.main([str(self.apk), '--report', str(output), '--source-commit', COMMIT])
        report = json.loads(output.read_text())
        self.assertEqual(1, code)
        self.assertEqual('FAIL', report['status'])
        self.assertEqual(hashlib.sha256(self.apk.read_bytes()).hexdigest(), report['apk_sha256'])
        self.assertEqual(COMMIT, report['source_commit'])
        self.assertEqual(2, len(report['oversized_methods']))

    def test_cli_retains_malformed_input_evidence_in_a_small_report(self):
        self.apk.write_bytes(b'not an apk')
        output = Path(self.directory.name) / 'report.json'
        with contextlib.redirect_stdout(io.StringIO()):
            code = SIZE.main([str(self.apk), '--report', str(output), '--source-commit', COMMIT])
        report = json.loads(output.read_text())
        self.assertEqual(1, code)
        self.assertEqual('ERROR', report['status'])
        self.assertTrue(report['errors'])
        self.assertEqual(hashlib.sha256(self.apk.read_bytes()).hexdigest(), report['apk_sha256'])


if __name__ == '__main__':
    unittest.main()
