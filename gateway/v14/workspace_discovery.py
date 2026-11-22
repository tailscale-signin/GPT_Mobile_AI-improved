"""Progressive model-facing tool guidance for the current task profile.

Discovery does not confer authority: every action still checks source, policy,
lease and scope. Optional external inference is never enabled by model text.
"""


def tools(job):
    profile = job['data'].get('workspace_tool_profile') or {}
    if profile and profile.get('head_sha') == job['data'].get('head_sha'):
        from v14.workspace_intelligence import tools as selected_tools
        selected, missing = selected_tools(job, profile['profile'])
        return {'profile': profile['profile'], 'actions': selected, 'missing_tools': missing,
                'scope': 'DISCOVERY_ONLY', 'grants_authority': False}
    from v14.workspace_workers import ROLES
    actions = set().union(*ROLES.values()) | {'candidates', 'tool_profile', 'worker', 'worker_wave', 'worker_status',
        'task_plan', 'task_plan_status', 'task_plan_complete', 'context_pack', 'context_summary', 'integration_status',
        'integration_call', 'next_edit', 'next_edit_validate', 'terminal_open', 'terminal_start', 'terminal_poll',
        'terminal_cancel', 'terminal_close', 'terminal_fork', 'terminal_switch', 'terminal_timeline', 'code_intelligence',
        'agent_status', 'agent_events', 'agent_artifacts', 'agent_import', 'requirements', 'skills', 'skill', 'workflow_step',
        'workflow_batch', 'workflow_batch_status', 'workflow_batch_step', 'progress', 'revert_preview',
        'compile_module', 'lint_changed_files', 'check_patch'}
    return {'profile': 'default', 'actions': sorted(actions), 'missing_tools': [],
            'scope': 'DISCOVERY_ONLY', 'grants_authority': False}


def instruction(job):
    discovery = tools(job)
    selected = set(discovery['actions'])
    default = discovery['profile'] == 'default'
    chunks = ['To investigate, return {"workspace_action":{"action":"one discovered action",'
              '"head_sha":"exact current head",...}}. Use task_tools for the current discovery profile. '
              'tool_profile with profile plan/edit/review/test/retrieve changes discovery within the existing authority. ']

    def add(actions, guidance):
        if default or selected.intersection(actions):
            chunks.append(guidance)

    add({'paths', 'glob', 'search', 'repo_search', 'symbols', 'relationships', 'explore'},
        'paths uses a literal query and offset; glob uses observed filename patterns. search with paths searches selected files; '
        'repo_search or search without paths searches the pinned repository using query, mode literal/regex, glob and cursor. '
        'explore ranks task-relevant graph context; symbols and relationships inspect revision-bound source relationships. '
        'Follow next_cursor until relevant source is found; no index result is runtime proof. ')
    add({'read', 'instructions'},
        'read takes path, start_line and line_count (1–200). Read exact cited ranges before editing, including every incomplete applicable instruction. '
        'instructions with paths loads path-specific conventions. Use hunks:[{start_line:1,old:exact old text,new:replacement}] for repeated anchors. ')
    add({'checks'}, 'checks lists approved checks. ')
    add({'discover_tests'},
        'discover_tests lists cases and approved_check_indices; page its exact next_cursor as cursor, including within large files. ')
    add({'check'},
        'check takes check_index and optionally proposal with the normal changes contract; omitting proposal tests unchanged source. '
        'Use a unique attempt_id only for a deliberate fresh execution. '
        'Only host-approved commands execute; one exploratory check never replaces complete final validation. ')
    add({'run_test', 'compile_module', 'lint_changed_files', 'check_patch'},
        'run_test/compile_module/lint_changed_files/check_patch select an observed check_index, never command strings. '
        'Only host-approved commands execute; one exploratory check never replaces complete final validation. ')
    add({'check_output', 'inspect_failure'},
        'check_output takes check_id, start_line and line_count to read existing output. inspect_failure reads retained check evidence. ')
    add({'candidates', 'candidate_files', 'compare_candidates'},
        'candidates lists retained patches; candidate_files takes candidate_check_id. compare_candidates takes left_check_id, right_check_id and offset from next_offset. '
        'Finalize an unchanged tested candidate with {"candidate_check_id":"exact retained check ID","summary":"..."}. ')
    add({'worker', 'worker_wave'},
        'worker takes worker_id, objective, role planner/retrieval/editor/reviewer, observed paths, depends_on and budget (2–12 calls). '
        'Repeat the same worker_id to continue its immutable scope. worker_wave takes workers:[worker arguments] and runs dependency-ready read-only workers concurrently; '
        'edit/check operations retain serialized shared budgets. Worker routes remain the selected parent route. Select a worker candidate for normal review. ')
    add({'task_plan', 'task_plan_status', 'task_plan_complete'},
        'task_plan takes items:[{id,requirement_ids,depends_on,paths,role}] in dependency order and must cover all accepted requirements. '
        'task_plan_status reports readiness. task_plan_complete takes id and receipt_sha256:[exact passing check receipts]; prose cannot complete a task. ')
    add({'terminal_open', 'terminal_start', 'code_intelligence'},
        'An opted-in offline terminal uses terminal_open with session_id and optional candidate_check_id; terminal_start takes unique process_id, argv, cwd, env and timeout_seconds. '
        'terminal_poll uses process_id and byte offset; terminal_cancel/terminal_close end retained work. Reuse original IDs to reconcile. '
        'terminal_timeline lists checkouts; terminal_fork creates an isolated pinned-base or candidate checkout; terminal_switch selects a retained session. '
        'code_intelligence uses the open session, process_id, language kotlin/python/typescript, path, one-based line, UTF-16 column, '
        'operation definition/references/diagnostics/callers/supertypes/rename_preview and optional new_name. Poll for results. Missing servers need lexical fallback. ')
    if default:
        chunks.append('context_summary retains a bounded extractive summary and immutable evidence references. context_pack takes selected paths and engine builtin/repomix; '
                      'only an installed host-approved Repomix is used. integration_status discovers configured versioned-docs/browser tools; '
                      'integration_call must use an observed integration, exact tool and its input schema. '
                      'next_edit takes path and instruction only for a selected LOCAL_ONLY binding; next_edit_validate takes next_edit_id and approved check_index. '
                      'Suggestions never apply themselves. agent_status/agent_events/agent_artifacts/agent_import take an existing run_id; '
                      'agent_start, agent_compare, steering and plan approval require an authenticated human workspace command. Never manufacture consent. '
                      'revert_preview takes commit_sha; inspect affected conventions before returning {"revert_commit":"sha","summary":"reason"} for ordinary validation. ')
    chunks.append('requirements with offset reads acceptance requirements in groups of four. skills lists procedure/skill identities; skill with name loads one procedure. '
                  'workflow_step takes zero-based step and receipt_sha256. workflow_batch takes dependency-ordered items [{id,depends_on}]; '
                  'page workflow_batch_status and record workflow_batch_step with id, step and receipt_sha256. '
                  'progress with hypothesis, next_action and tried retains working notes. Each action continues this same bounded task. ')
    return ''.join(chunks)
