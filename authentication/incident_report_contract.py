"""Closed context-v7 incident capsule fields, mirrored by IssueReportIncidentCapsule.kt.

No free text, raw identifiers or argument values are accepted. Observations never grant
replay authority. Earlier approved context versions remain unchanged.
"""

from __future__ import annotations

import re


ACTIONS = {
    'agent_merge_status',
    'agent_merge_enable',
    'agent_merge_update',
    'agent_merge_disable',
    'artifacts',
    'branches',
    'cancel',
    'capabilities',
    'catalog',
    'checkpoint',
    'checks',
    'close',
    'comment',
    'comments',
    'commits',
    'compare',
    'context',
    'continue',
    'create',
    'create_issue',
    'create_pull_request',
    'definitions',
    'diff',
    'dispatch',
    'download',
    'file',
    'files',
    'headers',
    'history',
    'jobs',
    'labels',
    'list',
    'logs',
    'merge',
    'milestones',
    'read',
    'releases',
    'reopen',
    'rerun',
    'resolve_ref',
    'resolve_thread',
    'review',
    'reviews',
    'run',
    'runs',
    'search',
    'selection',
    'snapshot',
    'status',
    'submit_review',
    'tags',
    'task_status',
    'threads',
    'tree',
    'update',
    'update_issue',
    'update_pull_request',
    'verify',
    'workflows',
}

FIELD_NAMES = {
    'accept',
    'action',
    'artifact_id',
    'assignees',
    'base',
    'base_sha',
    'body',
    'branch',
    'check_run_id',
    'comment_id',
    'comments',
    'commit_id',
    'commit_message',
    'commit_title',
    'conclusion',
    'connection_id',
    'connection_uid',
    'content',
    'cursor',
    'direction',
    'download_url',
    'draft',
    'encoding',
    'event',
    'files',
    'filter',
    'head',
    'head_sha',
    'in_reply_to',
    'index_cursor',
    'inputs',
    'issue_number',
    'job_id',
    'labels',
    'limit',
    'line',
    'maintainer_can_modify',
    'merge_method',
    'message',
    'milestone',
    'model',
    'number',
    'operation',
    'operation_id',
    'order',
    'owner',
    'page',
    'path',
    'paths',
    'per_page',
    'pr_number',
    'pull_number',
    'pull_request_number',
    'q',
    'query',
    'recursive',
    'ref',
    'release_id',
    'repo',
    'repository',
    'repository_full_name',
    'repository_id',
    'review_id',
    'run_id',
    'schema',
    'schema_version',
    'search_id',
    'sha',
    'side',
    'since',
    'sort',
    'start_line', 'end_line', 'max_lines', 'start_offset', 'ranges', 'evidence_id',
    'start_side',
    'state',
    'state_reason',
    'status',
    'tag_name',
    'thread_id',
    'title',
    'until',
    'url',
    'view_cursor',
    'view_id',
    'workflow_id',
}

# Counts cover supplied array entries; omitted counts distinct known names beyond the retained eight.
POLICY_FIELD_NAMES = {
    'assist',
    'assistPolicy',
    'budget',
    'conversationDelegation',
    'conversationGateway',
    'conversationResearch',
    'delegation',
    'engineering',
    'githubProfileOverride',
    'githubProfileOverride.webSearch',
    'githubProfileOverride.githubTools',
    'githubProfileOverride.engineeringMode',
    'githubProfileOverride.maximumContext',
    'githubProfileOverride.engineeringMemory',
    'githubProfileOverride.allowCloudEngineeringMemory',
    'githubProfileOverride.workflowPreset',
    'githubProfileOverride.quickWorkflows',
    'githubProfileOverride.engineeringGatewayProfileUid',
    'githubProfileOverride.executionBatchMode',
    'githubProfileOverride.sampling',

    'githubProfileSelection',
    'llamaReasoning',
    'location',
    'plugins',
    'profile',
    'profile.accelerator',
    'profile.apiUrl',
    'profile.batchApiUrl',
    'profile.batchMode',
    'profile.compatibleType',
    'profile.dangerousContentSafetyThreshold',
    'profile.disableAllTools',
    'profile.disableLocalTools',
    'profile.disableRemoteTools',
    'profile.enabled',
    'profile.harassmentSafetyThreshold',
    'profile.hateSpeechSafetyThreshold',
    'profile.id',
    'profile.isFavorite',
    'profile.labels',
    'profile.maxTokens',
    'profile.maxToolCalls',
    'profile.model',
    'profile.name',
    'profile.ollamaOptions',
    'profile.openRouterRouting',
    'profile.providerConnectionUid',
    'profile.reasoning',
    'profile.removed',
    'profile.secretRef',
    'profile.sexuallyExplicitSafetyThreshold',
    'profile.stream',
    'profile.systemPrompt',
    'profile.temperature',
    'profile.timeout',
    'profile.token',
    'profile.topK',
    'profile.topP',
    'profile.uid',
    'providerPermissions',
    'reasoning',
    'remoteMcp',
    'researchEnabled',
    'researchReviewer',
    'spend',
    'tool_inventory',
    'tools',
}
POLICY_COUNTS = {'changed_policy_field_count', 'changed_policy_unknown_count', 'changed_policy_malformed_count', 'changed_policy_omitted_count'}
POLICY_DIAGNOSTICS = POLICY_COUNTS | {'changed_policy_fields', 'changed_policy_observation'}

FAILED_EVENT_FIELDS = {'failed_event_code_present', 'failed_event_invocation_present', 'failed_event_details_present'}
COMMON = {
    'sqlite_constraint_kind',
    'dispatch_policy_sections',
    'dispatch_policy_stage',
    'dispatch_policy_resume_required',
    'incident_capture_site', 'incident_phase_provenance',
    'retry_delay_source',
    'provider_wait_state',
    'provider_wait_persisted',
    'recovery_authority',
    'exception_type_hashes',
    'causal_failure_code',
    'causal_id_hash',
    'causal_provenance',
    'incident_evidence_status',
    'incident_origin',
    'incident_phase',
    'recovery_attempt_count',
    'recovery_observation',
    'retry_delay_ms',
} | POLICY_DIAGNOSTICS | FAILED_EVENT_FIELDS

