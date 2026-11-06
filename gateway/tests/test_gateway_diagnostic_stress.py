"""Durable capture retention stress, isolated from the ordinary v14 suite deadline."""
from pathlib import Path
import sys
import tempfile
import time
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_gateway_v14_diagnostic_events import NOW, SESSION, batch, event, unpack
from v14.diagnostic_events import DiagnosticSessionJournal


class DiagnosticJournalStressTests(unittest.TestCase):
    def test_50000_events_restart_and_digest_expiry_never_resurrect_evicted_replay(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'events.sqlite3'
            store = DiagnosticSessionJournal(path, now=lambda: NOW)
            started, next_checkpoint = time.monotonic(), 10000
            try:
                for commits, first in enumerate(range(1, 50001, 64), 1):
                    last = min(50000, first + 63)
                    receipt = store.ingest('paired-owner', batch(event(n) for n in range(first, last + 1)))
                    if last >= next_checkpoint:
                        print('Durable diagnostic stress: '
                              f'committed_events={receipt["last_ingest_sequence"]} commits={commits} '
                              f'elapsed_seconds={time.monotonic() - started:.3f}', flush=True)
                        next_checkpoint += 10000
                store.close()
                store = DiagnosticSessionJournal(path, now=lambda: NOW)
                receipt = store.ingest('paired-owner', batch([event(1)]))
                self.assertEqual([], receipt['committed_event_ids'])
                self.assertEqual([{'event_id': event(1)['event_id'], 'content_verified': False}], receipt['already_accounted'])
                manifest, rows, _ = unpack(store.export('paired-owner', SESSION))
                self.assertEqual(45000, manifest['evicted_count'])
                self.assertEqual(45001, rows[0]['origin_sequence'])
                self.assertEqual(50000, manifest['sources'][0]['accounted_through'])
            finally:
                store.close()


if __name__ == '__main__':
    unittest.main()
