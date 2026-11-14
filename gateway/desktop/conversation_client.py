"""Bounded desktop client for the existing principal-scoped Gateway APIs.

No management credential is read or sent. In network mode this is the same
shared-network principal as other admitted clients, not a private desktop owner.
"""
import base64
import hashlib
import json
import time
from urllib.parse import quote

import requests

from desktop.configuration import authority, control_host, load

MAX_RESPONSE = 2 * 1024 * 1024
MAX_TRANSCRIPT = 16 * 1024 * 1024
MAX_STREAM = 4 * 1024 * 1024
MAX_REQUEST = 512 * 1024


class ClientError(ValueError):
    def __init__(self, message, *, code='desktop_request_failed', status=None, not_dispatched=False):
        super().__init__(message)
        self.code, self.status, self.not_dispatched = code, status, not_dispatched


def profile_id(model):
    return 'desktop_' + hashlib.sha256(model.encode('utf-8')).hexdigest()[:32]


def scope_key(identity):
    return (identity.get('gateway_id'), identity.get('principal_id'))


def privacy_text(identity):
    if identity.get('identity_scope') == 'shared-network':
        return 'Shared network history: devices using this Gateway can view these saved chats and messages.'
    if identity.get('identity_scope') == 'local-machine':
        return 'Saved on this Gateway under its local-machine identity.'
    return 'Saved on this Gateway under the connected account.'


class Transcript:
    """Assemble revisioned logical messages; never show an incomplete part as text."""
    def __init__(self):
        self.parts = {}
        self.size = 0

    def add(self, item):
        if not isinstance(item, dict):
            raise ClientError('Gateway returned an invalid saved message.')
        ident = item.get('logical_message_id') or item.get('message_id')
        revision, index, count = item.get('revision'), item.get('part_index', 0), item.get('part_count', 1)
        if (not isinstance(ident, str) or not ident or type(revision) is not int or revision < 0 or
                type(index) is not int or type(count) is not int or not 0 <= index < count <= 256 or
                not isinstance(item.get('content'), str)):
            raise ClientError('Gateway returned an invalid saved message part.')
        previous = self.parts.get(ident)
        if previous and previous['revision'] > revision:
            return
        if not previous or previous['revision'] < revision:
            if previous:
                self.size -= sum(len(value.encode('utf-8')) for value in previous['parts'].values())
            previous = self.parts[ident] = {'revision': revision, 'count': count,
                                           'digest': item.get('content_sha256'), 'parts': {}, 'item': dict(item)}
        if previous['count'] != count or previous['digest'] != item.get('content_sha256'):
            raise ClientError('Saved message parts disagree. Refresh this conversation.')
        old = previous['parts'].get(index)
        if old is not None and old != item['content']:
            raise ClientError('A saved message changed while loading. Refresh this conversation.')
        previous['parts'][index] = item['content']
        if old is None:
            self.size += len(item['content'].encode('utf-8'))
        if self.size > MAX_TRANSCRIPT:
            raise ClientError('This conversation exceeds the desktop history limit. Open a new chat to continue.')

    def messages(self):
        result = []
        for ident, message in self.parts.items():
            if len(message['parts']) != message['count']:
                raise ClientError('Saved history is incomplete. Refresh before continuing.')
            content = ''.join(message['parts'][index] for index in range(message['count']))
            digest = message['digest']
            if not isinstance(digest, str) or hashlib.sha256(content.encode('utf-8')).hexdigest() != digest:
                raise ClientError('Saved message integrity could not be verified. Refresh this conversation.')
            result.append(dict(message['item'], message_id=ident, logical_message_id=ident, content=content))
        # Imported history can share one timestamp. Revision preserves its
        # numerical publication order (history:10 must not sort before history:2).
        return sorted(result, key=lambda item: (item.get('created_at', 0), item['revision'], item.get('role') != 'user'))