TOOL_REPEAT_COUNTS = {'dependency_repeat_prior_admissions', 'dependency_repeat_batch_admissions', 'dependency_repeat_used', 'dependency_repeat_limit'}
TOOL_REPEAT_FIELDS = TOOL_REPEAT_COUNTS | {'dependency_repeat_scope'}
REPEAT_HISTORY_HASH_FIELDS = {'dependency_repeat_prior_call_id_hashes', 'dependency_repeat_batch_call_id_hashes'}
REPEAT_HISTORY_LIST_FIELDS = REPEAT_HISTORY_HASH_FIELDS | {'dependency_repeat_prior_outcomes'}
REPEAT_HISTORY_FIELDS = REPEAT_HISTORY_LIST_FIELDS | {'dependency_repeat_prior_coverage', 'dependency_repeat_batch_coverage'}
REPEAT_HISTORY_OUTCOMES = {'UNOBSERVED', 'DISPATCHED_SUCCESS', 'DISPATCHED_FAILURE', 'NOT_DISPATCHED', 'SHARED_RESULT', 'UNKNOWN_OUTCOME'}
INVENTORY_PAGINATION_FIELDS = {'inventory_pagination_reason', 'inventory_restart_offered'}
REMOTE_PAGINATION_COUNTS = {
    'remote_pagination_page', 'remote_pagination_pages_observed', 'remote_pagination_reported_total',
    'remote_pagination_unique_records', 'remote_pagination_duplicate_records',
}
REMOTE_PAGINATION_FLAGS = {'remote_pagination_contiguous', 'remote_pagination_changed'}
REMOTE_PAGINATION_FIELDS = REMOTE_PAGINATION_COUNTS | REMOTE_PAGINATION_FLAGS | {'remote_pagination_reason'}
PR_CONTEXT_RACE_FIELDS = {'pr_context_revision_changed', 'pr_context_read_requests', 'pr_context_read_responses'}
POLICY_RESPONSE_HEADER_ORDER = ('REPORTED_OAUTH_SCOPES', 'ACCEPTED_OAUTH_SCOPES', 'ACCEPTED_GITHUB_PERMISSIONS', 'SSO')
POLICY_RESPONSE_HEADER_FIELDS = {'status_policy_protection_permission_header_names', 'status_policy_rules_permission_header_names'}
POLICY_RESPONSE_FIELDS = POLICY_RESPONSE_HEADER_FIELDS | {'status_policy_protection_404_kind', 'status_policy_rules_404_kind'}

TOOL_FIELDS = {
    'url_reader_dns_state', 'url_reader_availability', 'tool_execution_observation', 'tool_budget_kind', 'tool_budget_phase', 'tool_result_bytes_used', 'tool_result_bytes_limit',
    'tool_budget_calls_used', 'tool_budget_calls_limit', 'tool_permission_scope',
    'semantic_read_rules', 'semantic_ci_rules', 'search_validation_field_names', 'dependency_admission_reasons',
    'native_dependency_state', 'dependency_call_id_hashes', 'dependency_count', 'dependency_failed_count', 'search_cursor_state', 'search_id_hash', 'search_cursor_hash',
    'mcp_connection_hash', 'mcp_failure_phase', 'mcp_failure_category', 'mcp_connection_state', 'mcp_readiness_state', 'mcp_connection_health', 'mcp_health_observation', 'mcp_health_age_ms',
    'saved_view_restore_state', 'saved_view_restore_error_hash',
    'argument_repair_signature_hash', 'argument_repair_attempt', 'argument_repair_exhausted',
    'saved_view_scope_hash',
    'action_schema_generation_hash',
    'argument_field_names',
    'argument_field_types',
    'github_error_codes',
    'github_error_fields',
    'github_error_resources',
    'native_tool_action',
    'resolved_argument_field_names',
    'resolved_argument_field_types',
    'resolved_argument_shape_hash',
    'saved_view_connection_hash',
    'saved_view_cursor_hash',
    'saved_view_cursor_issued',
    'saved_view_expired',
    'saved_view_owner_operation',
    'saved_view_source_hash',
    'server_request_id_hash',
    'tool_contract_version',
    'unknown_field_count',
    'unknown_field_hashes',
    'validation_field_names',
    'validation_path_hashes',
} | TOOL_REPEAT_FIELDS | REMOTE_PAGINATION_FIELDS

POLICY_OBSERVATION = {'OBSERVED', 'UNOBSERVED', 'INVALID_RESPONSE', 'ACCESS_DENIED', 'NOT_FOUND_OR_INACCESSIBLE', 'RATE_LIMITED', 'UNAVAILABLE', 'HTTP_FAILURE'}

ACCEPTANCE_BLOCKER_FIELDS = {'fixture_approval_blocked_count', 'owned_fixture_blocked_count', 'pending_local_fragments', 'pending_remote_continuations'}
ACCEPTANCE_FIELDS = {
    'fixture_approval_state', 'fixture_decision_mode', 'branch_protection_observation', 'branch_rules_observation',
    'receipt_count_scope',
    'acceptance_evaluator_branch',
    'acceptance_mismatch',
    'blocked_capability_count',
    'eligible_receipt_count',
    'evidence_ref_hashes',
    'excluded_receipt_count',
    'expected_action',
    'expected_operation',
    'receipt_exclusion_reasons',
    'required_capability_count',
    'task_kind',
    'untested_capability_count',
    'verified_capability_count',
}

