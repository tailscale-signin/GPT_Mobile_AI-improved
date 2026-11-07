"""One token admission contract for interactive and durable selected-model requests."""
from __future__ import annotations

import copy

from v14.execution_binding import ExecutionBindingError, canonical, fingerprint


def plan_tokens(payload, capability, counter, *, verification_tokens=0, final_tokens=0, recovery_tokens=0):
    context = capability.get('context_tokens')
    output_limit = capability.get('max_output_tokens')
    if capability.get('limits_verified') is not True or type(context) is not int or context < 256 or type(output_limit) is not int or output_limit <= 0:
        raise ExecutionBindingError('EXECUTION_CONTEXT_UNAVAILABLE', 'Verified context and output limits are required before model admission.')
    for value in (verification_tokens, final_tokens, recovery_tokens):
        if type(value) is not int or value < 0:
            raise ValueError('Invalid completion reserve')
    requested = payload.get('max_tokens', payload.get('max_completion_tokens'))
    explicit = requested is not None
    if explicit and (type(requested) is not int or requested <= 0):
        raise ExecutionBindingError('EXECUTION_OUTPUT_INVALID', 'The selected output allowance must be a positive token count.')
    reserve = verification_tokens + final_tokens + recovery_tokens
    safety = min(1024, max(64, context // 32))
    output = requested if explicit else min(output_limit, max(128, context // 4))
    if output > output_limit:
        raise ExecutionBindingError('EXECUTION_OUTPUT_LIMIT', 'The requested output allowance exceeds the selected runtime limit.')
    tokens, mode = counter(payload)
    if type(tokens) is not int or tokens < 0 or not isinstance(mode, str):
        raise ExecutionBindingError('EXECUTION_TOKEN_COUNT_INVALID', 'The selected tokenizer returned an invalid count.')
    total = tokens + output + safety + reserve
    if total > context:
        error = ExecutionBindingError('EXECUTION_CONTEXT_EXHAUSTED', 'The prompt, selected output allowance and completion reserves do not fit. Retained evidence can be paged; no alternate model was selected.')
        error.budget = {'context_tokens': context, 'input_tokens': tokens, 'output_tokens': output, 'safety_tokens': safety,
                        'verification_tokens': verification_tokens, 'final_tokens': final_tokens, 'recovery_tokens': recovery_tokens,
                        'count_mode': mode, 'remaining_tokens': max(0, context - total)}
        raise error
    return {'schema_version': 1, 'context_tokens': context, 'input_tokens': tokens, 'output_tokens': output,
            'safety_tokens': safety, 'verification_tokens': verification_tokens, 'final_tokens': final_tokens,
            'recovery_tokens': recovery_tokens, 'remaining_tokens': context - total, 'count_mode': mode,
            'input_tokens_measured': mode == 'SELECTED_TOKENIZER', 'explicit_output_preserved': explicit}


def stage_reserves(capability):
    context = capability['context_tokens']
    return {'verification_tokens': min(1024, max(64, context // 32)),
            'final_tokens': min(2048, max(128, context // 16)),
            'recovery_tokens': min(1024, max(64, context // 32))}


def compact_evidence(value, text_limit, *, nodes=2048):
    """Bound traversal and aggregate text, not the durable source.

    Omissions describe the parent container when children are not visited; do
    not allocate a placeholder for every omitted field of a very wide object.
    """
    if type(nodes) is not int or nodes < 1 or type(text_limit) is not int or text_limit < 1:
        raise ValueError('Evidence view limits must be positive integers')
    remaining = [nodes]
    text_remaining = [text_limit * 4]
    omissions = []
    def omit(path, reason, **details):
        if len(omissions) < 256:
            omissions.append(dict(pointer=path, reason=reason, **details))
    def visit(item, path, depth):
        remaining[0] -= 1
        if depth > 16:
            omit(path, 'VIEW_DEPTH_LIMIT')
            return {'available_in_retained_evidence': True, 'view_omitted': True}
        if isinstance(item, str):
            raw = item.encode('utf-8')
            allowance = min(text_limit, text_remaining[0])
            if len(raw) > allowance:
                prefix = raw[:allowance].decode('utf-8', 'ignore')
                text_remaining[0] -= len(prefix.encode('utf-8'))
                identity = fingerprint(item)
                omit(path, 'TOKEN_BUDGET', sha256=identity, byte_length=len(raw), visible_bytes=len(prefix.encode('utf-8')))
                return {'excerpt': prefix, 'complete': False, 'retained_sha256': identity, 'retained_bytes': len(raw)}
            text_remaining[0] -= len(raw)
            return item
        if isinstance(item, dict):
            result = {}
            for key, child in item.items():
                if remaining[0] <= 0 or len(result) >= 64:
                    omit(path, 'VIEW_NODE_LIMIT' if remaining[0] <= 0 else 'VIEW_ITEM_LIMIT', retained_count=len(item), visible_count=len(result))
                    break
                # Untrusted property names also consume tokens. Stop rather than
                # scan arbitrarily many oversized keys or duplicate raw paths.
                if not isinstance(key, str) or len(key.encode('utf-8')) > min(256, text_remaining[0]):
                    omit(path, 'VIEW_KEY_LIMIT', retained_count=len(item), visible_count=len(result))
                    break
                text_remaining[0] -= len(key.encode('utf-8'))
                result[key] = visit(child, path + '/' + key.replace('~', '~0').replace('/', '~1'), depth + 1)
            return result
        if isinstance(item, list):
            result = []
            for child in item:
                if remaining[0] <= 0 or len(result) >= 64:
                    omit(path, 'VIEW_NODE_LIMIT' if remaining[0] <= 0 else 'VIEW_ITEM_LIMIT', retained_count=len(item), visible_count=len(result))
                    break
                result.append(visit(child, path + '/' + str(len(result)), depth + 1))
            return result
        return item
    return visit(value, '', 0), omissions


def fit_engineering_payload(payload, capability, counter):
    """Progressive evidence projection preserves objective, policy and output reserve."""
    import json
    original = copy.deepcopy(payload)
    reserves = stage_reserves(capability)
    try:
        return payload, plan_tokens(payload, capability, counter, **reserves), None
    except ExecutionBindingError as error:
        if error.code != 'EXECUTION_CONTEXT_EXHAUSTED':
            raise
    envelope = json.loads(original['messages'][-1]['content'])
    retained = {name: envelope.get(name) for name in ('evidence', 'historical_memory', 'code_graph')}
    # Bounded, strictly decreasing views. There is no identical oversized replay.
    for limit in (4096, 2048, 1024, 512, 256):
        candidate = copy.deepcopy(original)
        body = copy.deepcopy(envelope)
        projected, omissions = compact_evidence(retained, limit, nodes=min(2048, max(32, limit // 8)))
        for name in retained:
            body.pop(name, None)
        body.update(projected)
        body['evidence_view'] = {'complete': False, 'retained_digest': fingerprint(retained), 'omissions': omissions,
                                 'instruction': 'Omitted evidence remains stored. Do not claim it was inspected or that uncovered requirements passed.'}
        candidate['messages'][-1]['content'] = canonical(body)
        try:
            budget = plan_tokens(candidate, capability, counter, **reserves)
            return candidate, budget, {'reason': 'CONTEXT_BUDGET', 'source_digest': fingerprint(retained),
                                      'view_digest': fingerprint(projected), 'text_slice_bytes': limit,
                                      'omissions': omissions, 'objective_preserved': True, 'completion_reserves_preserved': True}
        except ExecutionBindingError as error:
            if error.code != 'EXECUTION_CONTEXT_EXHAUSTED':
                raise
    raise ExecutionBindingError('EXECUTION_CONTEXT_EXHAUSTED', 'Even the bounded evidence view does not fit the selected model. Confirmed work is retained; request a smaller stage or explicitly change the selected profile.')
