"""Trusted host executable discovery and one local Docker endpoint for validation."""
from contextlib import contextmanager
import os
from pathlib import Path
import shutil
import tempfile


def executable(name):
    if name not in {'git', 'docker'}:
        raise ValueError('Unsupported validation prerequisite')
    found = shutil.which(name)
    if not found and os.name == 'nt':
        base = Path(os.environ.get('ProgramFiles', r'C:\Program Files'))
        candidate = base / ('Git/cmd/git.exe' if name == 'git' else 'Docker/Docker/resources/bin/docker.exe')
        if candidate.is_file():
            found = str(candidate)
    if not found:
        raise ValueError('Install Git for Windows, then restart Gateway.' if name == 'git'
                         else 'Install and start Docker Desktop with Linux containers, then restart Gateway.')
    return str(Path(found).resolve())


def environment(home):
    result = {'PATH': os.defpath, 'HOME': str(home), 'LANG': 'C.UTF-8'}
    if os.name == 'nt' and os.environ.get('SystemRoot'):
        result['SystemRoot'] = os.environ['SystemRoot']
    return result


@contextmanager
def command(argv):
    """Resolve tools before stripping PATH. Never inherit a remote Docker context."""
    args = list(argv)
    if args[0] == 'git':
        args[0] = executable('git')
    if args[0] == 'docker':
        docker = executable('docker')
        with tempfile.TemporaryDirectory(prefix='gateway-validation-docker-') as config:
            yield [docker, '--config', config, '--context', 'default', *args[1:]]
    else:
        yield args
