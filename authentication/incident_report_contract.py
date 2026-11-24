"""Closed context-v7 incident capsule fields, mirrored by IssueReportIncidentCapsule.kt.

No free text, raw identifiers or argument values are accepted. Observations never grant
replay authority. Earlier approved context versions remain unchanged.
"""

from __future__ import annotations

import re
from . import repository_operation_report_contract as repository_operations


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
    'includeLinks', 'includeDomains', 'excludeDomains', 'extractDocument',
    'documentPageStart', 'documentPageLimit', 'documentTextOffset', 'expectedDocumentSha256',
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
    'profileBehavior.delegationEnabled', 'profileBehavior.delegationCloudPermission', 'profileBehavior.crawlersEnabled', 'profileBehavior.crawlerToolIds', 'profileBehavior.maxCrawlPages', 'profileBehavior.toolPluginStates', 'profileBehavior.researchDepthOverride', 'profileBehavior.researchEnabledOverride', 'profileBehavior.academicSourcesAllowed',
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
    'githubProfileOverride.contextVariant',
    'githubProfileOverride.engineeringMemory',
    'githubProfileOverride.allowCloudEngineeringMemory',
    'githubProfileOverride.workflowPreset',
    'githubProfileOverride.quickWorkflows',
    'githubProfileOverride.engineeringGatewayProfileUid',
    'githubProfileOverride.executionBatchMode',
    'githubProfileOverride.sampling',
    'githubProfileOverride.intelligenceLevel',
    'githubProfileOverride.nextActionPolicy',
    'githubProfileOverride.evidenceElaboration',
    'githubProfileOverride.recoveryConsultConsent',
    'githubProfileOverride.engineeringTeamConsent',
    'githubProfileOverride.recoveryExecutionConsent',
    'githubProfileOverride.primaryPatchReviewConsent',

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
LAST_UNSENT_CODE = 'dependency_repeat_last_unsent_code'
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
    'search_argument_field', 'search_argument_rule', 'search_argument_expected', 'search_native_action', 'search_mode', 'search_query_hash',
    'round_catalog_tool_count',
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
    'github_validation_endpoint',
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
ACCEPTANCE_PUBLICATION_COUNTS = {'acceptance_extraction_planned_batches', 'acceptance_receipt_snapshot_count', 'acceptance_claim_count', 'acceptance_advertised_tool_count'}
ACCEPTANCE_PUBLICATION_TOKENS = {'acceptance_extraction_required_tokens', 'acceptance_extraction_input_limit_tokens'}
ACCEPTANCE_PUBLICATION_FIELDS = ACCEPTANCE_PUBLICATION_COUNTS | ACCEPTANCE_PUBLICATION_TOKENS | {'acceptance_publication_source', 'acceptance_extraction_observation', 'acceptance_draft_state'}
ACCEPTANCE_OBSERVATION_FIELDS = {'acceptance_claim_trust_failures', 'acceptance_publication_entry'}
ACCEPTANCE_EXPLANATION_FIELDS = {'acceptance_applicability', 'acceptance_contract_state', 'acceptance_plan_state', 'acceptance_extraction_state', 'acceptance_coverage_state', 'acceptance_empty_reason', 'acceptance_original_failure_present'}
REVIEW_COMPARISON_OBSERVATION_FIELDS = {'review_comparison_state', 'review_comparison_policy'}
TOOL_EVENT_SIGNED_SEQUENCES = {'tool_event_requested_sequence', 'tool_event_previous_max_sequence'}
TOOL_EVENT_PERSISTENCE_FIELDS = TOOL_EVENT_SIGNED_SEQUENCES | {'tool_event_persistence_operation', 'tool_event_persistence_phase', 'tool_event_persistence_failure', 'tool_event_assigned_sequence', 'tool_event_write_attempted'}
TLS_TRANSPORT_PHASE_FIELDS = {'tls_last_observed_phase', 'tls_request_body_completed', 'tls_connection_reused', 'tls_version', 'tls_http_protocol'}
PROVIDER_FIELDS = TLS_TRANSPORT_PHASE_FIELDS | {'claim_extraction_validation_stage', 'claim_extraction_schema_hash', 'provider_route_hash', 'provider_wait_checkpoint_hash', 'provider_wait_claim_hash', 'provider_wait_terminal_status', 'tls_failure_kind', 'tls_response_started', 'provider_local_budget_block', 'provider_tool_rejection', 'tool_protocol_frame_kind', 'tool_protocol_recovery_outcome', 'claim_extraction_failure_kind', 'claim_extraction_response_characters', 'claim_extraction_max_claims', 'claim_extraction_completed', 'reasoning_probe_observation', 'gateway_upstream_status', 'gateway_dependency', 'gateway_terminal_status', 'gateway_result_quality', 'provider_rejection_category', 'provider_error_type_hash', 'provider_parameter', 'provider_parameter_path_hash', 'payload_contract', 'privacy_catalog_entries', 'privacy_model_endpoint_count', 'privacy_compatible_endpoint_count', 'privacy_allowlist_excluded_count', 'privacy_denylist_excluded_count', 'privacy_fallback_excluded_count', 'privacy_metadata_age_ms', 'privacy_blockers'}
VOICE_PLAYBACK_FAILURES = {
    'AUDIO_FOCUS_DENIED', 'AUDIO_FOCUS_LOST', 'AUDIO_FOCUS_TIMEOUT', 'AUDIO_FOCUS_RESUME_FAILED',
    'TIMEOUT', 'MEMORY_PRESSURE', 'TEXT_QUEUE_FULL', 'SYNTHESIS_FAILED', 'ENGINE_FAILED', 'AUDIO_FAILED',
    'PRONUNCIATION_UNAVAILABLE', 'LEXICON_UNAVAILABLE',
}
VOICE_EXECUTION_ENUMS = {
    'voice_selected_backend': {'AUTO', 'CPU', 'QNN_GPU', 'QNN_HTP', 'GATEWAY_CUDA', 'LITERT_KOKORO_CPU', 'LITERT_KOKORO_GPU', 'PHONON_CPU', 'POCKET_CPU'},
    'voice_effective_provider': {'SYSTEM', 'KOKORO_CPU', 'QNN_GPU', 'QNN_HTP', 'GATEWAY_CUDA'},
    'voice_fallback_source': {'KOKORO', 'KOKORO_CPU', 'QNN_GPU', 'QNN_HTP', 'GATEWAY_CUDA', 'LITERT_KOKORO', 'PHONON', 'POCKET'},
    'voice_fallback_target': {'SYSTEM', 'KOKORO_CPU'},
    'voice_fallback_outcome': {'STARTED', 'SUCCEEDED', 'FAILED'},
    'voice_fallback_failure': VOICE_PLAYBACK_FAILURES,
}
VOICE_EXECUTION_FIELDS = set(VOICE_EXECUTION_ENUMS) | {'voice_fallback_count'}
VOICE_FIELDS = VOICE_EXECUTION_FIELDS | {'voice_pronunciation_unknown_word_length', 'run_id_hash', 'invocation_id_hash', 'voice_reply_failed', 'voice_reply_terminal_status', 'voice_reply_failure_evidence', 'voice_chat_failure_code', 'voice_chat_failure_code_hash', 'voice_chat_failure_phase', 'voice_focus_last_transition', 'voice_focus_transition_count', 'voice_pronunciation_unknown_words', 'voice_pronunciation_initialisms', 'voice_pronunciation_failure_kind', 'voice_pronunciation_audio_queued', 'voice_pronunciation_first_audio_observed'}

