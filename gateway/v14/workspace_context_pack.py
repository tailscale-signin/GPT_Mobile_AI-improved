"""Selective immutable source export, with optional offline Repomix execution."""
import hashlib
import io
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
from xml.sax.saxutils import escape, quoteattr

from v14.engineering_workspace import load_file
from v14.execution_binding import canonical, fingerprint


def _encode(files, style, head):
    if style == 'json':
        return canonical({'source_sha': head, 'files': files, 'source_level_only': True}).encode()
    if style == 'xml':
        return ('<repository source_sha=' + quoteattr(head) + '>' + ''.join(
            '<file path=' + quoteattr(row['path']) + ' blob_sha=' + quoteattr(row['blob_sha']) + '>' +
            escape(row['content']) + '</file>' for row in files) + '</repository>').encode()
    sections = ['# Selected repository source\n\nCommit: ' + head + '\n']
    for row in files:
        # A fence longer than any source run prevents content escaping its block.
        fence = '`' * max(3, max((len(part) for part in re.findall(r'`+', row['content'])), default=0) + 1)
        sections.append('\n## ' + json.dumps(row['path']) + '\n\nBlob: ' + row['blob_sha'] + '\n\n' + fence + '\n' + row['content'] + '\n' + fence + '\n')
    return ''.join(sections).encode()


def _repomix(backend, job, files, style, maximum):
    from v14.engineering_validation import cancellation_scope, load_policy, run_bounded
    policy, _, image, _, _ = load_policy(job['request']['repository'])
    if fingerprint(policy) != job['data'].get('admission', {}).get('policy_sha256') or policy.get('workspace_repomix') is not True:
        raise PermissionError('Enable Repomix in the unchanged approved validation image first')
    with tempfile.TemporaryDirectory(prefix='gateway-context-pack-') as root:
        folder = Path(root)
        os.chmod(folder, 0o755)
        selected = folder / 'selected'
        selected.mkdir(mode=0o755)
        for row in files:
            path = selected / row['path']
            path.parent.mkdir(parents=True, exist_ok=True)
            for parent in path.parents:
                if parent == folder:
                    break
                os.chmod(parent, 0o755)
            path.write_text(row['content'], encoding='utf-8')
            os.chmod(path, 0o444)
        config = folder / 'gateway-repomix.json'
        # Ignore repository config and instruction files as configuration. Only
        # selected immutable regular source bytes enter this disposable mount.
        config.write_text('{}', encoding='utf-8')
        os.chmod(config, 0o444)
        output = io.BytesIO()
        cidfile = folder / 'repomix-container'
        argv = ['docker', 'run', '--rm', '--pull=never', '--cidfile=' + str(cidfile), '--network=none', '--read-only',
                '--cap-drop=ALL', '--security-opt=no-new-privileges', '--pids-limit=128',
                '--memory=1g', '--cpus=1', '--user=65534:65534', '--workdir=/input',
                '--mount', 'type=bind,source=' + str(folder) + ',target=/input,readonly',
                '--tmpfs', '/tmp:rw,noexec,nosuid,size=64m', '--env', 'HOME=/tmp', image,
                'repomix', 'selected', '--config', '/input/gateway-repomix.json', '--stdout',
                '--style', style, '--parsable-style', '--compress', '--no-git-sort-by-changes',
                '--token-budget', str(maximum)]
        try:
            with cancellation_scope(backend.cancellation(job), job.get('deadline')):
                result = run_bounded(argv, folder, 60, maximum=maximum, destination=output)
        finally:
            # Killing the Docker client on cancellation or an output limit does
            # not stop its container. Reap only the ID written for this run.
            if cidfile.exists():
                container = cidfile.read_text().strip()
                if re.fullmatch('[a-f0-9]{64}', container):
                    from v14.validation_host import command as host_command, environment as host_environment
                    with host_command(['docker', 'rm', '-f', container]) as cleanup:
                        subprocess.run(cleanup, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                                       stderr=subprocess.DEVNULL, timeout=15, check=False, env=host_environment(folder))
        raw = output.getvalue()
        if result['exit_code'] != 0:
            reference = backend.private_artifacts.put_json(job, result, 'repomix-setup-failure')
            return None, {'status': 'RUNTIME_OR_INPUT_UNAVAILABLE', 'output_ref': reference, 'exit_code': result['exit_code'],
                          'recovery': 'Provision Repomix in the approved immutable image and inspect retained output'}
        return raw, {'status': 'COMPLETED', 'engine': 'repomix', 'command_sha256': fingerprint(argv[argv.index(image):]),
                     'image': image, 'source_transform': 'TREE_SITTER_COMPRESSION', 'runtime_verified': True}


def pack(backend, job, action):
    paths = action.get('paths')
    style, engine, maximum = action.get('format', 'json'), action.get('engine', 'builtin'), action.get('max_bytes', 32768)
    if (not isinstance(paths, list) or not 1 <= len(paths) <= 60 or any(not isinstance(path, str) for path in paths) or
            len(set(paths)) != len(paths) or style not in {'json', 'xml', 'markdown'} or engine not in {'builtin', 'repomix'} or
            type(maximum) is not int or not 1024 <= maximum <= 262144):
        raise ValueError('Select 1–60 source paths, json/xml/markdown, builtin/repomix and a 1–256 KiB budget')
    if backend.private_artifacts is None:
        raise ValueError('Private artifact storage is required for context export')
    files, omitted = [], []
    for path in paths:
        source = load_file(backend, job, path)
        row = {'path': path, 'blob_sha': source['sha'], 'content': source['content']}
        if len(_encode(files + [row], style, job['data']['head_sha'])) > maximum:
            omitted.append({'path': path, 'blob_sha': source['sha'], 'reason': 'BYTE_BUDGET'})
        else:
            files.append(row)
    raw = _encode(files, style, job['data']['head_sha'])
    execution = {'status': 'COMPLETED', 'engine': 'builtin', 'source_transform': 'NONE', 'runtime_verified': True}
    if engine == 'repomix':
        if not files:
            raise ValueError('No complete selected source file fits the input budget')
        raw, execution = _repomix(backend, job, files, style, maximum)
        if raw is None:
            return dict(execution, source_sha=job['data']['head_sha'], file_count=0, omitted_count=len(paths))
    media = {'json': 'application/json', 'xml': 'application/xml', 'markdown': 'text/markdown'}[style]
    reference = backend.private_artifacts.put(job, (raw[index:index + 65536] for index in range(0, len(raw), 65536)),
                                              purpose='selected-context-pack', media_type=media)
    result = {'artifact_ref': reference, 'file_count': len(files), 'omitted_count': len(omitted),
              'files': [{key: row[key] for key in ('path', 'blob_sha')} for row in files], 'omitted': omitted,
              'source_sha': job['data']['head_sha'], 'byte_length': len(raw), 'max_bytes': maximum,
              'sha256': hashlib.sha256(raw).hexdigest(), 'format': style, 'complete': not omitted,
              'token_count': None, 'token_count_status': 'NOT_MEASURED', 'source_level_only': True, **execution}
    job['data']['workspace_context_pack'] = result
    return result
