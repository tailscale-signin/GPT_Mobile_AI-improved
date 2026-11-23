"""Twelve distinct native repository defects with hidden, seeded behavior checks.

Kotlin state reducers are compiled JVM code, not Android instrumentation. SQLite
checks exercise Room-compatible schema migrations in the SQLite engine; they do
not claim Room annotation-processor coverage. Actions checks parse YAML and run
the declared shell gates in a bounded semantics adapter, not a hosted Actions VM.
"""
import hashlib
import json
from pathlib import Path
import random
import sqlite3

from benchmarks.contracts import digest
from v14.engineering_backend import blob_sha

VERSION = 'gateway.repository-native.v1'
TASK_IDS = (
    'repository-kotlin-stale-generation', 'repository-kotlin-stable-selection',
    'repository-kotlin-event-replay', 'repository-kotlin-state-restore',
    'repository-room-add-default', 'repository-room-rebuild-foreign-key',
    'repository-room-deduplicate-index', 'repository-room-transaction-rollback',
    'repository-ci-required-needs', 'repository-ci-skipped-gate',
    'repository-ci-event-ref', 'repository-ci-fork-permissions',
)
PRESETS = {'quick': (0, 4, 8, 9), 'standard': (0, 4, 8, 9, 1, 5, 6, 10), 'deep': tuple(range(12))}
OUTPUT_SCHEMA = {
    'type': 'object', 'additionalProperties': False, 'required': ['changes', 'diagnosis', 'tests', 'evidence'],
    'properties': {
        'changes': {'type': 'array', 'minItems': 1, 'maxItems': 1, 'items': {'type': 'object'}},
        'diagnosis': {'type': 'object', 'additionalProperties': {'type': 'string'}},
        'tests': {'type': 'array', 'maxItems': 6, 'items': {'type': 'object', 'required': ['input', 'expected']}},
        'evidence': {'type': 'object', 'required': ['tests_executed', 'changed_files']},
    },
}
PROPOSAL_EXAMPLE = {'changes': [{'path': 'exact displayed path', 'expected_blob_sha': 'exact displayed SHA',
                               'edits': [{'old': 'unique displayed anchor', 'new': 'corrected text'}]}],
                    'diagnosis': {'exact displayed path': 'Explain the defect.'}, 'tests': [],
                    'evidence': {'tests_executed': False, 'changed_files': ['exact displayed path']}}

KOTLIN_GENERATION = '''package candidate
fun solve(input: String): String {
    var state = "IDLE"; var generation: String? = null; var value = ""
    for (line in input.lines().filter { it.isNotEmpty() }) {
        val p = line.split('\t', limit = 3)
        when (p[0]) {
            "START" -> { generation = p[1]; state = "LOADING"; value = "" }
            "SUCCESS", "FAIL" -> if (generation == p[1]) {
                state = if (p[0] == "SUCCESS") "READY" else "ERROR"; value = p[2]
            }
            "RESET" -> { generation = null; state = "IDLE"; value = "" }
        }
    }
    return "$state|${generation ?: "-"}|$value"
}
'''
KOTLIN_SELECTION = '''package candidate
fun solve(input: String): String {
    var ids = listOf<String>(); var selected: String? = null
    for (line in input.lines().filter { it.isNotEmpty() }) {
        val p = line.split('\t', limit = 2)
        when (p[0]) {
            "LIST" -> {
                val next = p.getOrElse(1) { "" }.split(',').filter { it.isNotEmpty() }.distinct()
                val oldIndex = ids.indexOf(selected)
                selected = selected?.takeIf { it in next } ?: next.firstOrNull()
                ids = next
            }
            "SELECT" -> if (p[1] in ids) selected = p[1]
        }
    }
    return selected ?: "-"
}
'''
KOTLIN_REPLAY = '''package candidate
fun solve(input: String): String {
    val seen = mutableSetOf<String>(); val pending = linkedMapOf<String, String>()
    var delivered = 0
    for (line in input.lines().filter { it.isNotEmpty() }) {
        val p = line.split('\t', limit = 3)
        when (p[0]) {
            "EMIT" -> if (seen.add(p[1])) pending[p[1]] = p[2]
            "CONSUME" -> if (pending.remove(p[1]) != null) delivered++
            "ROTATE" -> { /* retained state survives UI recreation */ }
        }
    }
    return pending.entries.joinToString(",") { "${it.key}:${it.value}" } + "|$delivered"
}
'''
KOTLIN_RESTORE = '''package candidate
fun solve(input: String): String {
    val saved = mutableMapOf<String, String>(); var active: String? = null; var text = ""
    for (line in input.lines().filter { it.isNotEmpty() }) {
        val p = line.split('\t', limit = 3)
        when (p[0]) {
            "OPEN" -> {
                active?.let { saved[it] = text }; active = p[1]
                text = saved[p[1]] ?: p[2]
            }
            "EDIT" -> if (active != null) text = p.getOrElse(1) { "" }
            "CLOSE" -> { active?.let { saved[it] = text }; active = null; text = "" }
        }
    }
    return (active ?: "-") + "|" + text
}
'''