GATEWAY_FIELDS = {
    'command_id_hash',
    'command_receipt_observation',
    'gateway_package_digest_hash',
    'gateway_source_commit_hash',
}

HASHES = {
    'search_query_hash',
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
    'tls_response_started', 'tls_request_body_completed', 'tls_connection_reused', 'provider_local_budget_block',
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
    'round_catalog_tool_count', 'tool_result_bytes_used', 'tool_result_bytes_limit', 'tool_budget_calls_used', 'tool_budget_calls_limit', 'combined_issue_count', 'combined_failed_check_count', 'combined_unverified_check_count', 'combined_repair_rounds',
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
    'application_persistence',
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
    'claim_extraction_validation_stage': {'CLAIM_LIST', 'CLAIM_COUNT', 'CLAIM_RECORD', 'REQUIREMENT_MAP', 'QUESTIONS'},
    'github_prerequisite_observation': {'CATALOG_TOOL_NOT_EXPOSED', 'CATALOG_INITIAL_REQUEST_UNAVAILABLE', 'STATUS_TOOL_NOT_EXPOSED', 'STATUS_DISCOVERY_UNAVAILABLE', 'STATUS_CONNECTION_AMBIGUOUS', 'STATUS_ACQUISITION_UNAVAILABLE', 'STATUS_ARGUMENTS_UNAVAILABLE', 'STATUS_READ_FAILED', 'STATUS_RECEIPT_UNUSABLE'},
    'claim_extraction_failure_kind': {'INVALID_JSON', 'NON_OBJECT', 'SCHEMA_REJECTED'},
    'inventory_pagination_reason': {'MISSING_VERSION', 'VERSION_MISMATCH'},
    'sqlite_constraint_kind': {'UNIQUE', 'FOREIGN_KEY', 'NOT_NULL', 'CHECK', 'PRIMARY_KEY', 'TRIGGER', 'UNKNOWN'},
    'dependency_repeat_scope': {'TOOL_NAME', 'TOOL_NAME_AND_ARGUMENTS'},
    'tool_event_persistence_operation': {'START'},
    'tool_event_persistence_phase': {'ALLOCATE_SEQUENCE', 'INSERT'},
    'tool_event_persistence_failure': {'RUN_SEQUENCE_CONFLICT', 'EVENT_ID_CONFLICT', 'RUN_FOREIGN_KEY', 'NOT_NULL', 'UNKNOWN_CONSTRAINT', 'SEQUENCE_EXHAUSTED'},
    'app_search_readiness': {'CHAT_TOOLS_DISABLED', 'PROFILE_TOOLS_DISABLED', 'PROFILE_REMOTE_DISABLED', 'NO_ENGINE_RESOLVED', 'ROUTED_ENGINE_MISSING', 'BOUND_ENGINE_MISSING', 'AGGREGATE_NOT_CREATED'},
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
    LAST_UNSENT_CODE: {'INVALID_ARGUMENTS', 'NOT_DISPATCHED', 'EVIDENCE_UNAVAILABLE', 'SAVED_VIEW_UNAVAILABLE', 'GITHUB_REF_UNAVAILABLE', 'GITHUB_REPOSITORY_UNAVAILABLE', 'TOOL_UNAVAILABLE', 'UNKNOWN_TOOL', 'GITHUB_VALIDATION_FAILED', 'GITHUB_NOT_FOUND_OR_INACCESSIBLE', 'TOOL_PRECHECK_FAILED', 'PRECHECK_REJECTED', 'OTHER'},
    'dependency_repeat_prior_coverage': {'COMPLETE', 'PARTIAL', 'UNAVAILABLE'},
    'dependency_repeat_batch_coverage': {'COMPLETE', 'PARTIAL', 'UNAVAILABLE'},
    'status_policy_protection_404_kind': {'NOT_FOUND', 'OTHER_404', 'EXPLICIT_BRANCH_NOT_PROTECTED'},
    'status_policy_rules_404_kind': {'NOT_FOUND', 'OTHER_404'},
    'provider_wait_terminal_status': {'COMPLETED', 'PARTIAL', 'FAILED', 'BLOCKED', 'INTERRUPTED', 'CANCELED', 'EVIDENCE_MISSING', 'WAITING_FOR_APPROVAL'},
    'changed_policy_observation': {'OBSERVED', 'PARTIAL', 'MALFORMED'},
    'incident_capture_site': {'CHAT_REPOSITORY_TERMINAL_CATCH', 'CHAT_REPOSITORY_PREFLIGHT', 'OPENAI_ADAPTER_STREAM_CATCH', 'PROVIDER_ADAPTER_STREAM_CATCH', 'CHAT_REPOSITORY_ERROR_VALUE', 'CHAT_FLOW_EMPTY_ANSWER', 'AGENT_FAILED_EVENT', 'AGENT_ROUND_CATCH'},
    'incident_phase_provenance': {'CAPTURE_BOUNDARY_ONLY'},
    'native_dependency_state': {'DEFERRED', 'FAILED'},
    'tool_execution_observation': {'REMOTE_READ_SUCCEEDED'},
    'tool_budget_kind': {'CALLS', 'RESULT_BYTES', 'BOTH', 'COMBINED_WORK'},
    'tool_budget_phase': {'BEFORE_DISPATCH', 'AFTER_DISPATCH'},
    'tool_permission_scope': {'ANDROID_LOCATION', 'GITHUB_LOCAL_GRANT'},
    'tls_failure_kind': {'CERTIFICATE', 'PROTOCOL', 'HANDSHAKE', 'OTHER'},
    'tls_last_observed_phase': {'NOT_OBSERVED', 'CALL_STARTED', 'CONNECTING', 'TLS_HANDSHAKE', 'CONNECTION_ACQUIRED', 'REQUEST_HEADERS', 'REQUEST_BODY', 'REQUEST_BODY_COMPLETE', 'RESPONSE_HEADERS'},
    'tls_version': {'TLSv1', 'TLSv1.1', 'TLSv1.2', 'TLSv1.3', 'SSLv3'},
    'tls_http_protocol': {'http/1.0', 'http/1.1', 'h2', 'h2_prior_knowledge', 'quic', 'h3'},
    'remote_pagination_reason': {'INCOMPLETE', 'STALLED'},
    'provider_tool_rejection': {'TOOL_USE_FAILED'},
    'tool_protocol_frame_kind': {'MISSING_FUNCTION_NAME', 'UNFINISHED_STREAM', 'NON_OBJECT_ARGUMENTS', 'MALFORMED_JSON'},
    'tool_protocol_recovery_outcome': {'TEXT_FALLBACK_EXHAUSTED', 'TOOL_RETRY_EXHAUSTED', 'BACKEND_HANDLED_TOOLS', 'SINGLE_PASS_NOT_RETRYABLE', 'TEXT_ALREADY_EMITTED', 'NO_SAFE_REPAIR'},
    'reasoning_probe_observation': {'GATEWAY_OBSERVATION_AVAILABLE', 'DEADLINE_WITHOUT_GATEWAY_OBSERVATION', 'NO_GATEWAY_OBSERVATION'},
    'gateway_terminal_status': {'completed', 'failed', 'cancelled', 'canceled', 'interrupted'},
    'gateway_result_quality': {'complete', 'partial'},
    'combined_repair_stop': {'SINGLE_PASS_LOCAL_CHECKS_ONLY', 'CORRECTION_CAPACITY_UNAVAILABLE', 'LEGACY_REVISION_REQUIRES_NEW_OWNER', 'LEGACY_REPAIR_ALLOWANCE_REACHED', 'NO_PROGRESS_AFTER_REQUIRED_REVISIONS', 'NO_PROGRESS_AFTER_REQUIRED_REVISION', 'NEWER_REVISION_OR_UNAVAILABLE_REPLACEMENT_OWNER', 'REPAIR_REJECTED', 'NO_REMAINING_REQUIRED_CORRECTION', 'REPAIR_PREPARATION_FAILED', 'REPLACEMENT_MADE_NO_TEXT_PROGRESS', 'REPLACEMENT_OWNER_COMPARE_AND_SET_REJECTED'},
    'combined_requirement_gap': {'CURRENT_ARTICLE', 'LENGTH', 'TOPIC', 'OTHER'},
    'search_cursor_state': {'RETAINED_SEARCH_MISSING', 'PAGE_BOUNDARY_UNOBSERVED'},
    'mcp_failure_phase': {'configuration', 'catalog', 'discovery', 'initialization', 'execution', 'connection', 'dns', 'tls', 'oauth_refresh', 'local_validation'},
    'mcp_failure_category': (MCP_HEALTH - {'HEALTHY'}) | {'MCP_TOOL_ERROR', 'MCP_OUTPUT_INVALID', 'POLICY_BLOCKED'},
    'mcp_connection_health': MCP_HEALTH,
    'mcp_connection_state': {'CONNECTED', 'DEGRADED', 'UNREACHABLE', 'AUTHENTICATION_REQUIRED', 'RECOVERING'},
    'mcp_readiness_state': {'NOT_DISCOVERED', 'DISCOVERING', 'READY', 'STALE'},
    'mcp_health_observation': {'RETAINED_SNAPSHOT', 'UNAVAILABLE'},
    'saved_view_restore_state': {'NOT_FOUND', 'LOAD_FAILED', 'IDENTITY_REJECTED', 'RESTORED', 'BODY_MISSING', 'BODY_MISSING_INDEX_PRESENT', 'BODY_UNREADABLE', 'CURSOR_INDEX_MISSING', 'CURSOR_INDEX_INVALID', 'INVALID_VIEW_ID'},
    'provider_rejection_category': {'BILLING_UNAVAILABLE', 'INVALID_REQUEST', 'AUTHENTICATION', 'PERMISSION', 'RATE_LIMIT', 'NOT_FOUND', 'GONE', 'UNAVAILABLE', 'PAYLOAD_SIZE', 'MODEL_UNAVAILABLE', 'UNKNOWN'},
    'provider_parameter': {'model', 'messages', 'max_tokens', 'max_completion_tokens', 'temperature', 'top_p', 'stop', 'tools', 'tool_choice', 'stream', 'response_format', 'reasoning', 'thinking', 'metadata', 'system', 'input', 'max_output_tokens', 'previous_response_id', 'instructions'},
    'payload_contract': {'CHAT_COMPLETIONS', 'OPENAI_RESPONSES', 'ANTHROPIC_MESSAGES', 'GOOGLE_CONTENT', 'GATEWAY_GENERATION'},
    'voice_reply_terminal_status': {'COMPLETED', 'FAILED', 'CANCELED', 'INTERRUPTED', 'PARTIAL', 'EVIDENCE_MISSING', 'BLOCKED', 'WAITING_FOR_APPROVAL'},
    'voice_reply_failure_evidence': {'TYPED_INVOCATION_REFERENCE', 'CLASSIFICATION_ONLY', 'MISSING_FAILURE_RECORD'},
    'voice_chat_failure_phase': PHASES - {'unknown'},
    'voice_pronunciation_failure_kind': {'UNKNOWN_WORD', 'UNSUPPORTED_SCRIPT', 'MISSING_LETTER'},
    'voice_focus_last_transition': {'RESTORE', 'PAUSE', 'DUCK', 'STOP'},
    'receipt_count_scope': {'LINKED_CRITERION_EVIDENCE'},
    'acceptance_publication_source': {'STATUS', 'COVERAGE', 'CI_OBSERVATION', 'CLAIM_EXTRACTION', 'PARTIAL_FALLBACK', 'EVIDENCE_ADVANCED'},
    'acceptance_extraction_observation': {'NOT_NEEDED', 'NO_DRAFT', 'PLAN_BATCH_LIMIT', 'PLAN_INPUT_LIMIT', 'PLAN_LIMIT_CHANGED', 'COMPLETED', 'FAILED'},
    'acceptance_draft_state': {'ABSENT', 'EMPTY', 'PRESENT'},
    'acceptance_applicability': {'REQUIRED', 'NOT_APPLICABLE', 'UNDETERMINED'},
    'acceptance_contract_state': {'RETAINED', 'NOT_RECORDED'},
    'acceptance_plan_state': {'DEFINED', 'EMPTY', 'NOT_RECORDED'},
    'acceptance_extraction_state': {'NOT_RECORDED', 'NOT_NEEDED', 'NO_DRAFT', 'PLAN_BATCH_LIMIT', 'PLAN_INPUT_LIMIT', 'PLAN_LIMIT_CHANGED', 'COMPLETED', 'FAILED'},
    'acceptance_coverage_state': {'RECEIPTS_RETAINED', 'NO_RECEIPTS', 'NOT_RECORDED'},
    'acceptance_empty_reason': {'CRITERIA_PRESENT', 'PRIMARY_FAILURE', 'EXTRACTION_FAILED', 'EXTRACTION_NOT_RUN', 'NOT_APPLICABLE', 'MISSING_CONTRACT', 'MISSING_PLAN', 'NO_REQUIRED_CRITERIA'},
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
    'github_validation_endpoint': {'COMMIT_LOOKUP', 'TREE_READ', 'REVISION_COMPARE'},
    'native_tool_action': ACTIONS,
    'search_argument_field': {'query', 'mode', 'file_extension', 'path_prefix', 'batch_size', 'max_new_blobs', 'path_suffix', 'case_sensitive', 'fuzzy_match', 'fuzzy_threshold', 'min_lines', 'max_lines', 'context_lines'},
    'search_argument_rule': {'TYPE', 'QUERY_LENGTH', 'MODE', 'QUERY_QUALIFIER', 'EXTENSION', 'RANGE', 'LINE_ORDER', 'PATTERN_SYNTAX', 'PATTERN_FUZZY', 'PATTERN_BOUNDS', 'FUZZY_IDENTIFIER'},
    'search_argument_expected': {'STRING', 'BOOLEAN', 'INTEGER', 'NUMBER', 'QUERY_1_200', 'SEARCH_MODE', 'EXPLICIT_FILTERS', 'FILE_EXTENSION', 'NUMBER_0_5_1', 'INTEGER_0_1000000', 'INTEGER_1_1000000', 'MAX_AT_LEAST_MIN', 'INTEGER_0_100', 'TEXT_MAX_200', 'INTEGER_1_1000', 'INTEGER_1_100', 'LITERAL_STAR_PATTERN', 'FALSE', 'BOUNDED_PATTERN', 'IDENTIFIER_2_80'},
    'search_native_action': {'search_repository'},
    'search_mode': {'literal', 'definitions', 'references', 'paths', 'tests', 'patterns', 'UNSUPPORTED'},
    'saved_view_owner_operation': OPERATIONS,
    'expected_operation': OPERATIONS,
    'expected_action': ACTIONS,
}

