"""Request-owned sampling, separate from model identity, reasoning and write authority."""
import math
import re


def validate_policy(value):
    if (not isinstance(value, dict) or set(value) - {'version', 'mode', 'creativity'}
            or type(value.get('version')) is not int or value['version'] != 1
            or value.get('mode') not in {'ADAPTIVE', 'MANUAL'}
            or type(value.get('creativity')) not in (int, float)
            or not math.isfinite(value['creativity']) or not 0 <= value['creativity'] <= 1):
        raise ValueError('Invalid adaptive sampling policy')
    return dict(value)


def exploration_role(objective):
    text = objective[:16000].lower()
    if re.search(r'\b(design|redesign|brainstorm)\b.*\b(ui|ux|interface|screen|layout|visual)\b|\b(ui|ux|interface|visual)\b.*\b(design|redesign|concept)\b', text):
        return 'design options'
    if re.search(r'\b(architecture|architectural)\b', text):
        return 'architecture options'
    if re.search(r'\b(compare|evaluate|choose|explore)\b.*\b(alternatives|approaches|options|tradeoffs|trade-offs)\b', text):
        return 'decision options'
    return None


def resolve_policy(request, role):
    value = request.get('sampling_policy')
    if value is None:
        return None  # Retained jobs keep the exact sampling they originally accepted.
    policy = validate_policy(value)
    creativity = policy['creativity']
    stage, temperature = {
        'design options': ('DESIGN', .7 + .3 * creativity),
        'architecture options': ('ARCHITECTURE', .4 + .3 * creativity),
        'decision options': ('DECISION_OPTIONS', .4 + .3 * creativity),
        'planner': ('DECISION_SELECTION', .1),
        'repository planner': ('DECISION_SELECTION', .1),
        'coding agent': ('CODING', .2),
        'selected-model reviewer': ('VERIFICATION', .1),
        'independent reviewer': ('VERIFICATION', .1),
        'final summary': ('SUMMARY', .25 + .25 * creativity),
        'final report': ('SUMMARY', .25 + .25 * creativity),
    }.get(role, ('OPERATIONS', .05))
    if policy['mode'] == 'MANUAL':
        temperature = .2 + creativity
    return dict(policy, stage=stage, temperature=round(temperature, 6), top_p=None)


def generation_parameters(selected, policy):
    result = dict(selected)
    if policy is not None:
        result['temperature'] = policy['temperature']
        result.pop('top_p', None)
    return result


OPTIONS_INSTRUCTION = ('Explore 2 or 3 feasible approaches to the original task. Return only JSON: '
    '{"options":[{"id":"A","proposal":"...","tradeoffs":"...","assumptions":"..."}]}. '
    'Keep each field concise. Proposals and assumptions are not evidence. Do not claim tests or changes without receipts.')
SELECTION_INSTRUCTION = ('Select one proposed option against requirements, source evidence, testability, risk and maintenance cost. '
    'Alongside the required plan/paths, include selected_id, rationale, risks (array) and tests (array). '
    'Unknown evidence remains unknown. Selection never grants write or merge authority.')


def validate_options(value):
    options = value.get('options') if isinstance(value, dict) else None
    if not isinstance(options, list) or not 2 <= len(options) <= 3:
        raise ValueError('Expected two or three planning options')
    ids = []
    for item in options:
        if (not isinstance(item, dict) or not isinstance(item.get('id'), str)
                or not re.fullmatch(r'[A-Za-z0-9_-]{1,20}', item['id'])
                or any(not isinstance(item.get(key), str) or not item[key].strip() or len(item[key]) > 3000
                       for key in ('proposal', 'tradeoffs', 'assumptions'))):
            raise ValueError('Invalid planning option')
        ids.append(item['id'])
    if len(set(ids)) != len(ids):
        raise ValueError('Planning option identities must be unique')
    return ids


def validate_selection(value, ids):
    if (not isinstance(value, dict) or value.get('selected_id') not in ids
            or not isinstance(value.get('rationale'), str) or not value['rationale'].strip()):
        raise ValueError('Selection must identify and justify an existing option')
    for key in ('risks', 'tests'):
        rows = value.get(key)
        if not isinstance(rows, list) or not 1 <= len(rows) <= 12 or any(not isinstance(row, str) or not row.strip() or len(row) > 3000 for row in rows):
            raise ValueError('Selection needs bounded risks and validation')
