# Native product campaigns in the Windows app

Open **Settings → Advanced → Diagnostics → Benchmarks → Native products**. The
existing **Compare** tab still compares Gateway model selections. **Native
products** uses captured results from GPT Mobile AI, ChatGPT, Codex and Claude
Code, the canonical independent executable grader, and signed native-trial
comparisons. An installed Windows build supplies the controller and templates;
no separate source checkout, Git executable or Python installation is needed.

The campaign controls never generate a product answer. **Freeze** reads public
fixtures. **Grade imported answers** submits existing candidate bytes to the
configured independent sandbox. **Recover grade receipts** reads original
request identities. Native sessions and any spending on them remain separate
observer actions. The grading sandbox must already be configured and available.

## Prepare and freeze

1. In **Campaigns**, choose **Export setup templates**. Open the exported lab
   configuration in the app's JSON editor. Its installed runtime, Gateway state
   directory and origin are filled from this installation.
2. Under **Observer and independent grader keys**, select the independent
   signers' existing Ed25519 PEM private keys, or generate each role's key in a
   separate external directory. Add their **public** key paths to the lab
   configuration. Separate keys do not establish independent custody; arrange
   observation and grading responsibilities independently of the candidate.
3. Record the actual product versions, native surfaces, allowed tool access and
   requested settings. Freeze a full starting revision, hardware class, common
   tool policy, cache reset procedure, cost accounting and time measurement
   basis. Unobserved provider model identity and acknowledgements remain null.
4. Choose **Quick** or **Comprehensive**. The campaign-definition editor can
   change the time/cost ceilings, repeats, cache policy and margin before freezing;
   its preview shows the resulting scheduled count. Save both edited JSON files,
   then select
   **Freeze matched campaign**. Setup fetches public task descriptors from the
   original Gateway owner, freezes task/grader/environment digests, signs the
   plan and writes the randomized order. A partial setup retains its identity:
   open it from **Saved campaigns** and select **Resume setup**.

| Preset | Public tasks | Independent families | Repeats | Four-product slots | Default per-slot ceiling |
| --- | ---: | ---: | ---: | ---: | --- |
| Quick | 12 Python + 4 repository | 16 | 1 | 64 | 300 seconds; USD 0 |
| Comprehensive | 100 Python + 12 repository | 25 | 3 | 1,344 | 1,200 seconds; USD 0 |

USD 0 is an editable preregistered ceiling, not a claim that any service is free.
Change budgets before freezing. Related task variants and repeated runs stay in
their original family; the four capstones comprise one family. Warm-cache work
requires a separate frozen campaign. The advanced controller also accepts a
custom campaign definition through `campaignPath` with the same validation. The
app's campaign picker/editor supplies that same field when a saved definition is
selected; **Use bundled preset** clears the override.

## Run and import each matched trial

**Run trials** displays the next product, task, version, repeat and public prompt.
Copy or save that public packet and run one fresh session on the scheduled
product surface with the frozen controls. Do not supply controller expectations,
hidden graders, reference solutions, private keys or unrelated source material.
The schedule view displays up to 500 rows and identifies truncation; its complete
CSV and exported report retain every planned slot.

Keep the product's original final output, including malformed answers and
whitespace. Candidate text is never rewritten to pass a grader.

| Product | Import format |
| --- | --- |
| GPT Mobile AI | Complete Gateway conversation history, with the completed job and matching user/assistant command identity. **Read saved GPT Mobile answer** reads an existing conversation; it does not send a message. |
| ChatGPT | One selected, complete, unbranched conversation export with one task turn and a completed public final answer. The selection helper extracts that conversation without editing its nodes or answer. |
| Codex | One completed native JSONL turn, with its final agent answer. |
| Claude Code | One successful native JSON result containing its session identity and final result. |

An accepted container format does not independently prove its origin. Fill the
measurements/provenance editor with observed duration, actual cost or null,
interventions, origin evidence, terminal state and actual-controls confirmation.
The observer must explicitly attest the imported origin and controls. The blank
template leaves time, cost and interventions unknown. A supplied zero must be an
observation. Save the measurements, choose the native export and select **Import
observed trial**.

Record `PRODUCT_FAILURE`, `HARNESS_FAILURE` or `UNSUPPORTED` explicitly when
there is no delivered answer, without attaching a candidate. Supply the digest
of the distinct observed dispatch identity and the required measurements. A
known product failure remains a known failure; harness or capability gaps remain
unknown. Imports follow the frozen order and cannot replace committed slots.
**Import prepared inbox** accepts consecutive directories named `000000`,
`000001`, etc., each with `measurements.json` and, for delivered answers,
`capture.raw`. It stops at the first missing slot and preserves previous imports.

Rejected imports are quarantined with their exact raw bytes and reason. They do
not fill a slot or silently become product failures. An answer outside the
canonical 65,536-byte UTF-8 limit is rejected without truncation. Repair the
capture/provenance problem without changing what the product actually delivered;
retain unsupported or harness evidence where the outcome cannot be established.