COMBINED_FIELDS = {'combined_requirement_gap', 'combined_issue_count', 'combined_failed_check_count', 'combined_unverified_check_count', 'combined_repair_rounds', 'combined_repair_stop', 'pending_tool_count', 'pending_tool_identity_hashes', 'pending_tool_receipt_state', 'local_readiness_failure'}
HANDOFF_RECONCILIATION_COUNTS = {'handoff_replay_receipt_count', 'handoff_replay_matched_count', 'handoff_durable_receipt_count', 'handoff_durable_matched_count', 'handoff_retained_matched_count'}
HANDOFF_RECONCILIATION_HASHES = {'handoff_tool_binding_hash', 'handoff_saved_tool_binding_hash', 'handoff_evidence_digest_hash', 'handoff_journal_digest_hash'}
HANDOFF_RECONCILIATION_FIELDS = HANDOFF_RECONCILIATION_COUNTS | HANDOFF_RECONCILIATION_HASHES | {'handoff_checkpoint_state', 'handoff_durable_lookup'}
CHAT_DELIVERY_COUNTS = {'chat_delivery_content_bytes', 'chat_delivery_thinking_bytes', 'chat_delivery_success_events', 'chat_delivery_tool_events', 'chat_delivery_progress_events', 'chat_delivery_github_rounds'}
CHAT_DELIVERY_FIELDS = CHAT_DELIVERY_COUNTS | {'chat_delivery_terminal', 'chat_delivery_answer_state', 'chat_delivery_counts_capped'}
CLAIM_FRESHNESS_FIELDS = {'acceptance_claim_freshness_predicates'}
ACCEPTANCE_PUBLICATION_COUNTS = {'acceptance_extraction_planned_batches', 'acceptance_receipt_snapshot_count', 'acceptance_claim_count'}
ACCEPTANCE_PUBLICATION_TOKENS = {'acceptance_extraction_required_tokens', 'acceptance_extraction_input_limit_tokens'}
ACCEPTANCE_PUBLICATION_FIELDS = ACCEPTANCE_PUBLICATION_COUNTS | ACCEPTANCE_PUBLICATION_TOKENS | {'acceptance_publication_source', 'acceptance_extraction_observation', 'acceptance_draft_state'}
ACCEPTANCE_OBSERVATION_FIELDS = {'acceptance_claim_trust_failures', 'acceptance_publication_entry'}
REVIEW_COMPARISON_OBSERVATION_FIELDS = {'review_comparison_state', 'review_comparison_policy'}
TOOL_EVENT_SIGNED_SEQUENCES = {'tool_event_requested_sequence', 'tool_event_previous_max_sequence'}
TOOL_EVENT_PERSISTENCE_FIELDS = TOOL_EVENT_SIGNED_SEQUENCES | {'tool_event_persistence_operation', 'tool_event_persistence_phase', 'tool_event_persistence_failure', 'tool_event_assigned_sequence', 'tool_event_write_attempted'}
PROVIDER_FIELDS = {'claim_extraction_schema_hash', 'provider_route_hash', 'provider_wait_checkpoint_hash', 'provider_wait_claim_hash', 'provider_wait_terminal_status', 'tls_failure_kind', 'tls_response_started', 'provider_local_budget_block', 'provider_tool_rejection', 'tool_protocol_frame_kind', 'tool_protocol_recovery_outcome', 'claim_extraction_failure_kind', 'claim_extraction_response_characters', 'claim_extraction_max_claims', 'claim_extraction_completed', 'reasoning_probe_observation', 'gateway_upstream_status', 'gateway_dependency', 'gateway_terminal_status', 'gateway_result_quality', 'provider_rejection_category', 'provider_error_type_hash', 'provider_parameter', 'provider_parameter_path_hash', 'payload_contract', 'privacy_catalog_entries', 'privacy_model_endpoint_count', 'privacy_compatible_endpoint_count', 'privacy_allowlist_excluded_count', 'privacy_denylist_excluded_count', 'privacy_fallback_excluded_count', 'privacy_metadata_age_ms', 'privacy_blockers'}
VOICE_FIELDS = {'run_id_hash', 'invocation_id_hash', 'voice_reply_failed', 'voice_reply_terminal_status', 'voice_reply_failure_evidence', 'voice_chat_failure_code', 'voice_chat_failure_code_hash', 'voice_chat_failure_phase', 'voice_focus_last_transition', 'voice_focus_transition_count', 'voice_pronunciation_unknown_words', 'voice_pronunciation_initialisms', 'voice_pronunciation_failure_kind', 'voice_pronunciation_audio_queued', 'voice_pronunciation_first_audio_observed'}

GATEWAY_FIELDS = {
    'command_id_hash',
    'command_receipt_observation',
    'gateway_package_digest_hash',
    'gateway_source_commit_hash',
}

HASHES = {
    'search_id_hash', 'search_cursor_hash', 'provider_wait_checkpoint_hash', 'provider_wait_claim_hash', 'provider_route_hash',
    'mcp_connection_hash',
    'saved_view_restore_error_hash',
    'provider_error_type_hash', 'provider_parameter_path_hash', 'claim_extraction_schema_hash', 'voice_chat_failure_code_hash',
    'argument_repair_signature_hash',
    'saved_view_scope_hash',
    'action_schema_generation_hash',
    'causal_id_hash',
    'command_id_hash',
    'gateway_package_digest_hash',
    'gateway_source_commit_hash',
    'resolved_argument_shape_hash',
    'saved_view_connection_hash',
    'saved_view_cursor_hash',
    'saved_view_source_hash',
    'server_request_id_hash',
} | HANDOFF_RECONCILIATION_HASHES

HASH_LISTS = {
    'dependency_call_id_hashes', 'pending_tool_identity_hashes',
    'exception_type_hashes',
    'evidence_ref_hashes',
    'unknown_field_hashes',
    'validation_path_hashes',
}

FLAGS = FAILED_EVENT_FIELDS | REMOTE_PAGINATION_FLAGS | {
    'inventory_restart_offered',
    'tool_event_write_attempted',
    'chat_delivery_counts_capped',
    'claim_extraction_completed',
    'tls_response_started', 'provider_local_budget_block',
    'dispatch_policy_resume_required',
    'voice_reply_failed',
    'argument_repair_exhausted',
    'provider_wait_persisted',
    'saved_view_cursor_issued',
    'saved_view_expired',
    'pr_context_revision_changed',
    'voice_pronunciation_audio_queued', 'voice_pronunciation_first_audio_observed',
}

