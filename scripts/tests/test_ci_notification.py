import hashlib
import hmac
import importlib.util
import json
import unittest
from pathlib import Path
from unittest.mock import MagicMock

spec = importlib.util.spec_from_file_location('notify_ci_status', Path(__file__).parents[1] / 'notify_ci_status.py')
notify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(notify)


class CiNotificationTest(unittest.TestCase):
    def event(self):
        return {'repository': {'full_name': 'owner/repo'}, 'workflow_run': {
            'repository': {'full_name': 'owner/repo'}, 'id': 5, 'run_attempt': 2,
            'status': 'completed', 'conclusion': 'success', 'head_sha': 'a' * 40,
            'logs': 'private', 'head_commit': {'message': 'private'}}}

    def test_signed_delivery_is_bounded_and_contains_only_ci_metadata(self):
        opener = MagicMock()
        opener.open.return_value.__enter__.return_value.status = 204
        notify.deliver(self.event(), 'https://example.test/hook', 'secret', opener)
        req = opener.open.call_args.args[0]
        self.assertEqual('sha256=' + hmac.new(b'secret', req.data, hashlib.sha256).hexdigest(), req.get_header('X-hub-signature-256'))
        self.assertNotIn(b'private', req.data)
        self.assertEqual(15, opener.open.call_args.kwargs['timeout'])
        self.assertEqual('owner/repo:5:2', req.get_header('X-ci-delivery'))

    def test_invalid_destinations_and_incomplete_runs_do_not_send(self):
        for url in ('http://example.test', 'https://user:password@example.test', 'https://example.test/#fragment'):
            opener = MagicMock()
            with self.assertRaises(ValueError):
                notify.deliver(self.event(), url, 'secret', opener)
            opener.open.assert_not_called()
        event = self.event(); event['workflow_run']['status'] = 'in_progress'
        with self.assertRaises(ValueError):
            notify.notification(event)
        self.assertIsNone(notify.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://elsewhere.test'))
