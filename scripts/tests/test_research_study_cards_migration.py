"""Compare the executable 2→3 migration with Room's exported schema."""
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / 'app/schemas/dev.chungjungsoo.gptmobile.data.research.feature.ResearchDatabase'


def database(version):
    connection = sqlite3.connect(':memory:')
    connection.execute('PRAGMA foreign_keys=ON')
    for entity in json.loads((SCHEMAS / f'{version}.json').read_text())['database']['entities']:
        connection.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity.get('indices', []):
            connection.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    return connection


def migrate(connection):
    source = ROOT / 'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/research/feature/ResearchStudyCardsMigration.kt'
    queries = re.findall(r'db.execSQL\("([^"\n]+)"\)', source.read_text())
    if len(queries) != 6:
        raise AssertionError('Review this harness if the migration SQL structure changes')
    for query in queries:
        connection.execute(query)


class ResearchStudyCardsMigrationTest(unittest.TestCase):
    def test_schema_matches_room_columns_foreign_keys_and_indexes(self):
        with database(2) as old, database(3) as fresh:
            migrate(old)
            for table in ('research_study_reviews', 'research_work_studies'):
                for pragma in ('table_info', 'foreign_key_list', 'index_list'):
                    self.assertEqual(old.execute(f'PRAGMA {pragma}({table})').fetchall(), fresh.execute(f'PRAGMA {pragma}({table})').fetchall())
            self.assertEqual([], old.execute('PRAGMA foreign_key_check').fetchall())

    def test_preserves_and_links_existing_study_family_without_scoring_old_records(self):
        with database(2) as old:
            old.execute("INSERT INTO research_study_families VALUES ('family','NCT00000001','registration',1)")
            old.execute("INSERT INTO research_works(workId,title,canonicalUrl,publicationType,peerReview,studyFamilyId) VALUES ('work','Trial','https://example.org/trial','journal-article','UNKNOWN','family')")
            migrate(old)
            self.assertEqual([('work', 'family', 'registration', 1)], old.execute('SELECT * FROM research_work_studies').fetchall())
            self.assertEqual([('Trial',)], old.execute('SELECT title FROM research_works').fetchall())
            self.assertEqual(0, old.execute('SELECT COUNT(*) FROM research_study_reviews').fetchone()[0])


if __name__ == '__main__':
    unittest.main()
