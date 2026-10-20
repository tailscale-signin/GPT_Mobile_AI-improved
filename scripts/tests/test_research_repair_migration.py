"""Execute the additive repair migration against real SQLite and Room schemas."""
import re
import unittest
from test_research_examination_lifecycle_migration import database, FEATURE


class ResearchRepairMigrationTest(unittest.TestCase):
    def test_schema_and_retained_capture(self):
        with database(5) as old, database(6) as fresh:
            old.execute("INSERT INTO research_works(workId,title,canonicalUrl,publicationType,peerReview) VALUES ('retained','Trial','https://example.org','unknown','UNKNOWN')")
            statements = re.findall(r'db.execSQL\("([^"\n]+)"\)', (FEATURE / 'ResearchRepairMigration.kt').read_text())
            self.assertEqual(6, len(statements))
            for sql in statements:
                old.execute(sql)
            for table in ('research_source_validations', 'research_identity_resolutions', 'research_repair_cursors'):
                for pragma in ('table_info', 'foreign_key_list', 'index_list'):
                    self.assertEqual(old.execute(f'PRAGMA {pragma}({table})').fetchall(), fresh.execute(f'PRAGMA {pragma}({table})').fetchall())
            self.assertEqual('Trial', old.execute("SELECT title FROM research_works WHERE workId='retained'").fetchone()[0])
            self.assertEqual([], old.execute('PRAGMA foreign_key_check').fetchall())


if __name__ == '__main__':
    unittest.main()
