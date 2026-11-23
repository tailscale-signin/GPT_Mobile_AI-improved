import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from desktop import benchmark_qualification as qualification


class DesktopQualificationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.manifest = self.root / 'manifest.json'
        self.manifest.write_text(json.dumps({'expected_route': {'model': 'exact-model'}, 'cases': []}))
        self.binding = dict(origin='http://127.0.0.1:8090', gateway_id='one', principal_id='account')
        p = patch.object(qualification.benchmark, 'execution_binding', return_value=self.binding)
        p.start()
        self.addCleanup(p.stop)
        self.run = dict(id='qual_' + 'a' * 32, state='AWAITING_EVIDENCE', result=None)

    def test_unknown_admission_is_durable_and_never_reposted(self):
        with patch.object(qualification.benchmark, 'service_request', side_effect=TimeoutError):
            with self.assertRaises(TimeoutError):
                qualification.start(self.root, self.manifest, execute=True)
        saved = qualification.history(self.root)[0]
        self.assertEqual('UNCERTAIN', saved['state'])
        with patch.object(qualification.benchmark, 'service_request', return_value={'submissionState': 'NOT_OBSERVED'}) as call:
            checked = qualification.refresh(self.root, saved['localId'], execute=True)
        self.assertEqual('UNCERTAIN', checked['state'])
        self.assertNotIn('method', call.call_args.kwargs)
        self.assertEqual(self.binding, call.call_args.kwargs['binding'])
        with self.assertRaisesRegex(ValueError, 'original uncertain'):
            qualification.start(self.root, self.manifest)

    def test_readback_cannot_upgrade_original_read_only_authority(self):
        with patch.object(qualification.benchmark, 'service_request', return_value=self.run):
            result = qualification.start(self.root, self.manifest, execute=False)
        with patch.object(qualification.benchmark, 'service_request') as call:
            with self.assertRaisesRegex(ValueError, 'readback only'):
                qualification.refresh(self.root, result['localId'], execute=True)
            call.assert_not_called()

    def test_execute_and_readback_use_original_route_and_saved_id(self):
        with patch.object(qualification.benchmark, 'service_request', return_value=self.run) as call:
            value = qualification.start(self.root, self.manifest, execute=True)
            self.assertTrue(call.call_args.kwargs['payload']['execute'])
            self.assertEqual('POST', call.call_args.kwargs['method'])
            qualification.refresh(self.root, value['localId'])
            self.assertEqual('GET', call.call_args.kwargs['method'])
            self.assertEqual('/qualifications/' + self.run['id'], call.call_args.args[1])
            qualification.refresh(self.root, value['localId'], execute=True)
            self.assertEqual('/qualifications/' + self.run['id'] + '/resume', call.call_args.args[1])
            self.assertEqual('POST', call.call_args.kwargs['method'])
            self.assertEqual(self.binding, call.call_args.kwargs['binding'])
        self.assertNotIn('binding', value)
        self.assertNotIn('payload', value)


if __name__ == '__main__':
    unittest.main()
