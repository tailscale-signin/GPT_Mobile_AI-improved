"""Terminal identity failure invalidates context comparison labels, retaining facts."""
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks.contracts import digest
from benchmarks.store import BenchmarkStore


class ContextCompletionTests(unittest.TestCase):
    def test_final_identity_failure_invalidates_comparison_without_rewriting_completed_case(self):
        with tempfile.TemporaryDirectory() as directory:
            store = BenchmarkStore(Path(directory) / 'benchmarks.sqlite3')
            request = dict(request_id='context-terminal', model='selected', suite='coding', preset='quick',
                           context_variants=['CURRENT', 'TASK'], context_repeats=1)
            run, _ = store.create('owner', request, {})
            case = dict(id='task:CURRENT:0', status='PASSED', executed=True, graded=True, resolved=True,
                        contextReceipt={'manifestDigest': 'a' * 64, 'coldPreparationMs': 3})
            summary = dict(version='benchmark-context-comparison-v1', qualified=False, score=None,
                           contextComparisons=[dict(variant='TASK', state='DESCRIPTIVE_PAIRED_RESULT', matchedPairs=12)],
                           contextArms={'CURRENT': {'inputTokens': 120}})
            store.update(run['id'], state='RUNNING', planned=1, casePlan=[case['id']])
            store.case(run['id'], 0, case)
            store.update(run['id'], completed=1, summary=summary)
            store.update(run['id'], state='FAILED', endedAt=100, failure={'code': 'SELECTED_EXECUTION_CHANGED'})
            saved = store.get(run['id'])
            self.assertEqual('INVALIDATED', saved['summary']['contextComparisons'][0]['state'])
            self.assertEqual('FAILED', saved['summary']['invalidatedBy'])
            self.assertEqual(120, saved['summary']['contextArms']['CURRENT']['inputTokens'])
            self.assertEqual(case, saved['cases'][0])
            self.assertEqual(digest(saved['summary']), saved['completionReceipt']['summaryDigest'])
