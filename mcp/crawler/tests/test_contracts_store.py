import concurrent.futures
import json
import tempfile
import unittest
from pathlib import Path

from crawler_server.contracts import CrawlerError, WorkGrant, digest, request, tool_definitions, url_identity
from crawler_server.store import Store


class DurableCrawlerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "crawler.sqlite"
        self.now = 1000.0
        self.store = Store(self.path, clock=lambda: self.now)
        self.grant = WorkGrant("user-a", "grant-a", "root-a", "work-a", "policy-a", 1, 12, 50000, 2000)

    def tearDown(self):
        self.store.close()
        self.directory.cleanup()

    def start(self, key="accepted-a", **changes):
        args = {"seeds": ["https://example.org/guide"], "idempotencyKey": key, **changes}
        return self.store.start(request("start", args), self.grant)

    def publish(self, job, text="A legitimate short page.", links=()):
        claim = self.store.claim(job, self.grant)
        attempt = self.store.reserve_request(job, self.grant, claim["generation"], claim["url"])
        self.store.record_bytes(attempt, len(text.encode()), len(text.encode()))
        self.store.finish_attempt(attempt)
        revision = digest(claim["url"] + text)
        document = {"sourceId": digest(claim["url"]), "documentRevision": revision, "contentHash": digest(text), "text": text,
                    "requestedUrl": claim["url"], "coverage": {"fullText": None, "abstractOnly": None}}
        self.store.publish(claim, self.grant, document, text.encode(), links)
        return claim

    def assertCode(self, code, callback):
        with self.assertRaises(CrawlerError) as caught:
            callback()
        self.assertEqual(code, caught.exception.code)

    def test_resume_references_content_and_parent_deletion_keeps_child_evidence(self):
        job = self.start()
        self.publish(job, text="Stable original evidence.")
        self.store.cancel(job, self.grant)
        child = self.store.start(request("start", {"resumeFromJobId": job, "idempotencyKey": "resume"}), self.grant)
        self.assertEqual(1, self.store.db.execute("SELECT COUNT(*) FROM content_objects").fetchone()[0])
        self.assertEqual(1, self.store.db.execute("SELECT COUNT(*) FROM artifact_objects").fetchone()[0])
        self.assertEqual(0, self.store.db.execute("SELECT SUM(length(text)+length(artifact)) FROM documents").fetchone()[0])
        self.assertEqual(0, self.store.db.execute("SELECT SUM(length(text)) FROM chunks").fetchone()[0])
        self.store.delete(job, self.grant)
        result = self.store.results(child, self.grant)
        self.assertEqual("Stable original evidence.", result["chunks"][0]["text"])
        self.assertEqual(0, result["accounting"]["physicalRequests"])
        self.store.delete(child, self.grant)
        self.assertEqual(0, self.store.db.execute("SELECT COUNT(*) FROM content_objects").fetchone()[0])
        self.assertEqual(0, self.store.db.execute("SELECT COUNT(*) FROM artifact_objects").fetchone()[0])

    def test_retention_respects_archive_pins_and_releases_unreferenced_bytes(self):
        job = self.start()
        self.publish(job)
        self.store.cancel(job, self.grant)
        self.store.retain(job, self.grant, "archive:study")
        self.now += 8 * 86400
        self.assertEqual([], self.store.expire_jobs())
        self.assertTrue(self.store.results(job, self.grant)["chunks"])
        self.store.release(job, self.grant, "archive:study")
        self.assertEqual([job], self.store.expire_jobs())
        self.assertCode("RESULTS_EXPIRED", lambda: self.store.results(job, self.grant))
        self.assertEqual(0, self.store.db.execute("SELECT COUNT(*) FROM content_objects").fetchone()[0])

    def test_storage_quota_stops_publication_without_erasing_physical_receipts(self):
        self.store.principal_bytes = 4
        job = self.start()
        self.assertCode("STORAGE_FULL", lambda: self.publish(job))
        self.assertGreater(self.store.status(job, self.grant)["accounting"]["downloadedBytes"], 0)
        self.assertEqual(0, self.store.db.execute("SELECT COUNT(*) FROM content_objects").fetchone()[0])

    def test_v2_migration_verifies_backup_and_reuses_existing_content(self):
        job = self.start()
        self.publish(job, text="Pre-migration evidence 😀")
        with self.store.transaction() as db:
            db.execute("UPDATE documents SET text=(SELECT text FROM content_objects LIMIT 1),artifact=(SELECT body FROM artifact_objects LIMIT 1)")
            db.execute("UPDATE chunks SET text=(SELECT substr(text,start_offset+1,end_offset-start_offset) FROM documents LIMIT 1)")
            db.execute("DROP TABLE content_objects")
            db.execute("DROP TABLE artifact_objects")
            db.execute("PRAGMA user_version=2")
        self.store.close()
        self.store = Store(self.path, clock=lambda: self.now)
        self.assertEqual("Pre-migration evidence 😀", self.store.results(job, self.grant)["chunks"][0]["text"])
        self.assertEqual(3, self.store.db.execute("PRAGMA user_version").fetchone()[0])
        self.assertEqual(1, len(list(self.path.parent.glob("crawler.sqlite.schema2.*.backup"))))

    def test_temporary_evidence_expires_and_cannot_be_pinned_by_implicit_import(self):
        from dataclasses import replace
        self.grant = replace(self.grant, ephemeral=True)
        job = self.start()
        claim = self.publish(job)
        self.assertCode("PERMISSION_DENIED", lambda: self.store.retain(job, self.grant, "archive:implicit"))
        self.now += 3600
        self.assertEqual([job], self.store.expire_jobs())
        self.assertCode("RESULTS_EXPIRED", lambda: self.store.status(job, self.grant))
        self.assertCode("RESULTS_EXPIRED", lambda: self.store.publish(claim, self.grant, {}, b"", []))

    def test_concurrent_idempotent_starts_and_changed_work(self):
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            jobs = list(executor.map(lambda _: self.start(), range(20)))
        self.assertEqual(1, len(set(jobs)))
        self.assertCode("IDEMPOTENCY_CONFLICT", lambda: self.start(sourceGoal=25))
        self.assertEqual(0, self.store.status(jobs[0], self.grant)["accounting"]["physicalRequests"])

    def test_limits_and_scope_are_explicit_and_principal_partitioned(self):
        job = self.start()
        state = self.store.status(job, self.grant)
        self.assertEqual(12, state["effectiveSettings"]["maxPhysicalRequests"])
        self.assertIn("accepted_parent_grant", state["effectiveSettings"]["limitReasons"])
        wrong = WorkGrant("user-b", "grant-b", "root-b", "work-b", "policy-a", 1, 12, 50000, 2000)
        self.assertCode("PERMISSION_DENIED", lambda: self.store.status(job, wrong))

    def test_cancel_fences_late_publication_and_preserves_measured_work(self):
        job = self.start()
        claim = self.store.claim(job, self.grant)
        attempt = self.store.reserve_request(job, self.grant, 0, claim["url"])
        self.store.cancel(job, self.grant)
        self.store.record_bytes(attempt, 42, 84)
        self.store.finish_attempt(attempt)
        self.assertCode("CANCELLED", lambda: self.store.publish(claim, self.grant, {}, b"", []))
        state = self.store.status(job, self.grant)
        self.assertEqual(42, state["accounting"]["downloadedBytes"])
        self.assertTrue(state["accounting"]["final"])
        self.assertEqual(state, self.store.cancel(job, self.grant))

    def test_crash_preserves_uncertainty_without_replaying_network(self):
        job = self.start()
        claim = self.store.claim(job, self.grant)
        self.store.reserve_request(job, self.grant, 0, claim["url"])
        self.store.close()
        self.store = Store(self.path, clock=lambda: self.now)
        self.store.recover()
        state = self.store.status(job, self.grant)
        self.assertEqual("failed", state["state"])
        self.assertEqual("ACCOUNTING_UNAVAILABLE", state["stopReason"])
        self.assertEqual(1, state["accounting"]["physicalRequests"])
        self.assertEqual(1, state["accounting"]["uncertainRequests"])
        self.assertFalse(state["resumeEligible"])
        args = request("start", {"resumeFromJobId": job, "idempotencyKey": "new"})
        self.assertCode("ACCOUNTING_UNAVAILABLE", lambda: self.store.start(args, self.grant))

    def test_terminal_parent_is_immutable_and_resume_inherits_without_charge(self):
        job = self.start(mode="deep")
        self.publish(job, links=["https://example.org/next"])
        self.store.cancel(job, self.grant)
        before = self.store.status(job, self.grant)
        resumed = self.store.start(request("start", {"resumeFromJobId": job, "idempotencyKey": "resume-1"}), self.grant)
        state = self.store.status(resumed, self.grant)
        self.assertEqual(1, state["progress"]["inheritedDocuments"])
        self.assertEqual(0, state["accounting"]["physicalRequests"])
        self.assertEqual(before, self.store.status(job, self.grant))
        self.assertIsNotNone(self.store.claim(resumed, self.grant))

    def test_snapshot_cursor_replay_survives_arrivals_and_restart(self):
        job = self.start(mode="deep")
        self.publish(job, "Original evidence. " * 300, links=["https://example.org/next"])
        first = self.store.results(job, self.grant, output_bytes=4000)
        self.assertTrue(first["hasMoreWithinSnapshot"])
        cursor = first["nextCursor"]
        page = self.store.results(job, self.grant, output_bytes=4000, cursor=cursor)
        self.publish(job, "Newly arrived evidence.")
        self.store.finish(job, self.grant, "frontier_exhausted")
        self.assertEqual(page, self.store.results(job, self.grant, output_bytes=4000, cursor=cursor))
        self.store.close()
        self.store = Store(self.path, clock=lambda: self.now)
        self.assertEqual(page, self.store.results(job, self.grant, output_bytes=4000, cursor=cursor))
        self.assertCode("INVALID_CURSOR", lambda: self.store.results(job, self.grant, format="markdown", cursor=cursor))
        self.assertCode("INVALID_CURSOR", lambda: self.store.results(job, self.grant, cursor=cursor[:-2] + "00"))
        self.now += 86401
        self.assertCode("CURSOR_EXPIRED", lambda: self.store.results(job, self.grant, cursor=cursor))

    def test_deleted_content_cannot_be_recreated_by_a_late_worker(self):
        job = self.start()
        claim = self.store.claim(job, self.grant)
        self.store.delete(job, self.grant)
        self.assertCode("RESULTS_EXPIRED", lambda: self.store.publish(claim, self.grant, {}, b"", []))
        self.assertCode("RESULTS_EXPIRED", lambda: self.start())

    def test_output_budget_reports_an_error_instead_of_empty_success(self):
        job = self.start()
        self.publish(job)
        self.assertCode("OUTPUT_LIMIT_TOO_SMALL", lambda: self.store.results(job, self.grant, output_tokens=1))
        self.assertLessEqual(len(json.dumps(self.store.results(job, self.grant, output_bytes=4000), separators=(",", ":")).encode()), 4000)

    def test_contract_rejects_spoofed_grants_unknown_types_and_ambiguous_starts(self):
        bad = [{"url": "file:///etc/passwd"}, {"url": "http://127.0.0.1/"}, {"url": "http://[::1]/"},
               {"url": "https://example.org/", "principal": "user-a"}, {"url": "https://example.org/", "outputBytes": True},
               {"url": "https://example.org/", "outputTokens": "100"}, {"url": "https://user:pass@example.org/"}]
        for args in bad:
            with self.subTest(args=args), self.assertRaises(CrawlerError):
                request("fetch", args)
        self.assertCode("INVALID_ARGUMENTS", lambda: request("start", {"seeds": ["https://example.org/"], "resumeFromJobId": "old", "idempotencyKey": "key"}))
        self.assertEqual(["fetch", "start", "status", "results", "cancel"], [tool["name"] for tool in tool_definitions()])

    def test_meaningful_queries_and_spa_routes_remain_distinct(self):
        self.assertNotEqual(url_identity("https://EXAMPLE.org:443/App?record=1#/one"), url_identity("https://example.org/App?record=2#/one"))
        self.assertNotEqual(url_identity("https://example.org/App#/one"), url_identity("https://example.org/App#/two"))
        self.assertEqual("https://example.org/", url_identity("https://EXAMPLE.org:443"))


if __name__ == "__main__":
    unittest.main()
