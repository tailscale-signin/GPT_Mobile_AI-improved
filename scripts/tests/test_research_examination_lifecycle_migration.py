"""Run the shipped lifecycle migration on real SQLite databases and compare Room schemas."""
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
FEATURE = ROOT / 'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/research/feature'
SCHEMAS = ROOT / 'app/schemas/dev.chungjungsoo.gptmobile.data.research.feature.ResearchDatabase'


def database(version):
    db = sqlite3.connect(':memory:')
    db.execute('PRAGMA foreign_keys=ON')
    for entity in json.loads((SCHEMAS / f'{version}.json').read_text())['database']['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity.get('indices', []):
            db.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    return db


def migrate(db):
    statements = re.findall(r'db.execSQL\("([^"\n]+)"\)', (FEATURE / 'ResearchExaminationLifecycleMigration.kt').read_text())
    if len(statements) != 9:
        raise AssertionError('Review migration harness after SQL structure changes')
    for sql in statements:
        db.execute(sql)


class ExaminationLifecycleMigrationTest(unittest.TestCase):
    def test_schema_matches_room_and_preserves_legacy_checkpoint(self):
        with database(4) as old, database(5) as fresh:
            old.execute("INSERT INTO research_works(workId,title,canonicalUrl,publicationType,peerReview) VALUES ('work','Trial','https://example.org','unknown','UNKNOWN')")
            old.execute("INSERT INTO research_jobs(jobId,dedupKey,workId,origin,chatId,profileUid,authority,policyHash,stage,checkpointJson,updatedAt,lease) VALUES ('job','key','work','USER_EXAMINE',0,'profile','public','policy','READING','retained-chunk-3',40,7)")
            migrate(old)
            for table in ('research_jobs', 'research_job_transitions'):
                for pragma in ('table_info', 'foreign_key_list', 'index_list'):
                    self.assertEqual(old.execute(f'PRAGMA {pragma}({table})').fetchall(), fresh.execute(f'PRAGMA {pragma}({table})').fetchall())
            self.assertEqual(('retained-chunk-3', 7, 0, 0, None), old.execute('SELECT checkpointJson,lease,lastHeartbeatAt,deadlineAt,workerId FROM research_jobs').fetchone())
            self.assertEqual([], old.execute('PRAGMA foreign_key_check').fetchall())


if __name__ == '__main__':
    unittest.main()
