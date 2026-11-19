"""Immutable host-approved choices in the canonical owner-control job journal.

This module preserves validation-profile selection from the unreleased phone
prototype without its listener, grant database, or settings authority.
"""
import hashlib
import json
import os
from pathlib import Path
import re

from desktop.validation_policy import (POLICY_LIMIT, SETTING, load_policy_snapshot,
                                       save_policy_snapshot, validated_policy_bytes)


def state(root):
    value = load_policy_snapshot(root)
    profile = 'invalid' if value['error'] else hashlib.sha256(validated_policy_bytes(value['repositories'])).hexdigest() if value['enabled'] else 'disabled'
    return dict(value, profile_id=profile)


def active(root):
    return state(root)['profile_id']


def runtime_active():
    """Observe the policy selected by this engine's launch environment."""
    path = os.environ.get(SETTING, '')
    if not path:
        return 'disabled'
    try:
        with Path(path).open('rb') as stream:
            raw = stream.read(POLICY_LIMIT + 1)
        if len(raw) > POLICY_LIMIT:
            return 'invalid'
        return hashlib.sha256(validated_policy_bytes(json.loads(raw))).hexdigest()
    except (OSError, ValueError, TypeError, KeyError):
        return 'invalid'


def choices(db, repositories=None):
    permitted = None if repositories is None else set(repositories)
    return {row['id']: row['label'] for row in db.execute('SELECT id,label,repositories FROM gateway_control_validation_profiles ORDER BY created,id LIMIT 16')
            if permitted is None or set(json.loads(row['repositories'])) <= permitted}


def approved(db, profile):
    from desktop.control_store import ControlError
    if not isinstance(profile, str) or not re.fullmatch('[a-f0-9]{64}', profile):
        raise ControlError('validation_profile_not_approved', 403)
    row = db.execute('SELECT * FROM gateway_control_validation_profiles WHERE id=?', (profile,)).fetchone()
    if not row:
        raise ControlError('validation_profile_not_approved', 403)
    try:
        raw = row['document'].encode('utf-8')
        if len(raw) > POLICY_LIMIT or hashlib.sha256(raw).hexdigest() != profile:
            raise ValueError()
        document = json.loads(raw)
        if validated_policy_bytes(document) != raw:
            raise ValueError()
    except (ValueError, TypeError, KeyError):
        raise ControlError('validation_profile_changed', 409) from None
    return document


def approve_current(store):
    from desktop.control_store import ControlError, encoded
    current = state(store.root)
    if not current['enabled'] or current['error']:
        raise ControlError('validation_profile_setup_required', 412, 'Prepare and activate a valid repository policy on this PC first')
    raw = validated_policy_bytes(current['repositories'])
    profile = hashlib.sha256(raw).hexdigest()
    repositories = sorted(current['repositories'])
    label = ', '.join(repositories)[:90]
    with store.tx() as db:
        existing = db.execute('SELECT document FROM gateway_control_validation_profiles WHERE id=?', (profile,)).fetchone()
        if existing and existing[0] != raw.decode('utf-8'):
            raise ControlError('validation_profile_changed', 409)
        if not existing and db.execute('SELECT COUNT(*) FROM gateway_control_validation_profiles').fetchone()[0] >= 16:
            raise ControlError('validation_profiles_full', 409)
        db.execute('INSERT OR IGNORE INTO gateway_control_validation_profiles VALUES (?,?,?,?,?)',
                   (profile, label, raw.decode('utf-8'), encoded(repositories), store.now()))
    return {'profile_id': profile, 'label': label, 'repositories': repositories, 'approved': True}


def activate(store, profile):
    """CAS the selector only after idle admission; the guarded restart activates it."""
    from desktop.control_store import ControlError
    # Hold the journal transaction through the bounded file CAS. A concurrent
    # snapshot must not mistake our own selector write for a newer host edit.
    with store.tx() as db:
        current = state(store.root)
        if current['error']:
            raise ControlError('validation_profile_setup_required', 412)
        if current['profile_id'] != profile:
            intent = db.execute('SELECT profile,expected_revision FROM gateway_control_policy_intent WHERE id=1').fetchone()
            if not intent or intent['profile'] != profile or intent['expected_revision'] != current['revision']:
                raise ControlError('validation_policy_changed', 409, 'The host validation selection changed; refresh before selecting it again')
            document = approved(db, profile)
            try:
                current = dict(save_policy_snapshot(store.root, document, enabled=True, expected_revision=current['revision']), profile_id=profile)
            except (OSError, ValueError):
                raise ControlError('validation_policy_changed', 409) from None
        # An interrupted projection may already have written this exact immutable
        # profile. Record the observation without creating a second command.
        db.execute('INSERT OR REPLACE INTO gateway_control_policy_observation VALUES (1,?,?)',
                   (current['revision'], current['profile_id']))
    return current['profile_id']