class Client:
    def __init__(self, root, identity=None, session_factory=requests.Session):
        self.root, self.expected_identity, self.session_factory = root, identity, session_factory
        self.cfg = load(root)
        self.origin = 'http://' + authority(control_host(self.cfg), self.cfg['port'])

    def session(self):
        session = self.session_factory()
        session.trust_env = False
        session.headers.update({'User-Agent': 'GPT-Mobile-Gateway-Desktop'})
        return session

    def headers(self):
        if not self.expected_identity:
            raise ClientError('Connect to Gateway before sending a command.', not_dispatched=True)
        return {'X-Gateway-Installation-ID': self.expected_identity['gateway_id'],
                'X-Gateway-Principal-ID': self.expected_identity['principal_id']}

    def check_scope(self, value):
        if self.expected_identity and any(key in value and value[key] != self.expected_identity[key]
                                          for key in ('gateway_id', 'principal_id')):
            raise ClientError('Gateway or account changed. Reconnect before continuing.', code='identity_changed')
        if self.cfg != load(self.root):
            raise ClientError('Gateway settings changed. Reconnect before continuing.', code='identity_changed')
        return value

    @staticmethod
    def decode(response):
        data = bytearray()
        for block in response.iter_content(16384):
            data.extend(block)
            if len(data) > MAX_RESPONSE:
                raise ClientError('Gateway response exceeds the desktop limit.')
        try:
            value = json.loads(data)
        except (ValueError, UnicodeError):
            raise ClientError('Gateway returned an unreadable response.') from None
        if not isinstance(value, dict):
            raise ClientError('Gateway returned an invalid response.')
        if not 200 <= response.status_code < 300:
            error = value.get('error') if isinstance(value.get('error'), dict) else {}
            code = error.get('code', 'request_rejected')
            code = code[:100] if isinstance(code, str) and all(c.isalnum() or c in '_-' for c in code) else 'request_rejected'
            raise ClientError(f'Gateway could not complete this action ({code}, HTTP {response.status_code}).',
                              code=code, status=response.status_code,
                              not_dispatched=error.get('dispatch_state') == 'not_dispatched')
        return value

    def request(self, method, path, *, params=None, payload=None):
        if self.cfg != load(self.root):
            raise ClientError('Gateway settings changed. Reconnect before continuing.', code='identity_changed', not_dispatched=True)
        headers = self.headers() if method != 'GET' else {}
        try:
            with self.session() as session:
                with session.request(method, self.origin + path, params=params, json=payload, headers=headers,
                                     stream=True, timeout=(3, 15), allow_redirects=False) as response:
                    return self.check_scope(self.decode(response))
        except requests.RequestException:
            raise ClientError('Could not reach Gateway. Your saved draft remains available.', code='connection_unavailable') from None

    def identity(self):
        value = self.request('GET', '/gateway/identity')
        if any(not isinstance(value.get(key), str) or not 1 <= len(value[key]) <= 512 for key in ('gateway_id', 'principal_id')):
            raise ClientError('Gateway identity is unavailable.')
        self.expected_identity = value
        return value

    def models(self):
        value = self.request('GET', '/v1/models').get('data')
        if not isinstance(value, list):
            raise ClientError('Gateway returned an invalid model list.')
        return sorted({item['id'] for item in value if isinstance(item, dict) and isinstance(item.get('id'), str)
                       and 0 < len(item['id']) <= 512})

    def conversations(self, *, query='', archived=False, favorite=False, cursor=None):
        params = {'query': query, 'archived': str(archived).lower(), 'limit': 50}
        if favorite:
            params['favorite'] = 'true'
        if cursor:
            params['cursor'] = cursor
        return self.request('GET', '/gateway/conversations', params=params)

    def saved(self, *, query='', cursor=None):
        params = {'query': query, 'limit': 50}
        if cursor:
            params['cursor'] = cursor
        return self.request('GET', '/gateway/saved-messages', params=params)

    def read(self, conversation_id):
        path = '/gateway/conversations/' + quote(conversation_id, safe='')
        conversation = self.request('GET', path)
        assembler, cursor, seen = Transcript(), None, set()
        for _ in range(256):
            page = self.request('GET', path + '/messages', params={'limit': 100, **({'after_cursor': cursor} if cursor else {})})
            items = page.get('messages')
            if not isinstance(items, list):
                raise ClientError('Gateway returned invalid saved history.')
            for item in items:
                assembler.add(item)
            cursor = page.get('next_cursor')
            if not page.get('has_more'):
                conversation = dict(conversation, revision=page.get('revision', conversation.get('revision')),
                                    history_complete=page.get('history_complete', False))
                return {'conversation': conversation, 'messages': assembler.messages()}
            if not isinstance(cursor, str) or cursor in seen:
                raise ClientError('Saved history pagination did not progress.')
            seen.add(cursor)
        raise ClientError('This conversation exceeds the desktop history limit. Open a new chat to continue.')

    def metadata(self, conversation_id, revision, **values):
        return self.request('PATCH', '/gateway/conversations/' + quote(conversation_id, safe='') + '/metadata',
                            payload=dict(values, expected_metadata_revision=revision))

    def bookmark(self, conversation_id, message_id, saved=True):
        return self.request('PUT', '/gateway/conversations/' + quote(conversation_id, safe='') + '/bookmarks/' + quote(message_id, safe=''),
                            payload={'saved': saved})

    def command(self, command_id):
        return self.request('GET', '/gateway/commands/' + quote(command_id, safe=''), params={'include_outcome': 'true'})

    def cancel(self, job_id):
        return self.request('POST', '/gateway/jobs/' + quote(job_id, safe='') + '/cancel', payload={})

    def send(self, model, prompt, command_id, *, conversation=None, history=(), on_event=lambda *_: None):
        if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > 32000:
            raise ClientError('Enter a prompt of 1 to 32,000 characters.', not_dispatched=True)
        if not isinstance(model, str) or not model:
            raise ClientError('Choose a model first.', not_dispatched=True)
        if conversation and (conversation.get('active_job_kind') == 'engineering' or conversation.get('history_complete') is not True):
            raise ClientError('This conversation cannot be continued from desktop chat. Open its original job or start a new chat.', not_dispatched=True)
        metadata = {'profile_id': (conversation or {}).get('profile_id') or profile_id(model),
                    'command_id': command_id, 'store_conversation': True}
        if conversation:
            if conversation.get('model') != model:
                raise ClientError('The original conversation model must stay selected.', not_dispatched=True)
            metadata.update(conversation_id=conversation['conversation_id'], expected_revision=conversation['revision'])
        else:
            metadata['client_conversation_id'] = 'desktop-' + command_id
        turns = [{'role': item['role'], 'content': item['content']} for item in history
                 if item.get('role') in {'user', 'assistant'} and item.get('content')]
        turns.append({'role': 'user', 'content': prompt})
        payload = {'model': model, 'stream': True, 'messages': turns, 'gateway': metadata,
                   'tools': [], 'tool_choice': 'none'}
        if len(json.dumps(payload, ensure_ascii=False).encode('utf-8')) > MAX_REQUEST:
            raise ClientError('This conversation is too large to send intact. Start a new chat with a focused prompt.', not_dispatched=True)
        headers = dict(self.headers(), **{'Idempotency-Key': command_id,
                       'X-Gateway-Profile-Name-Base64': base64.urlsafe_b64encode(model.encode('utf-8')).decode().rstrip('=')})
        if self.cfg != load(self.root):
            raise ClientError('Gateway settings changed. Reconnect before sending.', code='identity_changed', not_dispatched=True)
        receipt, done, finish, total, started = {}, False, None, 0, time.monotonic()
        try:
            with self.session() as session:
                with session.post(self.origin + '/v1/chat/completions', json=payload, headers=headers,
                                  stream=True, timeout=(5, 60), allow_redirects=False) as response:
                    if response.status_code != 200:
                        self.decode(response)
                    receipt = {key: response.headers.get(header) for key, header in {
                        'gateway_id': 'X-Gateway-ID', 'principal_id': 'X-Gateway-Principal-ID',
                        'conversation_id': 'X-Gateway-Conversation-ID', 'command_id': 'X-Gateway-Command-ID',
                        'job_id': 'X-Gateway-Job-ID'}.items() if response.headers.get(header)}
                    receipt['detach_safe'] = response.headers.get('X-Gateway-Detach-Safe') == 'true'
                    self.check_scope(receipt)
                    on_event('accepted', receipt)
                    buffered = bytearray()
                    for block in response.iter_content(4096):
                        if time.monotonic() - started > 960:
                            raise ClientError('Observation timed out. Check the saved command; work may still be running.')
                        buffered.extend(block)
                        if len(buffered) > MAX_RESPONSE:
                            raise ClientError('Gateway stream exceeded the desktop frame limit.')
                        while b'\n' in buffered:
                            line, _, remainder = buffered.partition(b'\n')
                            buffered = bytearray(remainder)
                            if not line.startswith(b'data:'):
                                continue
                            data = line[5:].strip()
                            if data == b'[DONE]':
                                done = True
                                break
                            try:
                                event = json.loads(data)
                            except (ValueError, UnicodeError):
                                raise ClientError('Gateway returned an invalid stream frame. Check the saved command.') from None
                            if not isinstance(event, dict) or event.get('error'):
                                raise ClientError('The model stream ended with an error. Check the saved command.')
                            choices = event.get('choices', [])
                            if not isinstance(choices, list):
                                raise ClientError('Gateway returned invalid stream choices.')
                            for choice in choices:
                                if not isinstance(choice, dict):
                                    raise ClientError('Gateway returned an invalid stream choice.')
                                finish = choice.get('finish_reason') or finish
                                delta = choice.get('delta') or {}
                                content = delta.get('content') if isinstance(delta, dict) else None
                                if isinstance(content, str) and content:
                                    total += len(content.encode('utf-8'))
                                    if total > MAX_STREAM:
                                        raise ClientError('This reply exceeds the desktop live view limit. Open its saved history.')
                                    on_event('delta', content)
                        if done:
                            break
            if not done or finish is None:
                raise ClientError('Live observation ended before completion. Check the saved command; it will not be sent again.')
            return receipt
        except requests.RequestException:
            raise ClientError('Connection interrupted. Check the saved command; it will not be sent again.', code='connection_unavailable') from None