## Grade, recover and interpret

In **Scoreboard**, select the independent grader's key and choose **Grade imported
answers**. The existing supplied-answer grading implementation prepares one
immutable SQLite trial per delivered slot. It stores the original request ID,
owner, task and candidate digests and durably marks a submission before sending
it. All retained native evidence is checked before any grading request. The
grader signs product-blind result payloads under the separately registered key.

If an operation is interrupted, retain the campaign and choose **Recover grade
receipts**. Recovery performs one bounded readback pass. It never prepares a new
trial or repeats an uncertain submission. `NOT_OBSERVED` is unresolved admission,
not proof that nothing happened. A definitely prepared but never attempted trial
can be submitted by **Grade imported answers**, not by recovery. The advanced
`retire` operation uses the original cancellation identity; it creates no
automatic replacement.

App upgrades preserve local reports and original-ID recovery. If the installed
grading implementation differs from the frozen runtime, recovery saves status
only and reports `RUNTIME_CHANGED_READBACK_ONLY`. It does not sign new grades or
submit new work using changed code. Existing signed receipts remain readable.

The scoreboard shows observations against planned slots, verified completion
within budget, completion rate, timing percentiles, observed cost, coverage
reasons and matched comparisons against GPT Mobile AI. It retains the canonical
family weighting and simultaneous conservative 95% lower bounds. A comparative
win requires the preregistered margin against every peer with complete evidence.

- Missing observations, grades, capability coverage and costs stay unknown.
- Unknown cost prevents an otherwise passing answer from proving success within
  the cost budget. A known failure remains a failure.
- Timing percentiles include every observed state, including failures and
  unsupported routes; they do not describe only successful runs.
- Unsigned status fields do not become independently verified rubric scores.
- Delivered-final evidence does not prove first-attempt or best-within-budget
  performance; these stay unknown.
- Synthetic demonstrations test the controller only. They are permanently
  barred from live Gateway submission and never enter measured campaign history.

**Export readable scoreboard** produces self-contained HTML plus Markdown, JSON,
product CSV and complete trial-details CSV. It excludes native answer text,
provenance notes and signing keys. Keep the original campaign separately for
audit. Compare public key fingerprints with independently archived keys.

## GitHub intelligence scenarios

Setup exports include the ten canonical production scenario protocols and a
four-product capability template. Scenarios cover unfamiliar bugs, source paging,
CI repair, existing pull requests, context rotation, lost mutations, Gateway
restart, phone disconnect, terminal CI blockers and post-merge failures. The
catalog records required receipts and missing prerequisites; it does not execute
repository mutations or grant permissions.

The six candidate-facing read-only probes are labeled synthetic. Controller
expectations remain in a separate directory. Their results cannot become a live
GitHub Intelligence Score. Executable fixture grades, controller recovery checks,
Kotlin/JVM fixtures, SQLite fixtures and Actions-semantic checks do not establish
production GitHub, Android instrumentation, Room-processor or hosted Actions
execution. The ten-job score remains unknown until its separate production
receipt requirements are met.

## PowerShell and source operation

The exported setup includes `scripts/Invoke-NativeCampaign.ps1`. It uses the same
installed JSONL action boundary as the app and requires an explicit installed
`Gateway.exe` and Gateway state directory. It accepts a hashtable or an arguments
JSON file, writes UTF-8, preserves receipt identity and never invokes a shell
expression from input data. For example:

```powershell
$runtime = 'C:\path\to\active-version\Gateway.exe'
$state = 'C:\path\to\Gateway-state'
$helper = 'C:\Benchmarks\setup\scripts\Invoke-NativeCampaign.ps1'
& $helper -GatewayExe $runtime -Root $state -Action history
& $helper -GatewayExe $runtime -Root $state -Action read -Arguments @{ id = $campaignId }
& $helper -GatewayExe $runtime -Root $state -Action recover -Arguments @{
    id = $campaignId
    graderKeyPath = 'C:\Independent-Grader\grader.pem'
} -OutputFile 'C:\Benchmarks\receipts\recovery-001.json'
```

Supported actions are `templates`, `keys`, `create`, `resume-setup`, `history`,
`read`, `import`, `import-inbox`, `grade`, `recover`, `retire`, `export`, `demo`,
`select-chatgpt` and `read-mobile`. Use the app for normal setup and capture
collection. Source developers can run `python -m benchmarks.campaign --help`
with `gateway` on `PYTHONPATH`; the same installed-runtime and evidence checks
apply. The compatibility Tk dashboard retains its existing benchmark views.

## Verification scope

The new Python campaign suites use synthetic/native-format fixtures and blocked
network transports. The portable native presentation contract program exercises
unknown, partial and synthetic displays. Windows installer CI runs campaign
contracts, the .NET native contract program, WPF compilation and the existing
frozen installer smoke workflow. A source check or portable contract pass does
not establish a Windows install, live product result or comparative quality.
