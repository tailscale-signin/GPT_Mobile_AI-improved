"""Generation-bound recovery when durable event history is incomplete."""


def replay_snapshot(job_id: str, job: dict, events: list[dict], cursor: int, protocol: str,
                    expected_generation: str | None = None) -> dict | None:
    if type(cursor) is not int or cursor < 0:
        raise ValueError('Event cursor must be a nonnegative integer')
    sequences = [event.get('sequence') for event in events if isinstance(event, dict)
                 and type(event.get('sequence')) is int and event['sequence'] > 0]
    malformed = len(sequences) != len(events)
    distinct = sorted(set(sequences))
    latest = distinct[-1] if distinct else 0
    last = job.get('last_sequence', latest)
    if last is None:
        last = latest
    if type(last) is not int or last < 0:
        raise ValueError('Durable event sequence must be a nonnegative integer')
    generation = str(job.get('generation') or job_id)
    generation_changed = expected_generation is not None and expected_generation != generation
    inconsistent = malformed or len(distinct) != len(sequences) or latest > last or cursor > last
    missing, missing_count, expected = [], 0, cursor + 1
    for sequence in distinct:
        if not cursor < sequence <= last:
            continue
        if sequence > expected:
            missing_count += 1
            if len(missing) < 32:
                missing.append({'start': expected, 'end': sequence - 1})
        expected = sequence + 1
    if expected <= last:
        missing_count += 1
        if len(missing) < 32:
            missing.append({'start': expected, 'end': last})
    gap = inconsistent or missing_count > 0
    if not gap and not generation_changed:
        return None
    return {
        'protocol': protocol,
        'job_id': job_id,
        'generation': generation,
        'event': 'gateway_snapshot',
        'status': job.get('status'),
        'stage': job.get('stage'),
        'message': job.get('message'),
        'sequence': last,
        'replayGap': gap or generation_changed,
        'generationChanged': generation_changed,
        'previousGeneration': expected_generation if generation_changed else None,
        'missingRanges': missing,
        'missingRangesTruncated': missing_count > len(missing),
        'sequenceEvidenceConsistent': not inconsistent,
        'resultAvailable': bool(job.get('result_available')),
        'recovery': 'poll_job_result_before_continuing',
    }