SQL_DEFAULT = 'ALTER TABLE profiles ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1;\nPRAGMA user_version=2;\n'
SQL_FOREIGN = '''BEGIN IMMEDIATE;
CREATE TABLE messages_new(id INTEGER PRIMARY KEY, thread_id INTEGER NOT NULL REFERENCES threads(id) ON DELETE CASCADE, body TEXT NOT NULL);
INSERT INTO messages_new SELECT id,thread_id,body FROM messages;
DROP TABLE messages;
ALTER TABLE messages_new RENAME TO messages;
PRAGMA user_version=2;
COMMIT;
'''
SQL_DEDUP = '''BEGIN IMMEDIATE;
UPDATE users SET email=lower(trim(email));
DELETE FROM users WHERE id NOT IN (SELECT min(id) FROM users GROUP BY email COLLATE NOCASE);
CREATE UNIQUE INDEX users_email ON users(email COLLATE NOCASE);
PRAGMA user_version=2;
COMMIT;
'''
SQL_ATOMIC = '''BEGIN IMMEDIATE;
CREATE TABLE ledger_new(id INTEGER PRIMARY KEY, cents INTEGER NOT NULL CHECK(cents >= 0));
INSERT INTO ledger_new SELECT id,cents FROM ledger;
DROP TABLE ledger;
ALTER TABLE ledger_new RENAME TO ledger;
PRAGMA user_version=2;
COMMIT;
'''

CI_GATE = '''name: Required checks
on: [push, pull_request]
permissions:
  contents: read
jobs:
  build:
    runs-on: ubuntu-latest
    steps: [{run: 'true'}]
  unit:
    runs-on: ubuntu-latest
    steps: [{run: 'true'}]
  lint:
    runs-on: ubuntu-latest
    steps: [{run: 'true'}]
  gate:
    needs: [build, unit, lint]
    if: ${{ always() }}
    runs-on: ubuntu-latest
    steps:
      - name: Require every declared lane
        env:
          RESULTS: ${{ toJSON(needs) }}
        run: |
          python3 - <<'PY'
          import json, os, sys
          result = json.loads(os.environ['RESULTS'])
          sys.exit(0 if set(result) == {'build', 'unit', 'lint'} and all(row['result'] == 'success' for row in result.values()) else 1)
          PY
'''
CI_EVENT = '''name: Publish event gate
on: [push, pull_request, workflow_dispatch]
permissions:
  contents: read
jobs:
  gate:
    if: ${{ github.event_name == 'push' && github.ref == 'refs/heads/main' }}
    runs-on: ubuntu-latest
    steps: [{run: 'true'}]
'''
CI_PERMISSIONS = '''name: Fork permission boundary
on: [push, pull_request, pull_request_target]
permissions:
  contents: read
jobs:
  gate:
    runs-on: ubuntu-latest
    steps: [{run: 'true'}]
  publish:
    if: ${{ github.event_name == 'push' && github.ref == 'refs/heads/main' }}
    permissions:
      contents: write
      id-token: write
    runs-on: ubuntu-latest
    steps: [{run: 'true'}]
'''


