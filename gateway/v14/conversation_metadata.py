"""Principal-owned organization of canonical transcripts; never a second history store."""
import json

from v14.conversations import ConversationConflict


def schema(db):
    db.execute('CREATE TABLE IF NOT EXISTS gateway_conversation_metadata (conversation_id TEXT PRIMARY KEY, revision INTEGER NOT NULL, archived INTEGER NOT NULL DEFAULT 0, favorite INTEGER NOT NULL DEFAULT 0, tags TEXT NOT NULL DEFAULT \'[]\', updated_at REAL NOT NULL)')
    db.execute('CREATE TABLE IF NOT EXISTS gateway_message_bookmarks (sequence INTEGER PRIMARY KEY AUTOINCREMENT, conversation_id TEXT NOT NULL, message_id TEXT NOT NULL UNIQUE, created_at REAL NOT NULL)')
    db.execute('CREATE INDEX IF NOT EXISTS gateway_bookmark_conversation ON gateway_message_bookmarks(conversation_id)')
    db.execute("INSERT INTO gateway_installation VALUES('conversation_metadata_schema','1') ON CONFLICT(key) DO UPDATE SET value=excluded.value WHERE value!=excluded.value")


def metadata(db, conversation_id):
    row = db.execute('SELECT * FROM gateway_conversation_metadata WHERE conversation_id=?', (conversation_id,)).fetchone()
    return {'metadata_revision': row['revision'] if row else 0,
            'archived': bool(row['archived']) if row else False,
            'favorite': bool(row['favorite']) if row else False,
            'tags': json.loads(row['tags']) if row else []}


def search_query(value):
    if not isinstance(value, str) or len(value) > 256 or any(ord(c) < 32 for c in value):
        raise ValueError('Search must contain at most 256 printable characters')
    return value.strip()


def like_query(value):
    return '%' + value.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_') + '%'


def update(conversations, principal, conversation_id, payload):
    fields = {'title', 'archived', 'favorite', 'tags'}
    if not isinstance(payload, dict) or set(payload) - fields - {'expected_metadata_revision'} or not set(payload) & fields:
        raise ValueError('Expected conversation metadata')
    revision = payload.get('expected_metadata_revision')
    if type(revision) is not int or revision < 0:
        raise ValueError('Expected a metadata revision')
    for name in ('archived', 'favorite'):
        if name in payload and type(payload[name]) is not bool:
            raise ValueError(name + ' must be boolean')
    title = payload.get('title')
    if 'title' in payload and (not isinstance(title, str) or not 1 <= len(title.strip()) <= 120 or any(ord(c) < 32 for c in title)):
        raise ValueError('Title must contain 1 to 120 printable characters')
    tags = payload.get('tags')
    if 'tags' in payload and (not isinstance(tags, list) or len(tags) > 12 or any(not isinstance(tag, str) or not 1 <= len(tag.strip()) <= 32 or any(ord(c) < 32 for c in tag) for tag in tags)):
        raise ValueError('Use at most 12 short tags')

    def save(db):
        row = db.execute('SELECT id FROM gateway_conversations WHERE id=? AND principal=?', (conversation_id, principal)).fetchone()
        if not row:
            return None
        current = metadata(db, conversation_id)
        if current['metadata_revision'] != revision:
            raise ConversationConflict('Saved conversation details changed. Refresh before editing.',
                                       details={'current_metadata_revision': current['metadata_revision']})
        current.update({key: payload[key] for key in ('archived', 'favorite') if key in payload})
        if tags is not None:
            current['tags'] = list(dict.fromkeys(tag.strip() for tag in tags))
        if title is not None:
            db.execute('UPDATE gateway_conversations SET title=? WHERE id=?', (title.strip(), conversation_id))
        db.execute('INSERT INTO gateway_conversation_metadata VALUES(?,?,?,?,?,?) ON CONFLICT(conversation_id) DO UPDATE SET revision=excluded.revision,archived=excluded.archived,favorite=excluded.favorite,tags=excluded.tags,updated_at=excluded.updated_at',
                   (conversation_id, revision + 1, int(current['archived']), int(current['favorite']), json.dumps(current['tags']), conversations.now()))
        return dict(current, metadata_revision=revision + 1, conversation_id=conversation_id,
                    title=db.execute('SELECT title FROM gateway_conversations WHERE id=?', (conversation_id,)).fetchone()[0])
    return conversations.tx(save)


