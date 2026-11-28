"""Definite generation admission failures have durable, principal-bound receipts."""
import asyncio

from fastapi.responses import JSONResponse

from v14.conversations import CommandIdentityConflict


async def rejected_generation(runtime, context, error, status):
    """Publish rejection only after its fence commits; accepted/unknown outcomes win."""
    metadata = (context or {}).get('payload', {}).get('gateway') or {}
    if not context or context.get('retention', 'full') != 'full' or metadata.get('store_conversation') is False:
        return JSONResponse({'error': error}, status_code=status)
    try:
        outcome = await asyncio.to_thread(
            runtime.gateway_conversations.reject_command,
            context['principal'], context['command_id'], context['payload'],
            error['code'], status, kind='generation')
    except CommandIdentityConflict:
        return JSONResponse({'error': {
            'code': 'conversation_conflict', 'retryable': False,
            'message': 'This command identity belongs to another request. Read its saved outcome before continuing.',
            'failed_stage': 'conversation_admission', 'admission_state': 'unknown',
            'dispatch_state': 'unknown', 'command_outcome_unknown': True,
            'requires_reconciliation': True}}, status_code=409)
    except Exception:
        # A failed write cannot prove non-acceptance, and must not erase a
        # command that another request may already have admitted.
        return JSONResponse({'error': {
            'code': 'durable_commit_failed', 'retryable': False,
            'message': 'The command outcome could not be saved. Reconcile the original command before continuing.',
            'failed_stage': 'persistence', 'admission_state': 'unknown',
            'dispatch_state': 'unknown', 'command_outcome_unknown': True,
            'requires_reconciliation': True}}, status_code=503)
    if outcome.get('admission_state') != 'rejected':
        return JSONResponse({'error': {
            'code': 'conversation_conflict', 'retryable': False,
            'message': 'This command was already accepted. Open its saved job before continuing.',
            'failed_stage': 'conversation_admission', 'admission_state': 'accepted',
            'dispatch_state': 'unknown', 'command_outcome_unknown': True,
            'requires_reconciliation': True, 'next_action': 'open_active_job',
            'job_id': outcome['job_id'], 'command_id': outcome['command_id']}}, status_code=409)
    return JSONResponse({'error': dict(error,
        command_id=outcome['command_id'], admission_state='rejected',
        dispatch_state='not_dispatched', inference_attempted=False,
        retryable=False)}, status_code=status)
