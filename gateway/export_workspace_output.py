"""Export one retained engineering build product from the authenticated Gateway."""
import argparse
import base64
import hashlib
import os
from pathlib import Path
import re
import tempfile
from urllib.parse import quote

from desktop.conversation_client import Client


def export(client, job_id, path, destination, *, revision=None, patch=None, command=None):
    job = client.engineering_job(job_id)
    matches = [r for r in job.get('workspace_outputs', []) if r.get('path') == path and r.get('state') == 'RETAINED']
    matches = [row for row in matches if (revision is None or row.get('revision') == revision)
               and (patch is None or row.get('patch_identity') == patch) and (command is None or row.get('command_index') == command)]
    if len(matches) > 1:
        raise ValueError('More than one check produced this file. Select its revision, patch and command index.')
    if job.get('id') != job_id or not matches:
        raise ValueError('Choose an available output path from this job')
    output = matches[-1]
    if not 0 < output['bytes'] <= 192 * 1024 * 1024 or not 1 <= len(output['parts']) <= 12:
        raise ValueError('Invalid build artifact size')
    if (any(type(part.get('byte_length')) is not int or not 0 < part['byte_length'] <= 16 * 1024 * 1024 for part in output['parts'])
            or sum(part['byte_length'] for part in output['parts']) != output['bytes']):
        raise ValueError('Invalid artifact part lengths')
    combined = hashlib.sha256()
    with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as file:
        temporary = Path(file.name)
    try:
        with temporary.open('wb') as sink:
            for part in output['parts']:
                artifact, offset, digest = part['result_ref'], 0, hashlib.sha256()
                if not re.fullmatch('artifact_[a-f0-9]{40}', artifact) or part['job_id'] != job_id:
                    raise ValueError('Invalid artifact binding')
                while offset < part['byte_length']:
                    page = client.request('GET', '/gateway/engineering/jobs/'+quote(job_id,safe='')+'/artifacts/'+artifact,
                                          params={'offset':offset, 'length':65536})
                    raw = base64.b64decode(page['content_base64'], validate=True)
                    if (page['reference'] != part or not raw or len(raw) > min(65536, part['byte_length'] - offset) or page['offset'] != offset or
                            page['next_offset'] != offset+len(raw) or hashlib.sha256(raw).hexdigest() != page['page_sha256']):
                        raise ValueError('Artifact page identity changed')
                    offset += len(raw)
                    digest.update(raw)
                    combined.update(raw)
                    sink.write(raw)
                if offset != part['byte_length'] or digest.hexdigest() != part['sha256']:
                    raise ValueError('Artifact part failed verification')
            if sink.tell() != output['bytes'] or combined.hexdigest() != output['sha256']:
                raise ValueError('The exported output failed its complete checksum')
        os.replace(temporary,destination)
    finally:
        temporary.unlink(missing_ok=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('root','job','path','output'):
        parser.add_argument('--'+name,required=True)
    parser.add_argument('--revision')
    parser.add_argument('--patch')
    parser.add_argument('--command', type=int)
    arg=parser.parse_args()
    connection=Client(Path(arg.root))
    connection.expected_identity=connection.identity()
    export(connection,arg.job,arg.path,Path(arg.output), revision=arg.revision, patch=arg.patch, command=arg.command)