ENUMS.update(VOICE_EXECUTION_ENUMS)

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


CONVERSATION_RECOVERY_FEATURE = 'CONVERSATION_RECOVERY_DIAGNOSTICS_V1'
CONVERSATION_RECOVERY_CODES = {
    'GITHUB_VERIFICATION_INTERNAL_ERROR',
    'PROVIDER_WAIT_CHECKPOINT_CHANGED', 'PROVIDER_WAIT_EVIDENCE_UNAVAILABLE',
    'PROVIDER_WAIT_OUTCOME_UNKNOWN', 'PROVIDER_WAIT_TASK_CHANGED',
    'PROVIDER_WAIT_CHECKPOINT_INVALID', 'PROVIDER_WAIT_INPUT_CHANGED',
    'PROVIDER_WAIT_PREFIX_CHANGED', 'PROVIDER_WAIT_REVIEW_REQUIRED',
    'RESEARCH_RECOVERY_ACTION_CHANGED',
}
CONVERSATION_RECOVERY_COUNTS = {
    'continuation_saved_characters', 'continuation_attempt_count', 'continuation_output_cap',
}
CONVERSATION_RECOVERY_FIELDS = CONVERSATION_RECOVERY_COUNTS | {
    'verification_phase', 'continuation_cause', 'continuation_trigger',
    'checkpoint_mismatch_reason', 'observed_checkpoint_state',
    'provider_wait_observed_checkpoint_hash',
}
COMMON.update(CONVERSATION_RECOVERY_FIELDS)
COUNTS.update(CONVERSATION_RECOVERY_COUNTS | {'acceptance_advertised_tool_count'})
ACCEPTANCE_FIELDS.add('acceptance_advertised_tool_count')
HASHES.add('provider_wait_observed_checkpoint_hash')
TOOL_FIELDS.add('issue_continuation_state')
FIELD_NAMES.update({'after', 'expected_updated_at', 'names', 'inventory_cursor', 'inventory_version', 'catalog_only'})
ENUMS['incident_capture_site'].update({'GITHUB_VERIFICATION_CATCH', 'OUTPUT_CONTINUATION_OPEN_CATCH', 'REPOSITORY_GRAPH_CATCH'})
ENUMS.update({
    'verification_phase': {
        'DESCENDANTS', 'AUTHORITY', 'SNAPSHOT', 'RECEIPT_RENDERING',
        'CLAIM_PLANNING', 'CLAIM_EXTRACTION', 'ACCEPTANCE_RENDERING', 'PUBLICATION',
    },
    'continuation_cause': {'NO_SAVED_TASK', 'CAPACITY_REJECTED', 'HTTP_REJECTED', 'TRANSPORT_FAILURE', 'OPEN_FAILED'},
    'continuation_trigger': {'PROVIDER_OUTPUT_LIMIT', 'REQUESTED_LENGTH', 'STREAM_INTERRUPTED', 'RESTORED_DRAFT', 'NO_PROGRESS', 'REPETITIVE_OUTPUT'},
    'checkpoint_mismatch_reason': {
        'MISSING_CHECKPOINT', 'STATE_CHANGED', 'RESUME_DISALLOWED', 'DIGEST_CHANGED',
        'TASK_BINDING_CHANGED', 'TOOL_BINDING_CHANGED', 'EVIDENCE_DIGEST_INVALID',
    },
    'observed_checkpoint_state': {'WAITING_PROVIDER_LIMIT', 'TOOL_RESULTS_READY', 'TOOL_HANDOFF', 'BLOCKED_RECOVERY', 'COMPLETED', 'PARTIAL', 'FAILED', 'UNKNOWN'},
    'issue_continuation_state': {'MISSING_AFTER', 'MALFORMED_AFTER', 'FILTER_MISMATCH', 'PAGE_MISMATCH', 'INITIAL_PAGE_WITH_AFTER', 'AUTHENTICATED_CONNECTION_REQUIRED'},
})