def _kotlin_expected(index, text):
    rows = [line.split('\t', 2) for line in text.split('\n') if line]
    if index == 0:
        state, generation, value = 'IDLE', None, ''
        for row in rows:
            if row[0] == 'START':
                generation, state, value = row[1], 'LOADING', ''
            elif row[0] in {'SUCCESS', 'FAIL'} and row[1] == generation:
                state, value = ('READY' if row[0] == 'SUCCESS' else 'ERROR'), row[2]
            elif row[0] == 'RESET':
                state, generation, value = 'IDLE', None, ''
        return '|'.join((state, generation if generation is not None else '-', value))
    if index == 1:
        ids, selected = [], None
        for row in rows:
            if row[0] == 'LIST':
                ids = list(dict.fromkeys(value for value in row[1].split(',') if value))
                selected = selected if selected in ids else next(iter(ids), None)
            elif row[0] == 'SELECT' and row[1] in ids:
                selected = row[1]
        return selected or '-'
    if index == 2:
        seen, pending, delivered = set(), {}, 0
        for row in rows:
            if row[0] == 'EMIT' and row[1] not in seen:
                seen.add(row[1]); pending[row[1]] = row[2]
            elif row[0] == 'CONSUME' and row[1] in pending:
                del pending[row[1]]; delivered += 1
        return ','.join(key + ':' + value for key, value in pending.items()) + '|' + str(delivered)
    saved, active, text = {}, None, ''
    for row in rows:
        if row[0] == 'OPEN':
            if active is not None:
                saved[active] = text
            active = row[1]; text = saved.get(active, row[2])
        elif row[0] == 'EDIT' and active is not None:
            text = row[1]
        elif row[0] == 'CLOSE':
            if active is not None:
                saved[active] = text
            active, text = None, ''
    return (active or '-') + '|' + text


def _kotlin_vectors(index, seed):
    rng = random.Random(seed)
    a, b, c = [f'p{rng.randrange(10000, 99999)}' for _ in range(3)]
    n = str(rng.randrange(20, 100)); m = str(int(n) + 1)
    collections = (
        ['', f'START\t{n}', f'START\t{n}\nSUCCESS\t{n}\t{a}',
         f'START\t{n}\nSTART\t{m}\nSUCCESS\t{n}\t{a}',
         f'START\t{n}\nSTART\t{m}\nSUCCESS\t{m}\t{b}\nFAIL\t{n}\t{c}',
         f'START\t{n}\nRESET\nSUCCESS\t{n}\t{a}',
         f'START\t{n}\nFAIL\t{n}\t{b}', f'FAIL\t{n}\t{c}', 'SUCCESS\t-\tignored'],
        ['', f'LIST\t{a},{b},{c}', f'LIST\t{a},{b},{c}\nSELECT\t{b}\nLIST\t{c},{a},{b}',
         f'LIST\t{a},{b}\nSELECT\t{b}\nLIST\t{a},{c}',
         f'LIST\t{a},{b}\nSELECT\t{b}\nLIST\t', f'LIST\t{a}\nSELECT\t{c}',
         f'LIST\t{a},{a},{b}\nSELECT\t{b}\nLIST\t{b},{a}'],
        ['', f'EMIT\t{a}\tfirst', f'EMIT\t{a}\tfirst\nEMIT\t{a}\tsecond',
         f'EMIT\t{a}\tfirst\nCONSUME\t{a}\nROTATE\nEMIT\t{a}\tsecond',
         f'EMIT\t{a}\tfirst\nEMIT\t{b}\tsecond\nCONSUME\t{a}\nCONSUME\t{a}',
         f'CONSUME\t{a}\nEMIT\t{a}\tnew\nROTATE\nEMIT\t{a}\tduplicate'],
        ['', f'OPEN\t{a}\tdefault', f'OPEN\t{a}\tdefault\nEDIT\t\nCLOSE\nOPEN\t{a}\tfallback',
         f'OPEN\t{a}\tdefault\nEDIT\t  \nOPEN\t{b}\tb\nOPEN\t{a}\tfallback',
         f'OPEN\t{a}\tfirst\nEDIT\t{b}\nOPEN\t{c}\tother\nOPEN\t{a}\tfallback',
         f'EDIT\tlost\nOPEN\t{a}\tdefault\nCLOSE'],
    )
    return [{'events': text} for text in collections[index]]