COUNTS = {
    'tool_result_bytes_used', 'tool_result_bytes_limit', 'tool_budget_calls_used', 'tool_budget_calls_limit', 'combined_issue_count', 'combined_failed_check_count', 'combined_unverified_check_count', 'combined_repair_rounds',
    'dependency_count', 'dependency_failed_count', 'pending_tool_count',
    'claim_extraction_response_characters', 'claim_extraction_max_claims',
    'privacy_catalog_entries', 'privacy_model_endpoint_count', 'privacy_compatible_endpoint_count', 'privacy_allowlist_excluded_count', 'privacy_denylist_excluded_count', 'privacy_fallback_excluded_count',
    'blocked_capability_count',
    'eligible_receipt_count',
    'excluded_receipt_count',
    'recovery_attempt_count',
    'required_capability_count',
    'unknown_field_count',
    'untested_capability_count',
    'verified_capability_count',
} | REMOTE_PAGINATION_COUNTS | POLICY_COUNTS | ACCEPTANCE_BLOCKER_FIELDS | CHAT_DELIVERY_COUNTS | ACCEPTANCE_PUBLICATION_COUNTS | TOOL_REPEAT_COUNTS | {
    'pr_context_read_requests', 'pr_context_read_responses',
    'voice_focus_transition_count', 'voice_pronunciation_unknown_words', 'voice_pronunciation_initialisms'
}

TYPES = {
    'ARRAY',
    'BOOLEAN',
    'NULL',
    'NUMBER',
    'OBJECT',
    'STRING',
}

CORE = {
    'causal_id_hash',
    'causal_provenance',
    'incident_evidence_status',
    'incident_phase',
}

PHASES = {
    'acceptance_verification',
    'admission',
    'answer_review',
    'approval',
    'audio_focus',
    'backend_admission',
    'backend_template',
    'binding_admission',
    'cancelled',
    'capture_configuration',
    'capture_initialization',
    'capture_read',
    'capture_start',
    'claim_extraction',
    'claim_verification',
    'connection',
    'connection_preflight',
    'conversation_admission',
    'delivery',
    'dns',
    'dns_resolution',
    'engineering_guard',
    'execution_admission',
    'final_synthesis',
    'gateway_dispatch',
    'gateway_recovery',
    'inventory_pagination',
    'lifecycle',
    'message_admission',
    'model_invocation',
    'provider_wait',
    'model_preparation',
    'planning',
    'precheck',
    'provider_admission',
    'provider_generation',
    'redirect_precheck',
    'repository_admission',
    'request_validation',
    'response_stream',
    'retrieval',
    'review_comparison',
    'review_compatibility',
    'task_budget',
    'tls',
    'tool_admission',
    'tool_continuation',
    'tool_dispatch',
    'tool_execution',
    'tool_precheck',
    'tool_readiness',
    'tool_reconciliation',
    'transport',
    'unknown',
    'verification',
    'voice_capture',
    'voice_playback',
    'voice_reply',
    'voice_response_wait',
    'voice_recognition',
}

OPERATIONS = {
    'github_ci',
    'github_commit',
    'github_context',
    'github_engineering',
    'github_issue',
    'github_pr',
    'github_read',
    'github_review',
    'github_search',
    'mcp',
    'read_url',
    'repository_graph',
    'voice_capture',
    'voice_playback',
    'voice_recognition',
    'web_search',
}

MCP_HEALTH = {'DNS_FAILED', 'DNS_RECOVERY', 'ROUTE_UNAVAILABLE', 'CONNECTION_REFUSED', 'AUTHENTICATION_FAILED', 'TLS_FAILED', 'CONNECT_TIMEOUT', 'HTTP_ERROR', 'MCP_HANDSHAKE_FAILED', 'STREAM_INTERRUPTED', 'STREAM_IDLE_TIMEOUT', 'REQUEST_TIMEOUT', 'HEALTHY', 'UNKNOWN'}