# Same-failure, closed evidence for durable recovery and semantic stream failures.
EXECUTION_COMPLETION_FEATURE = 'EXECUTION_COMPLETION_EVIDENCE_V1'
EXECUTION_COMPLETION_CODES = {'WORKSPACE_EVIDENCE_CONFLICT', 'GITHUB_REVIEW_COMPARISON_REQUIRED', 'RECONCILIATION_REQUIRED', 'BILLING_UNAVAILABLE', 'PROVIDER_CONTENT_POLICY', 'PROVIDER_GENERATION_FAILED', 'PROVIDER_UNAVAILABLE', 'AUTHENTICATION_FAILED', 'ACCESS_FORBIDDEN'}
EXECUTION_COMPLETION_HASHES = {
    'provider_response_id_hash', 'no_output_parent_invocation_hash',
    'reconciliation_original_call_hash', 'reconciliation_original_tool_hash', 'reconciliation_original_arguments_hash',
    'no_output_draft_hash', 'no_output_segment_hash', 'no_output_evidence_hash',
    'evidence_record_hash', 'evidence_proposed_payload_hash', 'evidence_retained_payload_hash',
    'provider_wait_expected_tool_journal_hash', 'provider_wait_observed_tool_journal_hash',
    'provider_wait_expected_tool_evidence_hash', 'provider_wait_observed_tool_evidence_hash', 'provider_wait_tool_binding_hash',
}
EXECUTION_COMPLETION_COUNTS = {
    'no_output_visible_bytes', 'no_output_reasoning_bytes', 'no_output_tool_call_count',
    'no_output_repairs', 'no_output_repair_limit',
    'no_output_continuation_attempt', 'no_output_continuation_limit',
    'no_output_generated_tokens', 'no_output_reasoning_tokens', 'no_output_effective_limit', 'no_output_serialized_limit',
    'provider_wait_queued_tool_count', 'provider_wait_unsettled_tool_count', 'provider_wait_settled_tool_count',
}
EXECUTION_COMPLETION_UPSTREAM_CODES = {
    '401', '402', '429', 'insufficient_quota', 'insufficient_credits', 'budget_exceeded', 'credit_limit_exceeded', 'kudosupfront', 'sharedkeyempty',
    '500', '502', '503', '504', 'server_error', 'internal_error', 'internal_server_error', 'service_unavailable', 'overloaded_error', 'api_error', 'processing_error',
    'invalid_api_key', 'authentication_error', 'permission_denied', 'model_access_denied', 'route_forbidden', 'anonforbidden', 'noworkers', 'no_valid_workers',
    'context_window_exceeded', 'context_length_exceeded', 'rate_limit_exceeded', 'tokens_limit_exceeded', 'rate_limit_error', 'content_policy_violation', 'content_filter', 'safety_blocked',
}
EXECUTION_COMPLETION_ENUMS = {
    'reconciliation_identity_state': {'IDENTIFIED', 'MULTIPLE', 'NOT_RECORDED'},
    'reconciliation_original_code': {'TIMEOUT_OUTCOME_UNKNOWN', 'DISPATCH_OUTCOME_UNKNOWN', 'TOOL_OUTCOME_UNKNOWN', 'TOOL_RESULT_ID_MISMATCH', 'RECONCILIATION_REQUIRED', 'GATEWAY_OUTCOME_UNKNOWN', 'GATEWAY_TOOL_OUTCOME_UNKNOWN', 'REMOTE_OUTCOME_UNKNOWN'},
    'no_output_kind': {'REASONING_ONLY', 'EMPTY_VISIBLE_COMPLETION', 'DUPLICATE_DRAFT', 'OUTPUT_LIMIT_WITHOUT_NOVELTY'},
    'no_output_stop_reason': {'BOUNDED_EMPTY_OUTPUT_REPAIR', 'BOUNDED_VISIBLE_OUTPUT_REPAIR', 'VISIBLE_OUTPUT_REPAIR_REQUEST_ALREADY_CLAIMED', 'DUPLICATE_DRAFT', 'OUTPUT_ALLOWANCE_WITHOUT_NOVELTY', 'NO_PROGRESS_AFTER_CONTINUATION', 'EMPTY_OUTPUT_REPAIR_LIMIT'},
    'evidence_conflict_reason': {'IMMUTABLE_PAYLOAD_CHANGED', 'RETAINED_PAYLOAD_UNAVAILABLE', 'CONCURRENT_APPEND_CONFLICT'},
    'evidence_record_kind': {'github_evidence', 'github_engineering_memory', 'synthesis_checkpoint'},
    'evidence_writer_phase': {'IMMUTABLE_APPEND', 'CHECKPOINT_REPLACE', 'TERMINAL_RECONCILIATION'},
    'provider_failure_kind': {'AUTHENTICATION', 'BUDGET', 'PERMISSION', 'CONTENT_POLICY', 'FORBIDDEN_UNKNOWN', 'RATE_LIMIT', 'UNAVAILABLE', 'UNKNOWN'},
    'provider_budget_kind': {'ACCOUNT_CREDITS', 'KEY_LIMIT', 'REQUEST_AFFORDABILITY', 'IN_FLIGHT_BUDGET', 'UNKNOWN'},
    'provider_budget_limit_source': {'openrouter_credits', 'openrouter_key_limit', 'openrouter_in_flight_budget'},
    'provider_budget_reason': {'weight_exceeds_budget', 'in_flight_budget_exhausted'},
    'provider_budget_retry': {'MANUAL_AFTER_PROVIDER_DELAY', 'PROVIDER_TIMING_UNAVAILABLE', 'AFTER_CONFIGURATION_CHANGE', 'REVIEW_SAVED_WORK'},
    'upstream_error_code': EXECUTION_COMPLETION_UPSTREAM_CODES,
    'upstream_error_type': EXECUTION_COMPLETION_UPSTREAM_CODES,
    'stream_event_type': {'error', 'response.failed'},
    'checkpoint_validation_stage': {'TOOL_JOURNAL'},
    'provider_wait_decision_state': {'ELIGIBLE', 'INELIGIBLE', 'ROUTE_MISMATCH', 'DURABLE_WAIT', 'NOT_SCHEDULED', 'PERSISTENCE_UNCERTAIN'},
    'provider_wait_scheduler_state': {'ENQUEUED', 'PENDING_RECONCILIATION', 'NOT_SCHEDULED'},
    'provider_wait_no_wait_reason': {
        'DECISION_UNAVAILABLE', 'REJECTION_NOT_REPLAY_SAFE', 'RETRY_LIMIT_REACHED', 'CONNECTIVITY_RESUME_UNSAFE',
        'RESEARCH_CHECKPOINT_INVALID', 'ROUTE_MISMATCH', 'RUN_UNAVAILABLE', 'MESSAGE_UNAVAILABLE', 'OWNER_CHANGED',
        'CHECKPOINT_UNAVAILABLE', 'CHECKPOINT_CHANGED', 'REMOTE_JOB_PRESENT', 'INITIAL_OUTPUT_PRESENT',
        'GATEWAY_COMMAND_PRESENT', 'REMOTE_OUTCOME_UNKNOWN', 'RETRY_DEADLINE_UNAVAILABLE',
        'CONTINUATION_PREFIX_CHANGED', 'COMBINED_SNAPSHOT_INVALID', 'COMBINED_RESUME_UNSAFE', 'AUTHORITY_UNAVAILABLE',
        'AUTHORITY_CHANGED', 'PROFILE_UNAVAILABLE', 'INPUT_UNAVAILABLE', 'TASK_SOURCE_UNAVAILABLE', 'TASK_SOURCE_INVALID',
        'DEADLINE_EXPIRED', 'PERSISTENCE_REJECTED', 'PERSISTENCE_FAILED', 'PERSISTENCE_OUTCOME_UNKNOWN',
        'WAIT_STORE_UNAVAILABLE', 'MESSAGE_CHECKPOINT_FAILED', 'WAIT_PREPARATION_FAILED',
    } | repository_operations.PROVIDER_WAIT_NO_WAIT_REASONS,
}
RECONCILIATION_IDENTITY_FIELDS = {'reconciliation_identity_state', 'reconciliation_tracked_operation_count', 'reconciliation_original_call_hash', 'reconciliation_original_tool_hash', 'reconciliation_original_arguments_hash', 'reconciliation_original_code', 'reconciliation_original_dispatched'}
EXECUTION_COMPLETION_FIELDS = EXECUTION_COMPLETION_HASHES | EXECUTION_COMPLETION_COUNTS | set(EXECUTION_COMPLETION_ENUMS) | RECONCILIATION_IDENTITY_FIELDS | {'provider_semantic_status', 'no_output_repair_scheduled', 'provider_wait_state', 'provider_wait_persisted', 'ownership_fence'}
HASHES.update(EXECUTION_COMPLETION_HASHES)
FLAGS.add('no_output_repair_scheduled')
FLAGS.add('reconciliation_original_dispatched')
FLAGS.add('acceptance_original_failure_present')
ENUMS.update(EXECUTION_COMPLETION_ENUMS)
ENUMS['checkpoint_mismatch_reason'].update({
    'TOOL_OUTCOME_UNKNOWN', 'TOOL_RESULTS_PENDING', 'TOOL_JOURNAL_CHANGED', 'TOOL_RECEIPTS_CHANGED',
    'TOOL_REPLAY_PROJECTION_UNPROVEN', 'TOOL_RESULT_SEMANTICS_UNPROVEN', 'TOOL_RESULT_SEMANTICS_CHANGED',
    'TOOL_JOURNAL_CHANGED_DURING_RESTORE', 'RECOVERY_OWNER_UNAVAILABLE', 'CHECKPOINT_COMPARE_AND_SET_REJECTED',
})
ENUMS['observed_checkpoint_state'].update({'GENERATING', 'WRITING', 'NO_OUTPUT_REPAIR', 'OUTPUT_LIMIT', 'INTERRUPTED', 'BLOCKED_NO_PROGRESS'})