def sql_observe(source, case):
    """Trusted reference oracle only. Candidate SQL never runs on the host."""
    connection = sqlite3.connect(':memory:')
    try:
        connection.execute('PRAGMA foreign_keys=ON')
        connection.executescript(case['setup'])
        error = False
        try:
            connection.executescript(source)
        except sqlite3.Error:
            error = True
            if connection.in_transaction:
                connection.rollback()
        tables = [row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")]
        schemas, rows = {}, {}
        for table in tables:
            quoted = '"' + table.replace('"', '""') + '"'
            schemas[table] = {'columns': [list(row[1:]) for row in connection.execute('PRAGMA table_info(' + quoted + ')')],
                              'foreignKeys': [list(row[2:]) for row in connection.execute('PRAGMA foreign_key_list(' + quoted + ')')]}
            rows[table] = [list(row) for row in connection.execute('SELECT * FROM ' + quoted + ' ORDER BY 1')]
        checks = []
        for statement in case.get('probes', []):
            try:
                cursor = connection.execute(statement)
                checks.append({'ok': True, 'rows': [list(row) for row in cursor.fetchall()]})
            except sqlite3.Error:
                checks.append({'ok': False})
        return {'error': error, 'schema': schemas, 'rows': rows, 'checks': checks,
                'version': connection.execute('PRAGMA user_version').fetchone()[0]}
    finally:
        connection.close()


def _sql_vectors(index, seed):
    n = random.Random(seed).randrange(100, 10000)
    if index == 4:
        return [{'setup': "CREATE TABLE profiles(id TEXT PRIMARY KEY,name TEXT NOT NULL); PRAGMA user_version=1;" + rows,
                 'probes': ["INSERT INTO profiles(id,name) VALUES('new','New')", "SELECT enabled FROM profiles WHERE id='new'",
                            "INSERT INTO profiles(id,name,enabled) VALUES('null','Null',NULL)"]}
                for rows in ('', f"INSERT INTO profiles VALUES('p{n}','Profile {n}'),('empty','');")]
    if index == 5:
        return [{'setup': 'CREATE TABLE threads(id INTEGER PRIMARY KEY); CREATE TABLE messages(id INTEGER PRIMARY KEY,thread_id INTEGER NOT NULL,body TEXT NOT NULL); PRAGMA user_version=1;' + rows,
                 'probes': [f"INSERT INTO messages VALUES(99999,99999,'orphan')", f'DELETE FROM threads WHERE id={n}', 'SELECT * FROM messages ORDER BY id', 'PRAGMA foreign_key_check']}
                for rows in ('', f"INSERT INTO threads VALUES({n}),({n+1}); INSERT INTO messages VALUES(1,{n},'first'),(2,{n},'second'),(3,{n+1},'keep');")]
    if index == 6:
        return [{'setup': 'CREATE TABLE users(id INTEGER PRIMARY KEY,email TEXT NOT NULL,created_at INTEGER NOT NULL); PRAGMA user_version=1;' + rows,
                 'probes': [f"INSERT INTO users VALUES(99,'USER{n}@EXAMPLE.COM',99)", 'SELECT id,email FROM users ORDER BY id']}
                for rows in (f"INSERT INTO users VALUES(1,'user{n}@example.com',10);",
                             f"INSERT INTO users VALUES(1,' User{n}@example.com ',10),(2,'user{n}@EXAMPLE.COM',20),(3,'other@example.com',30);")]
    return [{'setup': 'CREATE TABLE ledger(id INTEGER PRIMARY KEY,cents INTEGER NOT NULL); PRAGMA user_version=1;' + rows,
             'probes': ["SELECT * FROM ledger ORDER BY id", 'INSERT INTO ledger VALUES(900001,-1)', 'INSERT INTO ledger VALUES(900002,0)']}
            for rows in ('', f'INSERT INTO ledger VALUES(1,{n}),(2,0);', f'INSERT INTO ledger VALUES(1,{n}),(2,-1);')]


def _ci_vectors(index, seed):
    if index in {8, 9}:
        values = [{'build': 'success', 'unit': 'success', 'lint': 'success'}]
        for lane in ('build', 'unit', 'lint'):
            for state in ('failure', 'skipped', 'cancelled'):
                values.append(dict(values[0], **{lane: state}))
        return [{'needs': value, 'github': {'event_name': 'push', 'ref': 'refs/heads/main'}} for value in values]
    branch = 'branch-' + str(seed % 997)
    return [{'needs': {}, 'github': {'event_name': event, 'ref': ref, 'event': {'pull_request': {'head': {'repo': {'fork': fork}}}}}}
            for event, ref, fork in (
                ('push', 'refs/heads/main', False), ('push', 'refs/heads/' + branch, False),
                ('pull_request', 'refs/pull/17/merge', True), ('pull_request', 'refs/heads/main', False),
                ('pull_request_target', 'refs/heads/main', True), ('workflow_dispatch', 'refs/heads/main', False),
                ('push', 'refs/tags/main', False))]


def _ci_expected(index, case):
    read = {'contents': 'read'}
    if index in {8, 9}:
        ok = all(value == 'success' for value in case['needs'].values())
        return {'gate': {'result': 'success' if ok else 'failure', 'permissions': read}}
    allowed = case['github']['event_name'] == 'push' and case['github']['ref'] == 'refs/heads/main'
    result = {'gate': {'result': 'success' if allowed else 'skipped', 'permissions': read}}
    if index == 11:
        result = {'gate': {'result': 'success', 'permissions': read},
                  'publish': {'result': 'success' if allowed else 'skipped', 'permissions': {'contents': 'write', 'id-token': 'write'}}}
    return result


def grader_digest():
    root = Path(__file__).resolve().parents[1]
    # Seal every installed benchmark module, including external dispatch, live
    # readback, admission/store and shared graders as they participate in receipts.
    paths = [*sorted((root / 'benchmarks').glob('*.py')),
             *[root / name for name in ('v14/engineering_backend.py', 'v14/engineering_workspace.py',
                                       'v14/engineering_validation.py', 'v14/validation_host.py')]]
    return digest({'version': VERSION, 'files': {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}})


def task_spec(task_id, seed=17):
    if task_id not in TASK_IDS or type(seed) is not int or not 0 <= seed <= 2**31 - 1:
        raise ValueError('Choose a known repository task and bounded seed.')
    index = TASK_IDS.index(task_id)
    specifications = (
        ('Reject stale asynchronous completion', KOTLIN_GENERATION, 'generation == p[1]', 'true',
         'Apply SUCCESS/FAIL only to the latest START generation. RESET invalidates outstanding work. Return STATE|generation|value; initial/reset is IDLE|-|.'),
        ('Keep saved profile selection stable', KOTLIN_SELECTION, 'selected?.takeIf { it in next } ?: next.firstOrNull()', 'next.getOrNull(oldIndex) ?: next.firstOrNull()',
         'LIST replaces unique ordered profile IDs. Preserve selection by UID across reordering; if removed choose first or null. SELECT of an absent UID is ignored. Return UID or -.'),
        ('Retain one-shot event consumption', KOTLIN_REPLAY, '/* retained state survives UI recreation */', 'seen.clear()',
         'Deduplicate EMIT by event ID for this retained session, including after CONSUME and ROTATE. ROTATE preserves seen and pending. Consume each queued event once. Return ordered pending id:value entries comma-separated, then |deliveredCount.'),
        ('Restore deliberately empty editor drafts', KOTLIN_RESTORE, 'saved[p[1]] ?: p[2]', 'saved[p[1]]?.takeIf { it.isNotBlank() } ?: p[2]',
         'OPEN saves the current draft and restores the requested document by ID. Empty/whitespace drafts are valid saved values. EDIT affects an open document; CLOSE saves it. Return activeId|text or -|.'),
        ('Migrate persisted profile defaults', SQL_DEFAULT, 'INTEGER NOT NULL DEFAULT 1', 'INTEGER DEFAULT 0',
         'Migrate profiles(id TEXT PRIMARY KEY,name TEXT NOT NULL), version1 to version2. Add enabled INTEGER NOT NULL DEFAULT1. Preserve all rows; future omitted values default to1 and explicit NULL is rejected.'),
        ('Rebuild a Room foreign key without data loss', SQL_FOREIGN, 'ON DELETE CASCADE', 'ON DELETE RESTRICT',
         'Migrate messages(id INTEGER PRIMARY KEY,thread_id INTEGER NOT NULL,body TEXT NOT NULL) to enforce threads(id), ON DELETE CASCADE. Preserve IDs/data; orphan inserts fail and deleting a thread removes only its messages. Set version2.'),
        ('Normalize duplicates before adding a unique index', SQL_DEDUP, 'lower(trim(email))', 'email',
         'Normalize user emails with lower(trim(email)); retain smallest id for duplicates. Preserve retained created_at. Enforce future email uniqueness case-insensitively and set version2. IDs,email,created_at retain their types.'),
        ('Roll back a failed migration atomically', SQL_ATOMIC, 'BEGIN IMMEDIATE;\n', '',
         'Rebuild ledger with cents INTEGER NOT NULL CHECK(cents>=0), preserve rows, set version2. Invalid old data must leave version1 and the original schema/data, with no partial table. The executor rolls back an open transaction after an SQL error; it cannot undo already committed statements. The migration owns BEGIN/COMMIT.'),
        ('Include every required CI dependency', CI_GATE, 'needs: [build, unit, lint]', 'needs: [build, unit]',
         'The gate job must run after all build/unit/lint results. It succeeds only when all three succeeded; failures, cancelled and skipped inputs produce a failed gate. Missing dependencies must not silently pass.'),
        ('Keep skipped CI from becoming a skipped required gate', CI_GATE, 'if: ${{ always() }}', 'if: ${{ success() }}',
         'Always run the gate even when a required dependency is skipped, cancelled or fails. Gate success requires build/unit/lint all success; otherwise execute and fail. A skipped gate is not acceptable evidence.'),
        ('Bind publication to exact event and branch', CI_EVENT, "github.event_name == 'push' && github.ref == 'refs/heads/main'", "github.ref == 'refs/heads/main'",
         'Run gate only for push on refs/heads/main. All PR, pull_request_target, manual and tag events must skip even if ref resembles main. Keep contents read permission.'),
        ('Keep fork-review jobs read only', CI_PERMISSIONS, 'permissions:\n  contents: read', 'permissions:\n  contents: write\n  id-token: write',
         'The gate review job runs read-only for every event (contents read, no other grants). Only publish has contents/id-token write and only on push refs/heads/main. Fork and pull_request_target reviews must never inherit write permissions.'),
    )
    title, reference, old, new, contract = specifications[index]
    language = 'kotlin' if index < 4 else 'sqlite' if index < 8 else 'actions'
    path = {'kotlin': 'Task.kt', 'sqlite': 'migration.sql', 'actions': 'workflow.yml'}[language]
    vectors = _kotlin_vectors(index, seed) if index < 4 else _sql_vectors(index, seed) if index < 8 else _ci_vectors(index, seed)
    expected = [_kotlin_expected(index, vector['events']) if index < 4 else sql_observe(reference, vector) if index < 8 else _ci_expected(index, vector) for vector in vectors]
    source = reference.replace(old, new, 1)
    scope = {'kotlin': 'COMPILED_KOTLIN_STATE', 'sqlite': 'SQLITE_ROOM_SCHEMA', 'actions': 'ACTIONS_YAML_GATE_SIMULATION'}[language]
    task = dict(id=task_id, problemId=task_id, family=task_id, title=title, capstone=False, taskPack='repository',
                language=language, seed=seed, contract=contract, sources={path: source}, referenceSources={path: reference},
                vectors=vectors, expected=expected, executionScope=scope, outputSchema=OUTPUT_SCHEMA,
                hiddenTestsDigest=digest(vectors), oracleDigest=digest([VERSION, expected]),
                proposalSchemaDigest=digest(OUTPUT_SCHEMA), graderDigest=grader_digest())
    task['manifestDigest'] = task['taskDigest'] = digest(task)
    task['prompt'] = (f'Repository-native task: {title}\nScope: {scope}. This is a bounded repository fixture, not a full Android or hosted GitHub certification.\n'
        f'Contract: {contract}\nChange only {path}. Kotlin signature is package candidate, fun solve(input:String):String. Events are tab-separated lines; values contain no tabs/newlines. '
        'SQL runs in SQLite with foreign_keys enabled. Workflow YAML is parsed, expressions evaluated and run steps executed in /bin/sh; no actions downloads, uses steps or network are supported. '
        'Workflow contract supports only name/on/permissions/jobs/env; jobs name/runs-on/needs/if/steps/permissions/env; steps name/run/env/if/shell(sh). Unsupported matrices, services, continue-on-error and shell types are rejected. '
        'Supported expressions: needs, github context; ==, !=, &&, ||, !; always(), success(), failure(), cancelled(), contains(), toJSON(), fromJSON(). '
        'Return exactly one JSON patch proposal matching this example: ' + json.dumps(PROPOSAL_EXAMPLE) + '\n'
        f'FILE {path}\nBLOB SHA {blob_sha(source)}\n{source}')
    return task


def task_manifest(preset='quick', seed=17):
    if preset not in PRESETS:
        raise ValueError('Choose Quick, Standard or Deep repository coverage.')
    return [task_spec(TASK_IDS[index], seed) for index in PRESETS[preset]]
