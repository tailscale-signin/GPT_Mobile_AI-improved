"""Exercise production retention SQL and the 3->4 migration without an Android SDK."""
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
FEATURE = ROOT / 'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/research/feature'


class ResearchRetentionSQLTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        schema = json.loads((ROOT / 'app/schemas/dev.chungjungsoo.gptmobile.data.research.feature.ResearchDatabase/3.json').read_text())['database']
        for entity in schema['entities']:
            self.db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        migration = (FEATURE / 'ResearchRetentionMigration.kt').read_text()
        for statement in re.findall(r'db.execSQL\("(.*?)"\)', migration):
            self.db.execute(statement)
        dao = (FEATURE / 'ResearchDatabase.kt').read_text()
        self.eviction_sql = re.search(r'@Query\("(SELECT \* FROM research_archive WHERE protectionReason.*?)"\)\s+suspend fun oldestExcess', dao).group(1)

    def tearDown(self):
        self.db.close()

    def insert(self, key, protection=None, hold=0):
        self.db.execute('INSERT INTO research_archive (workId,archiveSequence,firstArchivedAt,latestVersionId,primaryTopicId,protectionReason,holdUntil) VALUES (?,?,?,?,?,?,?)', (str(key), key, key, 'v' + str(key), 'topic', protection, hold))

    def candidates(self, keep=1000, now=100):
        return [row[0] for row in self.db.execute(self.eviction_sql, {'keep': keep, 'now': now})]

    def test_protected_growth_is_additional_to_unexamined_limit(self):
        for i in range(1, 1501):
            self.insert(i, 'EVIDENCE_CHECKED')
        for i in range(1501, 2502):
            self.insert(i)
        self.assertEqual(['1501'], self.candidates())
        self.assertEqual(2501, self.db.execute('SELECT count(*) FROM research_archive').fetchone()[0])

    def test_clear_unexamined_preserves_legacy_accepted_and_active_holds(self):
        self.insert(1, 'LEGACY_HOLD')
        self.insert(2, 'EVIDENCE_CHECKED')
        self.insert(3, hold=200)
        self.insert(4)
        self.assertEqual(['4'], self.candidates(0))
        self.assertEqual(['3', '4'], self.candidates(0, 201))

    def test_protection_promotion_is_rechecked_in_the_same_transaction(self):
        self.insert(1)
        self.insert(2)
        self.assertEqual(['1'], self.candidates(1))
        self.db.execute("UPDATE research_archive SET protectionReason='EVIDENCE_CHECKED' WHERE workId='1'")
        self.assertEqual([], self.candidates(1))

    def test_capture_rank_and_selected_review_sql_are_valid(self):
        dao = (FEATURE / 'ResearchDatabase.kt').read_text()
        query = re.search(r'@Query\("(SELECT w.workId.*?)"\)\s+suspend fun page', dao).group(1)
        query = query.replace('\\\\', '\\')
        self.db.execute(query, {'topic': '', 'query': '', 'reviewed': 0, 'assessed': 0, 'state': '', 'now': 100, 'rank': 0, 'limit': 40, 'offset': 0}).fetchall()

    def test_legacy_review_backfill_is_held_and_manual_deletion_wins(self):
        db = sqlite3.connect(':memory:')
        schema = json.loads((ROOT / 'app/schemas/dev.chungjungsoo.gptmobile.data.research.feature.ResearchDatabase/3.json').read_text())['database']
        for entity in schema['entities']:
            db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        def insert(table, overrides):
            values = {}
            for _, name, affinity, required, default, _ in db.execute('PRAGMA table_info(' + table + ')'):
                if required and default is None:
                    values[name] = 0 if affinity == 'INTEGER' else ''
            values.update(overrides)
            db.execute('INSERT INTO ' + table + '(' + ','.join(values) + ') VALUES (' + ','.join('?' for _ in values) + ')', tuple(values.values()))
        for work in ('retained', 'deleted'):
            insert('research_works', {'workId': work})
            insert('research_versions', {'versionId': 'v-' + work, 'workId': work})
            insert('research_study_reviews', {'reviewId': 'r-' + work, 'workId': work, 'versionId': 'v-' + work, 'createdAt': 100})
        db.execute("INSERT INTO research_tombstones (`key`,epoch) VALUES ('archive:deleted',200)")
        for statement in re.findall(r'db.execSQL\("(.*?)"\)', (FEATURE / 'ResearchRetentionMigration.kt').read_text()):
            db.execute(statement)
        self.assertEqual([('retained', 'LEGACY_HOLD')], db.execute('SELECT workId,protectionReason FROM research_archive').fetchall())
        self.assertEqual([('retained', 'v-retained')], db.execute("SELECT rootId,versionId FROM research_body_roots WHERE rootKind='assessment'").fetchall())
        db.close()


if __name__ == '__main__':
    unittest.main()