ENUMS = {
    'claim_extraction_failure_kind': {'INVALID_JSON', 'NON_OBJECT', 'SCHEMA_REJECTED'},
    'inventory_pagination_reason': {'MISSING_VERSION', 'VERSION_MISMATCH'},
    'sqlite_constraint_kind': {'UNIQUE', 'FOREIGN_KEY', 'NOT_NULL', 'CHECK', 'PRIMARY_KEY', 'TRIGGER', 'UNKNOWN'},
    'dependency_repeat_scope': {'TOOL_NAME', 'TOOL_NAME_AND_ARGUMENTS'},
    'tool_event_persistence_operation': {'START'},
    'tool_event_persistence_phase': {'ALLOCATE_SEQUENCE', 'INSERT'},
    'tool_event_persistence_failure': {'RUN_SEQUENCE_CONFLICT', 'EVENT_ID_CONFLICT', 'RUN_FOREIGN_KEY', 'NOT_NULL', 'UNKNOWN_CONSTRAINT', 'SEQUENCE_EXHAUSTED'},
    'review_comparison_state': {'HEAD_REQUIRED', 'BASELINE_REQUIRED', 'BASE_REF_REQUIRED', 'BASE_REF_BLOCKED', 'PR_BASE_LOOKUP', 'PR_BASE_UNVERIFIED', 'BASE_SHA_REQUIRED', 'COMPARISON_PAGES_PENDING', 'READ_CHANGED_SOURCE', 'COMPARE_REQUIRED'},
    'review_comparison_policy': {'UNRESOLVED', 'EXPLICIT_BASE_SHA', 'EXPLICIT_BASE_REF', 'OBSERVED_PR_BASE', 'DEFAULT_BRANCH_HEAD'},
    'acceptance_publication_entry': {'EVIDENCE_RETENTION_INCOMPLETE', 'TASK_DEADLINE', 'APPROVAL_CHECKPOINT', 'SAVED_CLAIM_EXTRACTION', 'CI_OBSERVATION', 'INTEGRATION_COOLDOWN', 'INTEGRATION_CHECKPOINT', 'INTEGRATION_BLOCKED', 'STATUS_COLLECTION', 'STATUS_SNAPSHOT', 'COVERAGE_CHECKPOINT', 'REVIEW_COMPARISON_BLOCKED', 'PROSE_ONLY_CORRECTION', 'COMPLETED_DRAFT', 'RETAINED_REPORT_UNAVAILABLE', 'RETAINED_FAILURE'},
    'chat_delivery_terminal': {'DONE'},
    'chat_delivery_answer_state': {'EMPTY_CONTENT', 'NO_ANSWER_CONTENT', 'GITHUB_ANSWER_PENDING'},
    'handoff_checkpoint_state': {'TOOL_HANDOFF', 'TOOL_RESULTS_READY'},
    'handoff_durable_lookup': {'RETURNED_EMPTY', 'RETURNED_RECEIPTS'},
    'url_reader_dns_state': {'RECOVERY_COOLDOWN', 'LOOKUP_FAILED'},
    'url_reader_availability': {'HTTP_UNAVAILABLE', 'NEGATIVE_CACHE', 'TURN_SUPPRESSION', 'REPEAT_LIMIT', 'REPEATED_FAILURE'},
    'fixture_decision_mode': {'AUTO_APPROVE', 'SUGGESTED', 'ASK', 'UNKNOWN'},
    'crash_surface': {'CHAT', 'DEBUG_LOGS', 'GITHUB_WORKSPACE'},
    'crash_boundary': {'ANDROID_VIEW_DRAW'},
    'pending_tool_receipt_state': {'CHECKPOINT_STATE_INCONSISTENT', 'TERMINAL_RECEIPTS_MISSING', 'SOME_TERMINAL_RECEIPTS_MISSING', 'OUTCOMES_UNRESOLVED'},
    'local_readiness_failure': {'INSUFFICIENT_MEMORY', 'PENDING_CLEANUP', 'VERIFICATION_REQUIRED', 'UNSUPPORTED_BACKEND', 'INCOMPATIBLE_MODEL', 'MISSING_RUNTIME', 'INITIALIZATION_TIMEOUT', 'UNKNOWN'},
    'fixture_approval_state': {'READY', 'CONDITIONAL', 'UNKNOWN'},
    'branch_protection_observation': POLICY_OBSERVATION,
    'branch_rules_observation': POLICY_OBSERVATION,
    'dependency_repeat_prior_coverage': {'COMPLETE', 'PARTIAL', 'UNAVAILABLE'},
    'dependency_repeat_batch_coverage': {'COMPLETE', 'PARTIAL', 'UNAVAILABLE'},
    'status_policy_protection_404_kind': {'NOT_FOUND', 'OTHER_404', 'EXPLICIT_BRANCH_NOT_PROTECTED'},
    'status_policy_rules_404_kind': {'NOT_FOUND', 'OTHER_404'},
    'provider_wait_terminal_status': {'COMPLETED', 'PARTIAL', 'FAILED', 'BLOCKED', 'INTERRUPTED', 'CANCELED', 'EVIDENCE_MISSING', 'WAITING_FOR_APPROVAL'},
    'changed_policy_observation': {'OBSERVED', 'PARTIAL', 'MALFORMED'},
    'incident_capture_site': {'CHAT_REPOSITORY_TERMINAL_CATCH', 'CHAT_REPOSITORY_PREFLIGHT', 'OPENAI_ADAPTER_STREAM_CATCH', 'PROVIDER_ADAPTER_STREAM_CATCH', 'CHAT_REPOSITORY_ERROR_VALUE', 'CHAT_FLOW_EMPTY_ANSWER', 'AGENT_FAILED_EVENT'},
    'incident_phase_provenance': {'CAPTURE_BOUNDARY_ONLY'},
    'native_dependency_state': {'DEFERRED', 'FAILED'},
    'tool_execution_observation': {'REMOTE_READ_SUCCEEDED'},
    'tool_budget_kind': {'CALLS', 'RESULT_BYTES', 'BOTH'},
    'tool_budget_phase': {'BEFORE_DISPATCH', 'AFTER_DISPATCH'},
    'tool_permission_scope': {'ANDROID_LOCATION', 'GITHUB_LOCAL_GRANT'},
    'tls_failure_kind': {'CERTIFICATE', 'PROTOCOL', 'HANDSHAKE', 'OTHER'},
    'remote_pagination_reason': {'INCOMPLETE', 'STALLED'},
    'provider_tool_rejection': {'TOOL_USE_FAILED'},
    'tool_protocol_frame_kind': {'MISSING_FUNCTION_NAME', 'UNFINISHED_STREAM', 'NON_OBJECT_ARGUMENTS', 'MALFORMED_JSON'},
    'tool_protocol_recovery_outcome': {'TEXT_FALLBACK_EXHAUSTED', 'TOOL_RETRY_EXHAUSTED', 'BACKEND_HANDLED_TOOLS', 'SINGLE_PASS_NOT_RETRYABLE', 'NO_SAFE_REPAIR'},
    'reasoning_probe_observation': {'GATEWAY_OBSERVATION_AVAILABLE', 'DEADLINE_WITHOUT_GATEWAY_OBSERVATION', 'NO_GATEWAY_OBSERVATION'},
    'gateway_terminal_status': {'completed', 'failed', 'cancelled', 'canceled', 'interrupted'},
    'gateway_result_quality': {'complete', 'partial'},
    'combined_repair_stop': {'CORRECTION_CAPACITY_UNAVAILABLE', 'LEGACY_REVISION_REQUIRES_NEW_OWNER', 'LEGACY_REPAIR_ALLOWANCE_REACHED', 'NO_PROGRESS_AFTER_REQUIRED_REVISIONS', 'NO_PROGRESS_AFTER_REQUIRED_REVISION', 'NEWER_REVISION_OR_UNAVAILABLE_REPLACEMENT_OWNER', 'REPAIR_REJECTED', 'NO_REMAINING_REQUIRED_CORRECTION', 'REPAIR_PREPARATION_FAILED', 'REPLACEMENT_MADE_NO_TEXT_PROGRESS', 'REPLACEMENT_OWNER_COMPARE_AND_SET_REJECTED'},
    'combined_requirement_gap': {'CURRENT_ARTICLE', 'LENGTH', 'TOPIC', 'OTHER'},
    'search_cursor_state': {'RETAINED_SEARCH_MISSING', 'PAGE_BOUNDARY_UNOBSERVED'},
    'mcp_failure_phase': {'configuration', 'catalog', 'discovery', 'initialization', 'execution', 'connection', 'dns', 'tls', 'oauth_refresh', 'local_validation'},
    'mcp_failure_category': (MCP_HEALTH - {'HEALTHY'}) | {'MCP_TOOL_ERROR', 'MCP_OUTPUT_INVALID'},
    'mcp_connection_health': MCP_HEALTH,
    'mcp_connection_state': {'CONNECTED', 'DEGRADED', 'UNREACHABLE', 'AUTHENTICATION_REQUIRED', 'RECOVERING'},
    'mcp_readiness_state': {'NOT_DISCOVERED', 'DISCOVERING', 'READY', 'STALE'},
    'mcp_health_observation': {'RETAINED_SNAPSHOT', 'UNAVAILABLE'},
    'saved_view_restore_state': {'NOT_FOUND', 'LOAD_FAILED', 'IDENTITY_REJECTED', 'RESTORED', 'BODY_MISSING', 'BODY_MISSING_INDEX_PRESENT', 'BODY_UNREADABLE', 'CURSOR_INDEX_MISSING', 'INVALID_VIEW_ID'},
    'provider_rejection_category': {'BILLING_UNAVAILABLE', 'INVALID_REQUEST', 'AUTHENTICATION', 'PERMISSION', 'RATE_LIMIT', 'NOT_FOUND', 'GONE', 'UNAVAILABLE', 'PAYLOAD_SIZE', 'MODEL_UNAVAILABLE', 'UNKNOWN'},
    'provider_parameter': {'model', 'messages', 'max_tokens', 'max_completion_tokens', 'temperature', 'top_p', 'stop', 'tools', 'tool_choice', 'stream', 'response_format', 'reasoning', 'thinking', 'metadata', 'system'},
    'payload_contract': {'CHAT_COMPLETIONS', 'ANTHROPIC_MESSAGES', 'GOOGLE_CONTENT', 'GATEWAY_GENERATION'},
    'voice_reply_terminal_status': {'COMPLETED', 'FAILED', 'CANCELED', 'INTERRUPTED', 'PARTIAL', 'EVIDENCE_MISSING', 'BLOCKED', 'WAITING_FOR_APPROVAL'},
    'voice_reply_failure_evidence': {'TYPED_INVOCATION_REFERENCE', 'CLASSIFICATION_ONLY', 'MISSING_FAILURE_RECORD'},
    'voice_chat_failure_phase': PHASES - {'unknown'},
    'voice_pronunciation_failure_kind': {'UNKNOWN_WORD', 'UNSUPPORTED_SCRIPT', 'MISSING_LETTER'},
    'voice_focus_last_transition': {'RESTORE', 'PAUSE', 'DUCK', 'STOP'},
    'receipt_count_scope': {'LINKED_CRITERION_EVIDENCE'},
    'acceptance_publication_source': {'STATUS', 'COVERAGE', 'CI_OBSERVATION', 'CLAIM_EXTRACTION', 'PARTIAL_FALLBACK', 'EVIDENCE_ADVANCED'},
    'acceptance_extraction_observation': {'NOT_NEEDED', 'NO_DRAFT', 'PLAN_BATCH_LIMIT', 'PLAN_INPUT_LIMIT', 'PLAN_LIMIT_CHANGED', 'COMPLETED', 'FAILED'},
    'acceptance_draft_state': {'ABSENT', 'EMPTY', 'PRESENT'},
    'provider_wait_state': {'WAITING_PROVIDER_LIMIT', 'CHECKPOINT_UNAVAILABLE', 'AUTHORITY_CHANGED', 'PERSIST_FAILED', 'NOT_SCHEDULED', 'RESUMED'},
    'retry_delay_source': {'PROVIDER_HEADER', 'PROVIDER_BODY', 'LOCAL_BACKOFF', 'UNKNOWN'},
    'recovery_authority': {'VALID', 'CHANGED', 'UNVERIFIED'},
    'causal_provenance': {'GATEWAY_COMMAND', 'UNAVAILABLE', 'MODEL_INVOCATION', 'EXPLICIT_CAUSE', 'TOOL_CALL'},
    'incident_evidence_status': {'TYPED_EVIDENCE', 'CAUSAL_EVIDENCE_UNAVAILABLE'},
    'incident_origin': {'GATEWAY', 'TOOL', 'LOCAL_RECOVERY', 'ACCEPTANCE', 'PROVIDER', 'APPLICATION', 'UNKNOWN'},
    'recovery_observation': {'RECONCILIATION_REQUIRED', 'WAITING_RETRY', 'RETRY_SCHEDULED', 'RECOVERED', 'UNOBSERVED', 'RETRY_ATTEMPTED', 'TERMINAL'},
    'task_kind': {'INTEGRATION_TEST', 'STATUS_HEALTH', 'GENERAL', 'REVIEW', 'CI_OBSERVATION', 'INVESTIGATION', 'STATUS_SNAPSHOT', 'UNCLASSIFIED', 'IMPLEMENTATION', 'READ_ONLY'},
    'acceptance_evaluator_branch': {'INFORMATIONAL_CLAIMS', 'OPERATION_ACCEPTANCE', 'REVISION_TEST_ACCEPTANCE', 'ACCEPTANCE_MAPPING_REQUIRED', 'CAPABILITY_COVERAGE', 'STATUS_UNCERTAINTY_REPORT', 'REQUIREMENT_MATCH', 'FIXTURE_LIFECYCLE', 'STATUS_OBSERVATION', 'REVIEW_COMPARISON', 'UNCLASSIFIED', 'EXECUTION_LEDGER', 'CI_REVISION_BINDING'},
    'acceptance_mismatch': {'UNRESOLVED_USER_DECISION', 'ACCEPTANCE_MAPPING_REQUIRED', 'NO_MATCHING_CLAIMS', 'LINKED_RECEIPT_INELIGIBLE', 'COMPARISON_REQUIRED', 'ACCEPTANCE_EVIDENCE_MISSING', 'UNSUPPORTED_CLAIMS', 'NO_EXECUTION_PROOF', 'SCOPE_MISMATCH', 'NO_ELIGIBLE_RECEIPT', 'OPERATION_MISMATCH', 'UNSUPPORTED_CRITERION', 'STATUS_NOT_PASS', 'MATCHING_OBSERVATION_INCOMPLETE', 'EVIDENCE_UNAVAILABLE', 'REVISION_MISMATCH', 'ACTION_MISMATCH', 'UNRESOLVED_OUTCOME'},
    'command_receipt_observation': {'CONTRACT_UNSUPPORTED', 'UNAVAILABLE_404', 'UNOBSERVED', 'PRESENT', 'READ_INTERRUPTED'},
    'incident_phase': PHASES,
    'dispatch_policy_stage': {'provider_admission', 'tool_precheck', 'tool_dispatch', 'tool_execution', 'delivery', 'verification'},
    'native_tool_action': ACTIONS,
    'saved_view_owner_operation': OPERATIONS,
    'expected_operation': OPERATIONS,
    'expected_action': ACTIONS,
}

