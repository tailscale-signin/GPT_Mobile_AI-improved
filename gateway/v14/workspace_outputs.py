"""Retain bounded build products from the current disposable checkout."""
import hashlib
from itertools import islice

from v14.engineering_artifacts import CHUNK
from v14.execution_binding import ExecutionBindingError

ANDROID_OUTPUTS = ('app/build/outputs/apk/debug/*.apk', 'app/build/test-results/testDebugUnitTest/TEST-*.xml',
                   'app/build/reports/lint-results-debug.xml')
PART_BYTES = 16 * 1024 * 1024


def patterns(policy):
    if (policy.get('prepared_environment') or {}).get('profile') == 'Android / Kotlin':
        return ANDROID_OUTPUTS
    return ()


def files(root, names):
    observed = {}
    for pattern in names:
        for path in islice(root.glob(pattern), 512):
            if (path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(root.resolve())
                    or any((root / parent).is_symlink() for parent in path.relative_to(root).parents)):
                continue
            stat = path.stat()
            observed[path.relative_to(root).as_posix()] = (stat.st_size, stat.st_mtime_ns)
            if len(observed) >= 256:
                return observed
    return observed


def capture(root, names, baseline, store):
    observed = files(root, names)
    result = []
    for relative in sorted(observed, key=lambda path: (not path.endswith('.apk'),path)):
        size, _ = observed[relative]
        if not size or observed[relative] == baseline.get(relative):
            continue
        item = {'path':relative, 'bytes':size}
        result.append(item)
        if len(result) > 32:
            item.update(state='OMITTED', reason='OUTPUT_COUNT_LIMIT')
            break
        if size > 192 * 1024 * 1024:
            item.update(state='OMITTED', reason='OUTPUT_SIZE_LIMIT')
            continue
        digest = hashlib.sha256()
        parts = []
        try:
            with (root / relative).open('rb') as raw:
                left = size
                while left:
                    amount = min(left, PART_BYTES)
                    def blocks():
                        remaining = amount
                        while remaining:
                            block = raw.read(min(CHUNK, remaining))
                            if not block:
                                raise ValueError('The produced artifact changed during collection')
                            remaining -= len(block)
                            digest.update(block)
                            yield block
                    parts.append(store(blocks()))
                    left -= amount
                if raw.read(1):
                    raise ValueError('The produced artifact changed during collection')
            item.update(state='RETAINED', sha256=digest.hexdigest(), parts=parts)
        except InterruptedError:
            raise
        except (OSError, ValueError, ExecutionBindingError) as error:
            item.update(state='UNAVAILABLE', reason=getattr(error, 'code', type(error).__name__))
    return result
