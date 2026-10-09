"""Exercise the actual 34→35 DDL against Room's exported schemas."""
import json
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / 'app/schemas/dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2'


def database(version):
    connection = sqlite3.connect(':memory:')
    connection.execute('PRAGMA foreign_keys=ON')
    schema = json.loads((SCHEMAS / f'{version}.json').read_text())['database']
    for entity in schema['entities']:
        connection.execute(entity['createSql'].replace('${TABLE_NAME}', entity['tableName']))
        for index in entity.get('indices', []):
            connection.execute(index['createSql'].replace('${TABLE_NAME}', entity['tableName']))
    return connection


def upgrade(connection):
    source = (ROOT / 'app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/database/ChatDatabaseV2Migrations.kt').read_text()
    migration = source.split('val MIGRATION_34_35')[1].split('val ALL_MIGRATIONS')[0]
    for ddl in re.findall(r'db.execSQL\("([^"]+)"\)', migration):
        connection.execute(ddl)


def add_chat(connection):
    values = {}
    for column in connection.execute('PRAGMA table_info(chats_v2)'):
        if column[4] is None:
            values[column[1]] = 0 if 'INT' in column[2] else '' if column[3] else None
    values.update(chat_id=1, title='Original chat')
    connection.execute('INSERT INTO chats_v2 (' + ','.join(values) + ') VALUES (' + ','.join('?' for _ in values) + ')', list(values.values()))


class MemoryV2SchemaTest(unittest.TestCase):
    def setUp(self):
        self.db = database(34)
        add_chat(self.db)
        upgrade(self.db)
        self.addCleanup(self.db.close)

    def test_fresh_and_upgraded_shapes_match(self):
        fresh = database(35)
        self.addCleanup(fresh.close)
        names = ('conversation_folders', 'conversation_folder_members', 'memory_facts', 'memory_fact_links', 'memory_pending_changes', 'memory_store_state')
        for name in names:
            self.assertEqual(fresh.execute(f'PRAGMA table_info({name})').fetchall(), self.db.execute(f'PRAGMA table_info({name})').fetchall(), name)
            self.assertEqual(sorted(row[1:] for row in fresh.execute(f'PRAGMA foreign_key_list({name})')), sorted(row[1:] for row in self.db.execute(f'PRAGMA foreign_key_list({name})')), name)

    def test_folder_removal_keeps_chat_and_removes_membership(self):
        self.db.execute("INSERT INTO conversation_folders VALUES ('folder','Research',1,1)")
        self.db.execute("INSERT INTO conversation_folder_members VALUES (1,'folder')")
        self.db.execute("DELETE FROM conversation_folders WHERE id='folder'")
        self.assertEqual([], self.db.execute('SELECT * FROM conversation_folder_members').fetchall())
        self.assertEqual('Original chat', self.db.execute('SELECT title FROM chats_v2').fetchone()[0])

    def test_deleting_chat_keeps_folder_and_removes_membership(self):
        self.db.execute("INSERT INTO conversation_folders VALUES ('folder','Research',1,1)")
        self.db.execute("INSERT INTO conversation_folder_members VALUES (1,'folder')")
        self.db.execute('DELETE FROM chats_v2 WHERE chat_id=1')
        self.assertEqual([], self.db.execute('SELECT * FROM conversation_folder_members').fetchall())
        self.assertEqual('Research', self.db.execute('SELECT name FROM conversation_folders').fetchone()[0])

    def test_membership_requires_both_real_endpoints(self):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO conversation_folder_members VALUES (1,'missing')")

    def test_pending_operation_retries_cannot_duplicate(self):
        self.db.execute("INSERT INTO memory_pending_changes VALUES ('one','operation','old',1,x'01','PENDING',1,NULL)")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO memory_pending_changes VALUES ('two','operation','old',1,x'01','PENDING',1,NULL)")

    def test_fact_delete_cascades_links_without_removing_other_fact(self):
        for id_ in ('old', 'new'):
            self.db.execute("INSERT INTO memory_facts VALUES (?,1,x'01','personal',1,'ACTIVE',1,0,1,NULL,1,NULL,NULL,x'01')", (id_,))
        self.db.execute("INSERT INTO memory_fact_links VALUES ('link','new','old','supersedes',1,NULL,NULL)")
        self.db.execute("DELETE FROM memory_facts WHERE id='old'")
        self.assertEqual([], self.db.execute('SELECT * FROM memory_fact_links').fetchall())
        self.assertEqual('new', self.db.execute('SELECT id FROM memory_facts').fetchone()[0])


if __name__ == '__main__':
    unittest.main()
