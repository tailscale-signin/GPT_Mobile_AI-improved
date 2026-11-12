"""Regression coverage for nested read-only GitHub/provider cooldowns."""
import copy
import sys
import time
import unittest
from pathlib import Path
from types import SimpleNamespace

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.batch import BatchExecutor, deferred_read, deferred_receipt


def node(name, *dependencies):
    return {'id': name, 'tool': 'get_blob', 'arguments': {'sha': name},
            'depends_on': list(dependencies)}


class GatewayBatchDeferralTest(unittest.TestCase):
    def test_429_retained_and_resumed_without_repeating_healthy_reads(self):
        calls = []
        def first(item):
            calls.append(item['id'])
            if item['id'] == 'a':
                error = requests.HTTPError('limit')
                error.response = SimpleNamespace(status_code=429, headers={'Retry-After': '3600'})
                raise error
            return {'content': item['id']}
        plan = [node('a'), node('b'), node('c', 'a')]
        executor = BatchExecutor()
        saved = executor.execute(plan, first)
        self.assertTrue(deferred_read(saved['a']))
        self.assertEqual('RETRY_AFTER', saved['a']['error']['retry_source'])
        self.assertEqual('b', saved['b']['content'])
        self.assertNotIn('c', saved)
        self.assertEqual({'a', 'b'}, set(calls))
        # A premature resume cannot violate the server cooldown.
        executor.execute(plan, lambda item: self.fail('read retried during cooldown'), receipts=saved)
        eligible = copy.deepcopy(saved)
        eligible['a']['error']['retry_at'] = time.time() - 1
        repeat = []
        final = executor.execute(plan, lambda item: repeat.append(item['id']) or {'content': item['id']}, receipts=eligible)
        self.assertEqual({'a', 'c'}, set(repeat))
        self.assertEqual('b', final['b']['content'])
        self.assertEqual('c', final['c']['content'])

    def test_secondary_limit_is_not_a_permissions_failure(self):
        limited = requests.HTTPError('secondary')
        limited.response = SimpleNamespace(status_code=403, headers={'Retry-After': '90'})
        receipt = deferred_receipt(limited, 0)
        self.assertEqual('BATCH_READ_DEFERRED', receipt['error']['code'])
        self.assertEqual(403, receipt['error']['original_status'])
        permission = requests.HTTPError('denied')
        permission.response = SimpleNamespace(status_code=403, headers={})
        self.assertIsNone(deferred_receipt(permission, 0))

    def test_unauthorized_read_not_reclassified(self):
        error = PermissionError('not allowed')
        self.assertIsNone(deferred_receipt(error, 0))


if __name__ == '__main__':
    unittest.main()
