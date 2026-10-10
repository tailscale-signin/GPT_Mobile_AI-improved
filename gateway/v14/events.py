"""Snapshot recovery for durable event cursors whose history was pruned."""


def replay_snapshot(job_id: str, job: dict, events: list[dict], cursor: int, protocol: str,
                    expected_generation: str | None = None) -> dict | None:
    sequences = [event.get("sequence") for event in events if isinstance(event.get("sequence"), int)]
    generation = str(job.get("generation") or job_id)
    generation_changed = expected_generation is not None and expected_generation != generation
    gap = bool(sequences and min(sequences) > cursor + 1)
    if not gap and not generation_changed:
        return None
    latest = max(sequences) if sequences else 0
    last = int(job.get("last_sequence", latest) or latest)
    return {
        "protocol": protocol,
        "job_id": job_id,
        "generation": generation,
        "event": "gateway_snapshot",
        "status": job.get("status"),
        "stage": job.get("stage"),
        "message": job.get("message"),
        "sequence": last,
        "replayGap": gap or generation_changed,
        "generationChanged": generation_changed,
        "previousGeneration": expected_generation if generation_changed else None,
        "resultAvailable": bool(job.get("result_available")),
        "recovery": "poll_job_result_before_continuing",
    }