def bookmark(conversations, principal, conversation_id, message_id, saved):
    if type(saved) is not bool:
        raise ValueError('saved must be boolean')
    def save(db):
        row = db.execute('SELECT m.id FROM gateway_messages m JOIN gateway_conversations c ON c.id=m.conversation_id WHERE c.id=? AND c.principal=? AND m.id=?',
                         (conversation_id, principal, message_id)).fetchone()
        if not row:
            return None
        if saved:
            db.execute('INSERT OR IGNORE INTO gateway_message_bookmarks(conversation_id,message_id,created_at) VALUES(?,?,?)',
                       (conversation_id, message_id, conversations.now()))
        else:
            db.execute('DELETE FROM gateway_message_bookmarks WHERE conversation_id=? AND message_id=?', (conversation_id, message_id))
        return {'conversation_id': conversation_id, 'message_id': message_id, 'saved': saved}
    return conversations.tx(save)


def saved_messages(conversations, principal, query='', cursor=None, limit=50, engineering_allowed=True, generation_allowed=True):
    query = search_query(query)
    if type(limit) is not int or not 1 <= limit <= 100:
        raise ValueError('Invalid page size')
    def read(db):
        gateway_id = conversations.identity(db)
        scope = [gateway_id, principal, 'bookmarks', query, engineering_allowed, generation_allowed]
        upper = db.execute('SELECT COALESCE(MAX(sequence),0) FROM gateway_message_bookmarks').fetchone()[0]
        before = upper + 1
        if cursor:
            value = conversations.decode_cursor(db, cursor)
            upper, before = value.get('upper'), value.get('before')
            if value.get('scope') != scope or type(upper) is not int or type(before) is not int or not 0 <= before <= upper + 1:
                raise ValueError('Saved-message cursor scope changed')
        rows = db.execute("SELECT b.sequence,b.created_at AS saved_at,c.id AS conversation_id,c.title,c.binding,c.active_kind,m.id AS message_id,m.role,m.status,m.revision,m.content FROM gateway_message_bookmarks b JOIN gateway_messages m ON m.id=b.message_id AND m.conversation_id=b.conversation_id JOIN gateway_conversations c ON c.id=b.conversation_id WHERE c.principal=? AND b.sequence<=? AND b.sequence<? AND (? OR c.active_kind!='engineering') AND (? OR c.active_kind!='generation') AND (?='' OR c.title LIKE ? ESCAPE '\\' OR m.content LIKE ? ESCAPE '\\') ORDER BY b.sequence DESC LIMIT ?",
                          (principal, upper, before, int(engineering_allowed), int(generation_allowed), query, like_query(query), like_query(query), limit + 1)).fetchall()
        more = len(rows) > limit
        items = []
        for row in rows[:limit]:
            item = dict(json.loads(row['binding']), conversation_id=row['conversation_id'], title=row['title'],
                        message_id=row['message_id'], logical_message_id=row['message_id'], role=row['role'], status=row['status'],
                        revision=row['revision'], content=row['content'][:4000], content_truncated=len(row['content']) > 4000,
                        saved=True, saved_at=row['saved_at'], **metadata(db, row['conversation_id']))
            items.append(item)
        return {'schema_version': 1, 'gateway_id': gateway_id, 'principal_id': principal, 'items': items,
                'has_more': more, 'complete': not more,
                'next_cursor': conversations.encode_cursor(db, {'scope': scope, 'upper': upper, 'before': rows[limit - 1]['sequence']}) if more else None}
    return conversations.read(read)
