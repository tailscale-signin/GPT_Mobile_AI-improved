import base64
import hashlib
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase
from unittest.mock import Mock, patch

from export_workspace_output import export
from v14 import workspace_outputs


class WorkspaceOutputTests(TestCase):
    def test_changed_outputs_only_with_part_and_complete_verification(self):
        with TemporaryDirectory() as folder:
            root = Path(folder)
            archived = root / 'old.apk'
            archived.write_bytes(b'old')
            baseline = workspace_outputs.files(root, ['*.apk'])
            raw = b'published test bytes' * 8000
            (root / 'new.apk').write_bytes(raw)
            try:
                (root / 'link.apk').symlink_to(archived)
            except OSError:
                pass
            job_id = 'job'
            content = {}
            def store(blocks):
                value = b''.join(blocks)
                sha = hashlib.sha256(value).hexdigest()
                ref = dict(job_id=job_id, result_ref='artifact_'+sha[:40], sha256=sha, byte_length=len(value))
                content[ref['result_ref']] = value, ref
                return ref
            with patch.object(workspace_outputs, 'PART_BYTES', 100000):
                outputs = workspace_outputs.capture(root, ['*.apk'], baseline, store)
            self.assertEqual(['new.apk'], [o['path'] for o in outputs])
            self.assertEqual(2, len(outputs[0]['parts']))
            client = Mock()
            client.engineering_job.return_value = dict(id=job_id, workspace_outputs=[dict(outputs[0], revision='head', patch_identity='patch', command_index=1)])
            def page(_method, path, *, params):
                data, ref = content[path.rsplit('/', 1)[-1]]
                offset = params['offset']
                block = data[offset:offset+params['length']]
                return dict(reference=ref, offset=offset, next_offset=offset+len(block),
                            content_base64=base64.b64encode(block).decode(), page_sha256=hashlib.sha256(block).hexdigest())
            client.request.side_effect = page
            destination = root/'saved'
            export(client, job_id, 'new.apk', destination, revision='head', patch='patch', command=1)
            self.assertEqual(raw, destination.read_bytes())
            destination.write_bytes(b'previous')
            with self.assertRaises(ValueError):
                export(client, job_id, 'new.apk', destination, revision='wrong')
            client.request.side_effect = lambda *a, **kw: dict(page(*a, **kw), page_sha256='changed')
            with self.assertRaises(ValueError):
                export(client, job_id, 'new.apk', destination)
            self.assertEqual(b'previous', destination.read_bytes())
