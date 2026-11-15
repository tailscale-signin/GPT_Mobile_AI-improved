"""Local operator controls. Benchmark requests cannot choose an execution image."""
import json
from pathlib import Path
import re

from desktop.configuration import atomic_json

DEFAULTS = {'schema': 1, 'sandbox_image': '', 'sandbox_seconds': 30, 'repair_attempts': 1}


def validate(value):
    if not isinstance(value, dict) or set(value) - set(DEFAULTS):
        raise ValueError('Unsupported benchmark settings')
    result = dict(DEFAULTS, **value)
    if result['schema'] != 1 or type(result['schema']) is not int:
        raise ValueError('Unsupported benchmark settings version')
    image = result['sandbox_image']
    if not isinstance(image, str) or image and not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9./:_-]{0,180}@sha256:[a-f0-9]{64}', image):
        raise ValueError('Use a local Python image pinned as name@sha256:<64 hexadecimal characters>')
    if type(result['sandbox_seconds']) is not int or not 5 <= result['sandbox_seconds'] <= 120:
        raise ValueError('Sandbox timeout must be between 5 and 120 seconds')
    if type(result['repair_attempts']) is not int or not 0 <= result['repair_attempts'] <= 2:
        raise ValueError('Choose zero, one or two bounded repairs')
    return result


def load_settings(root):
    path = Path(root) / 'data' / 'benchmark-settings.json'
    if not path.exists():
        return dict(DEFAULTS)
    if path.stat().st_size > 8192:
        raise ValueError('Benchmark settings exceed their size bound')
    return validate(json.loads(path.read_text(encoding='utf-8')))


def save_settings(root, value):
    result = validate(value)
    atomic_json(Path(root) / 'data' / 'benchmark-settings.json', result)
    return result
