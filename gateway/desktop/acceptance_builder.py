"""Build exact task/test mappings from ordinary settings fields."""
from copy import deepcopy
import re


def add_behavior_check(options, *, objective, requirement_id, source_path, case_id, report_path, command_index, ci_check):
    from desktop.repository_validation import CUSTOM_PROFILE, validate_options
    from v14.engineering_validation import validate_policy_entry
    if not isinstance(objective, str) or not 3 <= len(objective.strip()) <= 16000:
        raise ValueError('Enter the exact task objective this check verifies.')
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', requirement_id):
        raise ValueError('Use a short requirement ID with letters, digits, hyphens or underscores.')
    for path in (source_path, report_path):
        if not path or path.startswith('/') or '\\' in path or any(part in {'', '.', '..', '.git'} for part in path.split('/')):
            raise ValueError('Source and JUnit report paths must be repository-relative files.')
    if not case_id.strip() or len(case_id) > 200 or not ci_check.strip() or len(ci_check) > 400:
        raise ValueError('Enter an exact JUnit case ID and GitHub check name.')
    if type(command_index) is not int or not 0 <= command_index < len(options['commands']):
        raise ValueError('Select an existing approved command that writes the JUnit report.')
    result = deepcopy(options)
    result['profile'] = CUSTOM_PROFILE
    if any(row['requirement_id'] == requirement_id for row in result['acceptance_map']):
        raise ValueError('That requirement ID already has a mapping. Choose a new ID or edit its existing mapping.')
    result['acceptance_map'].append({'requirement_id': requirement_id, 'case_id': case_id, 'command_index': command_index,
                                     'scope': 'behavior', 'report_path': report_path})
    if ci_check not in result['allowed_checks']:
        result['allowed_checks'].append(ci_check)
    requirement = {'id': requirement_id, 'paths': [source_path], 'checks': [ci_check],
                   'evidence': [{'kind': 'behavior_test', 'name': case_id}]}
    normalized = ' '.join(objective.casefold().split())
    templates = [row for row in result['acceptance_templates']
                 if normalized in {' '.join(term.casefold().split()) for term in row['objectives']}]
    if len(templates) > 1:
        raise ValueError('Several templates match this objective. Resolve the duplicate mappings first.')
    if templates:
        templates[0]['requirements'].append(requirement)
    else:
        result['acceptance_templates'].append({'objectives': [objective.strip()], 'requirements': [requirement]})
    # Editing an unprepared form never fabricates an image identity.
    validate_policy_entry(result, allow_unprepared_image=True)
    return validate_options(result)
