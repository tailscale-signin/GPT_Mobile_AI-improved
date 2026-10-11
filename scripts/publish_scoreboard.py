#!/usr/bin/env python3
"""Trusted Actions entry point. Issue content is data, never a command or file path."""
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.request

from scoreboard import best, parse_issue, require, validate

REPOSITORY = "tailscale-signin/GPT_Mobile_AI-improved"
BRANCH = "scoreboard-data"
MAX_INDEX_BYTES = 8_000_000


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class GitHub:
    def __init__(self, token):
        self.token = token
        self.opener = urllib.request.build_opener(NoRedirect)

    def api(self, method, path, data=None, missing=False, raw=False):
        require(path.startswith("/") and ".." not in path, "Invalid API path.")
        body = None if data is None else json.dumps(data, allow_nan=False, separators=(",", ":")).encode()
        request = urllib.request.Request(f"https://api.github.com/repos/{REPOSITORY}{path}", data=body, method=method, headers={
            "Authorization": f"Bearer {self.token}", "Accept": "application/vnd.github.raw+json" if raw else "application/vnd.github+json",
            "X-GitHub-Api-Version": "2026-03-10", "Content-Type": "application/json"
        })
        try:
            with self.opener.open(request, timeout=30) as response:
                payload = response.read(MAX_INDEX_BYTES + 1)
                require(len(payload) <= MAX_INDEX_BYTES, "Repository data exceeds size limit.")
                return json.loads(payload) if payload else None
        except urllib.error.HTTPError as error:
            if error.code == 404 and missing:
                return None
            raise


def initialize(github):
    """Publish a real empty feed before the first submission, without replacing data."""
    for attempt in range(5):
        ref = github.api("GET", f"/git/ref/heads/{BRANCH}", missing=True)
        parent = ref["object"]["sha"] if ref else None
        tree_data = {"tree": []}
        if parent:
            index = github.api("GET", f"/contents/scoreboards/index.json?ref={parent}", missing=True, raw=True)
            # A missing index on an existing data branch needs investigation/rebuild
            # from archives. Never silently reset it to an empty board.
            require(index is not None, "Existing scoreboard branch is missing its index; restore it from history before publishing.")
            require(index.get("schemaVersion") == 1 and isinstance(index.get("entries"), list), "Invalid scoreboard index.")
            best(index["entries"])
            return
        index = dict(schemaVersion=1, updatedAt=int(time.time() * 1000), entries=[])
        tree_data["tree"].append({"path": "scoreboards/index.json", "mode": "100644", "type": "blob", "content": json.dumps(index) + "\n"})
        tree = github.api("POST", "/git/trees", tree_data)
        commit = github.api("POST", "/git/commits", {"message": "Initialize empty community scoreboard", "tree": tree["sha"], "parents": []})
        try:
            github.api("POST", "/git/refs", {"ref": f"refs/heads/{BRANCH}", "sha": commit["sha"]})
            return
        except urllib.error.HTTPError as error:
            if error.code not in (409, 422) or attempt == 4:
                raise


def publish(github, submission, issue):
    require(type(issue) is int and 0 < issue <= 100_000_000, "Invalid issue number.")
    submission = validate(submission, submitted=True)
    archive_path = f"scoreboards/submissions/{issue}.json"
    submission = dict(submission, issueNumber=issue)
    for attempt in range(5):
        ref = github.api("GET", f"/git/ref/heads/{BRANCH}", missing=True)
        parent = ref["object"]["sha"] if ref else None
        base_tree = None
        index = {"schemaVersion": 1, "entries": []}
        if parent:
            # Immutable SHA reads keep the index and CAS parent from the same snapshot.
            existing = github.api("GET", f"/contents/{archive_path}?ref={parent}", missing=True, raw=True)
            if existing is not None:
                return  # Accepted submissions are immutable, including after issue edits.
            index = github.api("GET", f"/contents/scoreboards/index.json?ref={parent}", raw=True)
            base_tree = github.api("GET", f"/git/commits/{parent}")["tree"]["sha"]
        entries = best(index["entries"] + [submission])
        require(len(entries) <= 1000, "Community index is at its model limit; a maintainer must expand it.")
        next_index = dict(schemaVersion=1, updatedAt=int(time.time() * 1000), entries=entries)
        encoded_index = json.dumps(next_index, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
        require(len(encoded_index.encode()) + 1 <= MAX_INDEX_BYTES, "Community index is at its size limit; a maintainer must expand it.")
        tree_data = {"tree": [
            {"path": "scoreboards/index.json", "mode": "100644", "type": "blob", "content": encoded_index + "\n"},
            {"path": archive_path, "mode": "100644", "type": "blob", "content": json.dumps(submission, ensure_ascii=False, allow_nan=False, separators=(",", ":")) + "\n"}
        ]}
        if base_tree:
            tree_data["base_tree"] = base_tree
        tree = github.api("POST", "/git/trees", tree_data)
        commit = github.api("POST", "/git/commits", {"message": f"Store validated scoreboard submission #{issue}", "tree": tree["sha"], "parents": [parent] if parent else []})
        try:
            if parent:
                github.api("PATCH", f"/git/refs/heads/{BRANCH}", {"sha": commit["sha"], "force": False})
            else:
                github.api("POST", "/git/refs", {"ref": f"refs/heads/{BRANCH}", "sha": commit["sha"]})
            return
        except urllib.error.HTTPError as error:
            if error.code not in (409, 422) or attempt == 4:
                raise
    raise RuntimeError("Could not update shared results; retry the workflow.")


def process_issue(github, issue):
    if "pull_request" in issue or not str(issue.get("title", "")).startswith("[Scoreboard v1] "):
        return
    number = issue.get("number")
    require(type(number) is int and number > 0, "Invalid issue number.")
    try:
        submission = parse_issue(issue.get("body"))
    except ValueError as error:
        github.api("POST", f"/issues/{number}/comments", {"body": f"Scoreboard validation failed: {error} Edit the submission with a complete supported benchmark to retry. No new data was published."})
        return
    publish(github, submission, number)
    github.api("POST", f"/issues/{number}/comments", {"body": "Accepted as a self-reported benchmark. The validated test data is stored on the `scoreboard-data` branch. The online index keeps the highest complete score per provider/model and test plan; a lower retest does not replace the best result."})
    github.api("PATCH", f"/issues/{number}", {"state": "closed", "state_reason": "completed"})


def recover_pending(github):
    # Collect first: closing issues while paginating an open-only list skips rows.
    pending = []
    for page in range(1, 11):
        issues = github.api("GET", f"/issues?state=open&sort=created&direction=asc&per_page=100&page={page}")
        pending.extend(issue for issue in issues if "pull_request" not in issue and str(issue.get("title", "")).startswith("[Scoreboard v1] "))
        if len(issues) < 100:
            break
    else:
        raise ValueError("Too many open issues to recover safely; use the workflow's issue_number input for individual submissions.")
    for issue in pending:
        process_issue(github, issue)


def main():
    require(os.environ.get("GITHUB_REPOSITORY") == REPOSITORY, "Wrong scoreboard repository.")
    event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text())
    github = GitHub(os.environ["GITHUB_TOKEN"])
    if "issue" in event:
        process_issue(github, event["issue"])
    else:
        initialize(github)
        number = str(event.get("inputs", {}).get("issue_number", "")).strip()
        if number:
            require(number.isascii() and number.isdigit() and 0 < int(number) <= 100_000_000, "Invalid issue number.")
            process_issue(github, github.api("GET", f"/issues/{int(number)}"))
        else:
            recover_pending(github)


if __name__ == "__main__":
    main()