ENUM_LISTS = {
    'status_policy_protection_permission_header_names': set(POLICY_RESPONSE_HEADER_ORDER),
    'status_policy_rules_permission_header_names': set(POLICY_RESPONSE_HEADER_ORDER),
    'acceptance_claim_trust_failures': {'TOOL_NOT_IN_AUTHORIZED_CATALOG', 'UNRECOGNIZED_EVIDENCE_OPERATION', 'TARGET_OUTSIDE_ACCEPTED_SCOPE'},
    'acceptance_claim_freshness_predicates': {'OBSERVATION_TIME_UNAVAILABLE', 'OBSERVATION_TIME_FUTURE', 'MUTABLE_AGE_EXCEEDED', 'MUTABLE_EPOCH_MISMATCH'},
    'semantic_read_rules': {'RANGE_ARRAY', 'SOURCE_REQUIRED', 'RANGE_OBJECT', 'RANGE_FIELDS', 'REPOSITORY_FILE_PATH', 'RANGE_OFFSET', 'LINE_WINDOW'},
    'semantic_ci_rules': {'BRANCH_NAME', 'COMMIT_SHA', 'POSITIVE_ID', 'WORKFLOW_SELECTOR', 'MISSING_SELECTOR', 'UNUSED_FIELD', 'LOG_START', 'LOG_ORDER', 'LOG_SPAN'},
    'search_validation_field_names': {'fuzzy_threshold', 'context_lines', 'max_new_blobs', 'batch_size'},
    'dependency_admission_reasons': {'TOOL_UNAVAILABLE', 'REPEATED_FAILURE', 'REPEAT_LIMIT', 'CALL_ALLOWANCE', 'DECISION_REQUIRED', 'PREREQUISITE_DEFERRED'},
    'dispatch_policy_sections': {'PROFILE', 'BUDGET', 'SPEND', 'TOOLS', 'MCP', 'PERMISSIONS', 'ASSIST', 'DELEGATION', 'RESEARCH', 'GATEWAY', 'GITHUB', 'REASONING', 'ENGINEERING'},
    'changed_policy_fields': POLICY_FIELD_NAMES,
    'privacy_blockers': {'MODEL_NOT_IN_ZDR_CATALOG', 'PROVIDER_ALLOWLIST', 'PROVIDER_DENYLIST', 'FALLBACK_ORDER'},
    'github_error_resources': {'PullRequest', 'Repository', 'Milestone', 'Label', 'Reference', 'Search', 'Release', 'Issue', 'Review', 'Commit'},
    'github_error_codes': {'missing_field', 'invalid', 'unprocessable', 'custom', 'already_exists', 'missing'},
    'receipt_exclusion_reasons': {'WRONG_SCOPE', 'UNKNOWN_OUTCOME', 'WRONG_ACTION', 'PRECHECK_ONLY', 'UNVERIFIED', 'STALE', 'WRONG_REVISION', 'WRONG_OPERATION', 'MISSING_IDENTITY', 'NOT_SUCCESSFUL'},
    'validation_field_names': FIELD_NAMES,
    'argument_field_names': FIELD_NAMES,
    'resolved_argument_field_names': FIELD_NAMES,
    'argument_field_types': TYPES,
    'resolved_argument_field_types': TYPES,
    'github_error_fields': FIELD_NAMES,
}


