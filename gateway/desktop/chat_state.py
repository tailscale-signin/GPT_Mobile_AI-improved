"""Local drafts and send identity only; never a second transcript/job executor."""
import json
from pathlib import Path
import threading
import uuid

from desktop.configuration import atomic_json


class ChatState:
    def __init__(self, root):
        self.path = Path(root) / 'data' / 'desktop-chat-state.json'
        self.lock = threading.Lock()
        self.draft_version = 0
        self.value = {'schema': 1, 'draft': '', 'model': '', 'pending': None}
        self.error = None
        self.recoverable_pending = None
        try:
            if self.path.stat().st_size > 512 * 1024:
                raise ValueError('Desktop draft exceeds its bound')
            value = json.loads(self.path.read_text(encoding='utf-8'))
            pending = value.get('pending') if isinstance(value, dict) else None
            if self.valid_pending(pending):
                self.recoverable_pending = pending
            if (not isinstance(value, dict) or value.get('schema') != 1 or
                    not isinstance(value.get('draft'), str) or len(value['draft']) > 32000 or
                    not isinstance(value.get('model'), str) or len(value['model']) > 512 or
                    pending is not None and not self.valid_pending(pending)):
                raise ValueError('Invalid desktop draft')
            self.value = value
        except FileNotFoundError:
            pass
        except (OSError, ValueError, UnicodeError):
            self.error = 'The previous desktop draft could not be read. The saved file has been kept.'

    @staticmethod
    def valid_pending(value):
        return (isinstance(value, dict) and isinstance(value.get('command_id'), str) and
                1 <= len(value['command_id']) <= 256 and isinstance(value.get('identity'), dict) and
                all(isinstance(value['identity'].get(key), str) and 1 <= len(value['identity'][key]) <= 512
                    for key in ('gateway_id', 'principal_id')))

    def reserve_draft(self):
        """Order UI edits before their background writes can race each other."""
        with self.lock:
            self.draft_version += 1
            return self.draft_version

    def save(self, *, draft_version=None, **values):
        with self.lock:
            if self.error:
                raise ValueError(self.error)
            if 'draft' in values or 'model' in values:
                if draft_version is None:
                    self.draft_version += 1
                elif draft_version != self.draft_version:
                    # The command transition remains valid even when its draft
                    # snapshot has been superseded by a newer user edit.
                    values = {key: value for key, value in values.items() if key not in {'draft', 'model'}}
            candidate = dict(self.value, **values)
            if len(candidate['draft']) > 32000:
                raise ValueError('Drafts are limited to 32,000 characters.')
            atomic_json(self.path, candidate)
            self.value = candidate

    def recover(self):
        """Explicit recovery preserves the damaged bytes and every readable pending ID."""
        with self.lock:
            if not self.error:
                return None
            backups = list(self.path.parent.glob('desktop-chat-recovery-*.json'))
            if len(backups) >= 3:
                raise ValueError('Three draft recovery files are already retained. Move one to your backup folder before recovering again.')
            backup = self.path.with_name('desktop-chat-recovery-' + uuid.uuid4().hex + '.json')
            if self.path.exists():
                self.path.rename(backup)
            candidate = {'schema': 1, 'draft': '', 'model': '', 'pending': self.recoverable_pending,
                         'recovery_backup': backup.name}
            try:
                atomic_json(self.path, candidate)
            except OSError:
                # The original file remains intact, even if the disk is full.
                if backup.exists() and not self.path.exists():
                    backup.rename(self.path)
                raise
            self.value, self.error = candidate, None
            return backup.name
