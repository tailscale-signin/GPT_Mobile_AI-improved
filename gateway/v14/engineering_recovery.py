"""Durable recovery limits and allowlisted projections."""

SESSION_STEPS = 256
SESSION_REPAIRS = 20
CONTINUOUS_SESSIONS = 8
RETRY_SOURCES = ('RETRY_AFTER', 'RATE_LIMIT_RESET', 'APP_BACKOFF', 'TRANSIENT_TRANSPORT',
                 'MODEL_READINESS', 'BACKEND_READINESS', 'SHARED_COOLDOWN')
RETRY_STAGES = ('PLAN', 'READ', 'IMPLEMENT', 'REVIEW', 'COMMIT', 'OPEN_PR', 'CI', 'PROMOTE', 'MERGE', 'VERIFY')
RETRY_OUTCOMES = ('SCHEDULED', 'NEEDS_INPUT', 'RESUMED', 'INTERRUPTED')


def continuous_progress(job):
    session = job.get('execution_session')
    if session is not None:
        return {'session': session['number'], 'session_limit': CONTINUOUS_SESSIONS,
                'confirmed_steps': job['steps'], 'verified_milestones': len(job['milestones'])}


def retry_summary(job, *, active_only=False):
    wait = job.get('provider_wait')
    if not wait and not active_only:
        wait = job.get('data', {}).get('latest_retry')
    if not isinstance(wait, dict):
        return None
    source, attempts = wait.get('source'), wait.get('attempts')
    if source not in RETRY_SOURCES or type(attempts) is not int or not 0 <= attempts <= 16000000:
        return None
    summary = {'source': source, 'attempts': attempts}
    if wait.get('stage') in RETRY_STAGES:
        summary['stage'] = wait['stage']
    if wait.get('outcome') in RETRY_OUTCOMES:
        summary['outcome'] = wait['outcome']
    return summary