def fields_for(area):
    return COMMON | {'TOOL': TOOL_FIELDS | REPEAT_HISTORY_FIELDS | INVENTORY_PAGINATION_FIELDS | PR_CONTEXT_RACE_FIELDS, 'INCIDENT': COMBINED_FIELDS | HANDOFF_RECONCILIATION_FIELDS | CHAT_DELIVERY_FIELDS | TOOL_EVENT_PERSISTENCE_FIELDS | REVIEW_COMPARISON_OBSERVATION_FIELDS, 'APP': {'crash_surface', 'crash_boundary'}, 'ACCEPTANCE': ACCEPTANCE_FIELDS | ACCEPTANCE_BLOCKER_FIELDS | CLAIM_FRESHNESS_FIELDS | ACCEPTANCE_PUBLICATION_FIELDS | ACCEPTANCE_OBSERVATION_FIELDS | POLICY_RESPONSE_FIELDS, 'GATEWAY': GATEWAY_FIELDS, 'PROVIDER': PROVIDER_FIELDS, 'VOICE': VOICE_FIELDS}.get(area, set())


def valid_value(key, value):
    if key == 'search_validation_field_names':
        return isinstance(value, list) and 1 <= len(value) <= 4 and all(
            isinstance(item, str) and item in ENUM_LISTS[key] for item in value
        ) and len(set(value)) == len(value)
    if key == 'semantic_ci_rules':
        return isinstance(value, list) and 1 <= len(value) <= 9 and all(
            isinstance(item, str) and item in ENUM_LISTS[key] for item in value
        ) and len(set(value)) == len(value)
    if key in REPEAT_HISTORY_LIST_FIELDS:
        if not isinstance(value, list) or len(value) > 8 or not all(
            isinstance(item, str) and (bool(re.fullmatch(r'[a-f0-9]{64}', item)) if key in REPEAT_HISTORY_HASH_FIELDS else item in REPEAT_HISTORY_OUTCOMES) for item in value
        ):
            return False
        return key not in REPEAT_HISTORY_HASH_FIELDS or len(value) == len(set(value))
    if key in POLICY_RESPONSE_HEADER_FIELDS:
        # Preserve measured empty lists and ordered subsets, never a filtered
        # replacement for malformed/noncanonical approved observations.
        return isinstance(value, list) and len(value) <= 4 and all(
            isinstance(item, str) and item in ENUM_LISTS[key] for item in value
        ) and value == [item for item in POLICY_RESPONSE_HEADER_ORDER if item in value]
    if key == 'acceptance_claim_trust_failures':
        return isinstance(value, list) and 1 <= len(value) <= 3 and all(
            isinstance(item, str) and item in ENUM_LISTS[key] for item in value
        ) and len(set(value)) == len(value)
    if key in CLAIM_FRESHNESS_FIELDS:
        return isinstance(value, list) and len(value) <= 4 and all(
            isinstance(item, str) and item in ENUM_LISTS[key] for item in value
        ) and len(set(value)) == len(value)
    if key in HASH_LISTS or key in ENUM_LISTS:
        return isinstance(value, list) and 1 <= len(value) <= (4 if key == 'privacy_blockers' else 8) and all(
            isinstance(item, str) and (bool(re.fullmatch(r'[a-f0-9]{64}', item)) if key in HASH_LISTS else item in ENUM_LISTS[key]) for item in value
        )
    if key in HASHES:
        return isinstance(value, str) and bool(re.fullmatch(r'[a-f0-9]{64}', value))
    if key in {'causal_failure_code', 'voice_chat_failure_code'}:
        from .issue_reports import ERROR_CODES
        return isinstance(value, str) and value in ERROR_CODES
    if key in ENUMS:
        return isinstance(value, str) and value in ENUMS[key]
    if key in FLAGS:
        return type(value) is bool
    if type(value) is not int:
        return False
    if key in {'retry_delay_ms', 'privacy_metadata_age_ms', 'mcp_health_age_ms'}:
        return 0 <= value <= 86_400_000
    if key == 'tool_contract_version':
        return 1 <= value <= 1000
    if key == 'argument_repair_attempt':
        return 1 <= value <= 3
    if key == 'claim_extraction_response_characters':
        return 0 <= value <= 32_768
    if key == 'claim_extraction_max_claims':
        return 1 <= value <= 8
    if key in HANDOFF_RECONCILIATION_COUNTS:
        return 0 <= value <= 1024
    if key in ACCEPTANCE_PUBLICATION_TOKENS:
        return 0 <= value <= 2_147_483_647
    if key in TOOL_EVENT_SIGNED_SEQUENCES:
        return -2_147_483_648 <= value <= 2_147_483_647
    if key == 'tool_event_assigned_sequence':
        return 0 <= value <= 2_147_483_647
    return key in COUNTS and 0 <= value <= 16_000_000