def fields_for(area):
    return COMMON | (EXECUTION_COMPLETION_FIELDS if area in {'INCIDENT', 'PROVIDER'} else set()) | {'TOOL': TOOL_FIELDS | REPEAT_HISTORY_FIELDS | {LAST_UNSENT_CODE} | INVENTORY_PAGINATION_FIELDS | PR_CONTEXT_RACE_FIELDS | RECONCILIATION_IDENTITY_FIELDS, 'INCIDENT': {'github_prerequisite_observation', 'app_search_readiness'} | COMBINED_FIELDS | HANDOFF_RECONCILIATION_FIELDS | CHAT_DELIVERY_FIELDS | TOOL_EVENT_PERSISTENCE_FIELDS | REVIEW_COMPARISON_OBSERVATION_FIELDS, 'APP': {'crash_surface', 'crash_boundary'}, 'ACCEPTANCE': {'github_prerequisite_observation'} | ACCEPTANCE_FIELDS | ACCEPTANCE_BLOCKER_FIELDS | CLAIM_FRESHNESS_FIELDS | ACCEPTANCE_PUBLICATION_FIELDS | ACCEPTANCE_OBSERVATION_FIELDS | ACCEPTANCE_EXPLANATION_FIELDS | POLICY_RESPONSE_FIELDS, 'GATEWAY': GATEWAY_FIELDS, 'PROVIDER': PROVIDER_FIELDS, 'VOICE': VOICE_FIELDS}.get(area, set())


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
    if key in EXECUTION_COMPLETION_COUNTS:
        return 0 <= value <= 2_147_483_647
    if key == 'reconciliation_tracked_operation_count':
        return 0 <= value <= 1024
    if key == 'ownership_fence':
        return 0 <= value <= 9_223_372_036_854_775_807
    if key == 'provider_semantic_status':
        return value == 429
    if key == 'voice_pronunciation_unknown_word_length':
        return 1 <= value <= 256
    if key == 'voice_fallback_count':
        return 0 <= value <= 100
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
        return LAST_UNSENT_CODE not in values
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
    if LAST_UNSENT_CODE in values and (values['dependency_repeat_prior_coverage'] != 'COMPLETE' or outcomes[-1:] != ['NOT_DISPATCHED']):
        return False
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
