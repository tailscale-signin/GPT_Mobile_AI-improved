"""Reviewed operation semantics; remote readOnly hints never waive mutation fences."""
import re
IMMUTABLE_READ='immutable_read'
MUTABLE_READ='mutable_read'
MUTATION='mutation'
_READS={'get_file_contents','get_commit','list_commits','list_branches','list_tags','get_pull_request','pull_request_read','list_pull_requests','search_pull_requests','get_issue','issue_read','list_issues','search_issues','search_code','search_repositories','get_repository','get_tree','get_blob','get_workflow','list_workflow_runs','get_workflow_run','get_workflow_run_logs','get_job_logs','list_releases','get_latest_release','get_release_by_tag','get_me','get_copilot_space','list_copilot_spaces'}
_IMMUTABLE={'get_file_contents','get_commit','get_tree','get_blob'}
def operation_class(tool,arguments):
    if tool not in _READS: return MUTATION
    if tool in _IMMUTABLE and any(re.fullmatch(r'(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})',str(arguments.get(k,''))) for k in ('sha','commit_sha','tree_sha','blob_sha','ref')): return IMMUTABLE_READ
    return MUTABLE_READ

def select_equivalent(definitions,metadata):
    """Deduplicate only explicitly declared operation AND authorization equivalence."""
    selected=[];seen=set()
    for item in definitions:
        meta=metadata.get(item['function']['name'],{})
        operation=meta.get('equivalentOperation');scope=meta.get('authorizationScope')
        key=(operation,scope) if operation and scope else None
        if key and key in seen: continue
        if key: seen.add(key)
        selected.append(item)
    return selected
