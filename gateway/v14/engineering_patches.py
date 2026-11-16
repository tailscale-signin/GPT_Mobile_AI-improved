"""Exact line-hunk proposals; no fuzzy application or unresolved conflict markers."""
import re


def apply_hunks(original, hunks, spans):
    if not isinstance(hunks, list) or not 1 <= len(hunks) <= 30:
        raise ValueError('Supply 1–30 exact diff hunks')
    lines = original.splitlines(True)
    patches = []
    for hunk in hunks:
        if (not isinstance(hunk, dict) or set(hunk) != {'start_line', 'old', 'new'}
                or type(hunk['start_line']) is not int or not 1 <= hunk['start_line'] <= len(lines) + 1
                or not isinstance(hunk['old'], str) or not hunk['old'] or not isinstance(hunk['new'], str)):
            raise ValueError('A hunk requires start_line and nonempty exact old text plus new text')
        start = sum(map(len, lines[:hunk['start_line'] - 1]))
        end = start + len(hunk['old'])
        if original[start:end] != hunk['old']:
            raise ValueError('HUNK_CONFLICT at line ' + str(hunk['start_line']) + ': read the current pinned range and revise this hunk')
        if not any(left <= start and end <= right for left, right in spans):
            raise ValueError('Read the complete hunk range before editing it')
        if re.search(r'^(?:<<<<<<<|=======|>>>>>>>)', hunk['new'], re.M):
            raise ValueError('HUNK_CONFLICT: resolve conflict markers before proposing the patch')
        patches.append((start, end, hunk['new']))
    patches.sort()
    if any(left[1] > right[0] for left, right in zip(patches, patches[1:])):
        raise ValueError('Diff hunks must not overlap')
    result = original
    for start, end, replacement in reversed(patches):
        result = result[:start] + replacement + result[end:]
    return result
