# Gateway 16: admission, collaboration and conversation ownership

This follow-up preserves Gateway 16, session diagnostics, smooth chat motion and the concurrent local-voice work on PR #816. It addresses the comparison findings against the older `0116890e7e41` revision.

## Accepted request → producible acceptance → verified result

Implementation admission now checks the selected execution binding, repository-specific validation policy, exact source commit in a bare mirror, locally available digest-pinned Docker image, approved command arguments, named CI checks and behavior-test producers. These are bounded read-only probes; they do not pull images, fetch repositories, run validation commands or spend the job's model budget. Read/plan tasks retain their separate path.

Android requests the advertised v1 preflight before saving a new implementation command. A successful response must match the objective digest and source revision. The resolved acceptance requirements are persisted with the request. Direct HTTP and conversation submissions use the same server admission gate, and retries reconcile the original request ID before repeating preflight. Confirmed saved jobs remain readable if dependencies later fail.

An explicit acceptance manifest is preferred. When it is empty, the host may supply **one unambiguous acceptance template matching the full objective (ignoring case and whitespace)**. The gateway derives named behavior evidence from the host's exact JUnit case mapping. Missing, ambiguous, unsupported or unrelated coverage produces a concrete admission blocker, not a job that inevitably ends with a generic partial response. This deliberately does not invent semantic tests for arbitrary objectives: a new unsupported feature still needs an appropriate approved contract and evidence producer.

Example host policy (replace the mirror/image/checks/cases with real qualified values):

```json
{
  "owner/repository": {
    "mirror": "/srv/mirrors/repository.git",
    "image": "approved-validator@sha256:<64-lowercase-hex-digest>",
    "commands": [["./run-approved-tests"]],
    "allowed_checks": ["Required verification"],
    "acceptance_templates": [{
      "objectives": ["Repair the login validation"],
      "requirements": [{
        "id": "login-validation",
        "paths": ["src/login.kt"],
        "checks": ["Required verification"]
      }]
    }],
    "acceptance_map": [{
      "requirement_id": "login-validation",
      "case_id": "LoginTest.rejectsInvalidInput",
      "command_index": 0,
      "scope": "behavior",
      "report_path": "reports/tests.xml"
    }]
  }
}
```

`GATEWAY_ENGINEERING_VALIDATION_POLICY` remains a host-controlled file. Models cannot alter it or choose shell commands. Existing policies need `allowed_checks`; objective templates are optional when requests already supply explicit acceptance. The admitted policy digest is retained and checked again at validation. Fresh JUnit output remains required; stored reports, model statements and green workflow summaries cannot create behavior proof. Completion receipts now list verified/remaining requirement IDs and a next action; model planning and repair receive the retained requirements.

Global availability does not claim that every objective is implementation-ready. Capabilities advertise validation configuration and objective preflight separately. The per-request result distinguishes selected-model metadata qualification, GitHub access, storage/worker prerequisites, validation policy, source revision, runtime and evidence mapping. A repaired dependency is checked again on the next admission.

## Native collaboration

The grouped PR tool exposes `ready` and `draft` with expected-head guards, GraphQL state changes, remote readback and existing uncertain-write reconciliation. An ordinary `update` containing `draft` is rejected before mutation rather than silently discarding the field.

New grouped actions cover reviewer requests/removal, review-thread resolution/reopening, review dismissal, discussion/inline comment editing, labels, assignees, issue/PR reactions, conversation locking and auto-merge configuration. Thread discovery is bounded and exposes continuation. PR writes require the current head; issue writes require the current update timestamp; comment edits additionally require the comment timestamp. Thread/comment ownership is checked before dispatch. Mutations retain the native exact-approval and execution-journal boundaries. Auto-merge enablement is distinct from completed merge evidence. Reaction actions currently address the issue/PR itself, not arbitrary comment reactions.

API references: [GitHub PR GraphQL types](https://docs.github.com/en/graphql/reference/pulls), [review requests](https://docs.github.com/en/rest/pulls/review-requests), [reviews](https://docs.github.com/en/rest/pulls/reviews).

## Conversation behavior

The progress header distinguishes device-owned execution from a confirmed, detach-safe gateway job. Saved gateway work can be running, awaiting input or terminal; using a gateway model alone does not confer durable ownership.

The remaining gateway merge approval is now an inline conversation card with the exact PR, head, changed paths, test evidence and approval lifetime. Native approvals and questions keep their existing action/run bindings. MCP forms stay scoped to their originating run and retain a monotonic 40-second deadline; they do not persist form values or silently replay after interruption. A disconnected MCP server must reissue its question. Durable GitHub decisions and gateway approvals recover through their existing saved task/job mechanisms.

Generated PR bodies accurately describe a separate review pass by the same selected model. It is supplemental review, not independent validation. Deterministic activity observations remain distinct from selected-model prose.

## Validation scope

See the accompanying source-bound validation receipt for final test results. Host HTTP/model/runtime fixtures do not qualify the deployed Windows gateway, live GitHub credentials, Android speech engine, Compose rendering or physical device performance. Full Android CI is required on the final combined PR head. No production merge, release or host installation is performed by this follow-up.
