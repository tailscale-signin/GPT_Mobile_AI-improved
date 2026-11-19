"""Immutable Windows-approved validation choices exposed to paired owners."""
import hashlib
import json
from pathlib import Path
import re

from desktop.configuration import atomic_json
from desktop.phone_access import directory
from desktop.validation_policy import load_policy_snapshot, save_policy_snapshot, validated_policy_bytes


def choices(root):
    try:
        value = json.loads((directory(root) / 'validation-choices.json').read_text())
    except FileNotFoundError:
        return {}
    if not isinstance(value, dict):
        raise ValueError('Invalid approved validation profiles.')
    return {key: str(label)[:100] for key, label in value.items() if re.fullmatch('[a-f0-9]{64}', key)}


def approve_current(root):
    current = load_policy_snapshot(root)
    if not current['enabled'] or current['error']:
        raise ValueError('Activate and verify a repository validation policy on Windows first.')
    raw = validated_policy_bytes(current['repositories'])
    digest = hashlib.sha256(raw).hexdigest()
    folder = directory(root)
    folder.mkdir(parents=True, exist_ok=True)
    atomic_json(folder / (digest + '.json'), current['repositories'])
    allowed = choices(root)
    if digest not in allowed and len(allowed) >= 16:
        raise ValueError('The phone profile list holds up to 16 approved choices.')
    allowed[digest] = ', '.join(sorted(current['repositories']))[:90] or 'Repository validation'
    atomic_json(folder / 'validation-choices.json', allowed)


def active(root):
    current = load_policy_snapshot(root)
    if not current['enabled']:
        return 'disabled'
    if current['error']:
        return 'invalid'
    return hashlib.sha256(validated_policy_bytes(current['repositories'])).hexdigest()


def activate(root, choice, previous):
    current = load_policy_snapshot(root)
    if current['enabled'] and (current['error'] or hashlib.sha256(validated_policy_bytes(current['repositories'])).hexdigest() != previous):
        raise ValueError('Windows changed the validation selection.')
    if choice not in choices(root):
        raise ValueError('Windows approval required for this profile.')
    path = directory(root) / (choice + '.json')
    if path.stat().st_size > 65536:
        raise ValueError('Invalid approved profile.')
    document = json.loads(path.read_bytes())
    if hashlib.sha256(validated_policy_bytes(document)).hexdigest() != choice:
        raise ValueError('The approved profile changed on disk.')
    save_policy_snapshot(root, document, enabled=True, expected_revision=current['revision'])
