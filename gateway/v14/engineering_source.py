"""Prepare a pinned source revision before entering network-isolated validation."""
from contextlib import contextmanager
import time

from desktop.repository_validation import check_mirror, git_environment, git_prefix, repository_name, run
from v14.engineering_validation import load_policy
from v14.state_lock import StateDirectoryLock, StateDirectoryAlreadyOwned
from v14.validation_host import executable


@contextmanager
def preparation_lock(mirror, cancel):
    lock = StateDirectoryLock(mirror.parent / (mirror.name + '.prepare') / 'lock')
    while True:
        if cancel.is_set():
            raise InterruptedError('Source preparation stopped')
        try:
            lock.acquire()
            break
        except StateDirectoryAlreadyOwned:
            cancel.wait(.2)
    try:
        yield
    finally:
        lock.release()


def prepare_source(job, token, expected_tree, cancel):
    policy, mirror, _, _, _ = load_policy(job['request']['repository'])
    repository = repository_name(job['request']['repository'])
    head = job['data']['head_sha']
    git = executable('git')
    # The same mirror can serve independent jobs; serialize only preparation.
    with preparation_lock(mirror, cancel):
        check_mirror(git, repository, mirror, cancel=cancel)
        prefix = git_prefix(git) + ['--git-dir=' + str(mirror)]
        try:
            observed = run(prefix + ['rev-parse', '--verify', head + '^{commit}'],
                           env=git_environment(), cancel=cancel, label='Inspect pinned source')
        except ValueError:
            remaining = max(1, min(900, job['deadline'] - time.time()))
            run(prefix + ['fetch', '--quiet', '--no-tags', 'https://github.com/' + repository + '.git', head],
                env=git_environment(token), cancel=cancel, timeout=remaining, label='Prepare pinned source')
            observed = run(prefix + ['rev-parse', '--verify', head + '^{commit}'],
                           env=git_environment(), cancel=cancel, label='Verify pinned source')
        tree = run(prefix + ['rev-parse', '--verify', head + '^{tree}'],
                   env=git_environment(), cancel=cancel, label='Verify source tree')
        if observed != head or tree != expected_tree:
            raise ValueError('Prepared source differs from the observed GitHub revision')
    return {'head_sha': head, 'tree_sha': tree, 'status': 'VERIFIED'}