def aligned(values):
    if not repeat_history_aligned(values):
        return False
    for used, limit in (('tool_result_bytes_used', 'tool_result_bytes_limit'), ('tool_budget_calls_used', 'tool_budget_calls_limit')):
        if used in values and limit in values and values[used] > values[limit]:
            return False
    for prefix in ('argument', 'resolved_argument'):
        names, types = values.get(prefix + '_field_names'), values.get(prefix + '_field_types')
        if (names is None) != (types is None) or names is not None and len(names) != len(types):
            return False
    return policy_aligned(values)


def repeat_history_aligned(values, current_call_id_hash=None):
    if not REPEAT_HISTORY_FIELDS & values.keys():
        return True
    if not (REPEAT_HISTORY_FIELDS | TOOL_REPEAT_FIELDS) <= values.keys():
        return False
    if not isinstance(values['dependency_repeat_scope'], str) or values['dependency_repeat_scope'] not in {'TOOL_NAME', 'TOOL_NAME_AND_ARGUMENTS'}:
        return False
    if any(type(values[key]) is not int or not 0 <= values[key] <= 16_000_000 for key in TOOL_REPEAT_COUNTS):
        return False
    prior_count, batch_count, used, limit = (values[key] for key in ('dependency_repeat_prior_admissions', 'dependency_repeat_batch_admissions', 'dependency_repeat_used', 'dependency_repeat_limit'))
    if prior_count + batch_count != used or used < limit or any(not valid_value(key, values[key]) for key in REPEAT_HISTORY_LIST_FIELDS):
        return False
    prior, batch, outcomes = (values[key] for key in ('dependency_repeat_prior_call_id_hashes', 'dependency_repeat_batch_call_id_hashes', 'dependency_repeat_prior_outcomes'))
    if (len(prior) != len(outcomes) or len(set(prior + batch)) != len(prior) + len(batch) or
            any(current in prior + batch for current in (values.get('call_id_hash'), current_call_id_hash) if current is not None)):
        return False
    def covered(key, retained, count):
        coverage = values[key]
        return (coverage == 'COMPLETE' and retained == count or
                coverage == 'PARTIAL' and 0 < retained < count or
                coverage == 'UNAVAILABLE' and count > 0 and retained == 0)
    return (covered('dependency_repeat_prior_coverage', len(prior), prior_count) and
            covered('dependency_repeat_batch_coverage', len(batch), batch_count))


def policy_aligned(values):
    if not POLICY_DIAGNOSTICS & values.keys():
        return True
    status = values.get('changed_policy_observation')
    total = values.get('changed_policy_field_count')
    if status == 'MALFORMED':
        return (total is None or total > 256) and not (POLICY_DIAGNOSTICS - {'changed_policy_observation', 'changed_policy_field_count'}) & values.keys()
    if status not in {'OBSERVED', 'PARTIAL'} or type(total) is not int or not 0 <= total <= 256:
        return False
    counts = [values.get(key) for key in ('changed_policy_unknown_count', 'changed_policy_malformed_count', 'changed_policy_omitted_count')]
    if any(type(count) is not int for count in counts):
        return False
    names = values.get('changed_policy_fields', [])
    if len(set(names)) != len(names) or total < len(names) + sum(counts):
        return False
    unknown, malformed, omitted = counts
    if not names and total > unknown + malformed or omitted > 0 and len(names) != 8 or len(names) + omitted > len(POLICY_FIELD_NAMES):
        return False
    return sum(counts) == 0 if status == 'OBSERVED' else sum(counts) > 0
