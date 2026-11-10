"""Bounded support receipts from committed jobs; no prompts, paths or result bodies."""
import hashlib
import json
import re

from v14.diagnostic_schema import SCHEMA_DIGEST, ENUM_ATTRIBUTES
from v14.engineering_scope import accepted_scope


def terminal_receipt(job, state, verification):
    requirements = verification.get('requirements', [])
    required = len(requirements)
    verified = sum(row.get('status') == 'BEHAVIOR_VERIFIED' for row in requirements if isinstance(row, dict))
    scope = (job.get('data') or {}).get('scope_verification') or {}
    read_scope = accepted_scope(job.get('request') or {}) in {'read', 'plan'}
    accepted = scope.get('delivered') is True and scope.get('repository_mutations') == 0 if read_scope else required > 0 and verified == required and verification.get('passed') is True
    status = ('COMPLETED' if state == 'COMPLETED' and accepted else 'EVIDENCE_MISSING' if state == 'COMPLETED' else
              'CANCELLED' if state == 'CANCELLED' else 'FAILED' if state == 'FAILED' else
              'BLOCKED' if state in {'NEEDS_INPUT', 'MERGE_READY'} else 'PARTIAL')
    reason = (job.get('data') or {}).get('blocked_reason')
    if isinstance(reason, dict):
        reason = reason.get('code')
    if not isinstance(reason, str) or reason not in ENUM_ATTRIBUTES['reason_code']:
        reason = ('ACCEPTANCE_CRITERIA_UNVERIFIED' if status in {'PARTIAL', 'EVIDENCE_MISSING'} else
                  'TASK_BLOCKED' if status == 'BLOCKED' else 'UNKNOWN_CAUSE' if status == 'FAILED' else None)
    next_action = ('none' if status in {'COMPLETED', 'CANCELLED'} else
                   'authorize' if reason in {'ENGINEERING_AUTHENTICATION_REQUIRED', 'ENGINEERING_ACCESS_DENIED', 'AUTHORIZATION_REQUIRED'} else
                   'repair_request' if reason in {'ENGINEERING_VALIDATION_REJECTED', 'OPERATION_PRECHECK_REJECTED'} else
                   'verify_acceptance' if status in {'PARTIAL', 'EVIDENCE_MISSING'} else 'inspect_failure')
    failed_stage = job.get('state', '').lower() if isinstance(job.get('state'), str) and status not in {'COMPLETED', 'CANCELLED'} else None
    last_completed = (job.get('data') or {}).get('last_completed_stage')
    if not isinstance(last_completed, str) or last_completed not in ENUM_ATTRIBUTES['last_completed_stage']:
        last_completed = 'unknown'
    return {'schema': 'gateway.terminal-receipt.v1', 'job_id': job['id'], 'status': status,
            'task_scope': accepted_scope(job.get('request') or {}),
            'acceptance_verified': status == 'COMPLETED', 'required_criteria': required,
            'verified_criteria': verified, 'remaining_criteria': required - verified,
            'reason_code': reason, 'failed_stage': failed_stage if failed_stage in ENUM_ATTRIBUTES['failed_stage'] else None,
            'last_completed_stage': last_completed, 'checkpoint_revision': job.get('revision', 0),
            'next_action': next_action}


def engineering_inventory(service, principal, session):
    """One transactional snapshot, scoped to the admitted owner AND original capture."""
    def read(db):
        rows = db.execute("SELECT id,state,revision,json_extract(data,'$.diagnostic_capture'),"
                          "json_extract(data,'$.data.final_report.terminal_receipt'),"
                          "json_extract(data,'$.request.model'),json_extract(data,'$.request.profile_id'),"
                          "json_extract(data,'$.data.head_sha') FROM engineering_jobs WHERE principal=? "
                          "AND json_extract(data,'$.diagnostic_capture.session_id')=? ORDER BY next_at DESC LIMIT 65",
                          (principal, session)).fetchall()
        items = []
        for row in rows[:64]:
            job_id, state, revision, capture, receipt, model, profile, head = row
            # Current authorization can be revoked while an old capture remains readable.
            if not service.authorized({'principal': principal}):
                return {'availability': 'access_denied', 'complete': False, 'jobs': []}
            if not isinstance(job_id, str) or not re.fullmatch(r'[a-z0-9_-]{1,128}', job_id):
                continue
            item = {'job_id': job_id, 'checkpoint_revision': revision,
                    'state': state if state in {'COMPLETED', 'CANCELLED', 'FAILED', 'NEEDS_INPUT', 'MERGE_READY', 'PLAN', 'READ', 'IMPLEMENT', 'REVIEW', 'COMMIT', 'OPEN_PR', 'PROMOTE', 'CI', 'MERGE', 'VERIFY', 'REPAIR', 'CANCELLING'} else 'ACTIVE',
                    'terminal': state in {'COMPLETED', 'CANCELLED', 'FAILED'},
                    'checkpoint_reference': f'/gateway/engineering/jobs/{job_id}',
                    'evidence_reference': f'/gateway/engineering/jobs/{job_id}/evidence'}
            if receipt:
                parsed = json.loads(receipt)
                # Receipts are persisted by build_report; use only bounded fields on export.
                item['terminal_receipt'] = {key: value for key, value in parsed.items()
                    if key in {'required_criteria', 'verified_criteria', 'remaining_criteria', 'checkpoint_revision'} and type(value) is int and 0 <= value <= 2 ** 53
                    or key == 'status' and isinstance(value, str) and value in ENUM_ATTRIBUTES['terminal_status']
                    or key == 'reason_code' and isinstance(value, str) and value in ENUM_ATTRIBUTES['reason_code']
                    or key in {'failed_stage', 'last_completed_stage'} and isinstance(value, str) and value in ENUM_ATTRIBUTES[key]
                    or key == 'next_action' and isinstance(value, str) and value in ENUM_ATTRIBUTES['recovery_action']
                    or key == 'task_scope' and isinstance(value, str) and value in {'read', 'plan', 'implement'}
                    or key == 'acceptance_verified' and type(value) is bool}
            for name, value in (('model_digest', model), ('profile_digest', profile)):
                if isinstance(value, str):
                    item[name] = hashlib.sha256(value.encode()).hexdigest()
            if isinstance(head, str) and re.fullmatch('[a-f0-9]{40}', head):
                item['repository_revision'] = head
            items.append(item)
        return {'availability': 'observed', 'complete': len(rows) <= 64, 'limit': 64, 'jobs': items,
                'scope': 'Committed engineering jobs bound to this owner and capture; generation jobs have separate receipts.',
                'schema_digest': SCHEMA_DIGEST}
    return service.store.tx(read)
