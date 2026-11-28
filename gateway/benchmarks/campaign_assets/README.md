# Native product campaign setup

These files come from the Windows Gateway app's **Benchmarks → Native products →
Export setup templates** action. They are configuration and protocol templates,
not measured product results.

Edit `config/lab.example.json` in the app, recording actual matched controls and
native product versions. Keep unknown identity and usage fields null. Supply
separately held observer and grader public keys; keep both private keys outside
the app, campaign state and candidate workspaces. The app fills the installed
runtime and Gateway connection fields during export.

Quick schedules 64 matched product slots; Comprehensive schedules 1,344. Freeze
controls before collecting any answers. USD 0 is the default ceiling, not a claim
that product requests are free. The app never generates answers from these
campaign controls.

`scenarios/github-intelligence-catalog.json` prepares ten production scenario
protocols. Every unmeasured score stays null. `scenarios/public/` contains only
the separately labeled synthetic candidate probes; do not distribute controller
expectations, hidden grader material or private keys to candidates.

`scripts/Invoke-NativeCampaign.ps1` is an optional PowerShell 5.1/7 interface to
the installed Gateway runtime. It takes `-GatewayExe`, `-Root`, `-Action` and an
`-Arguments` hashtable or `-ArgumentsFile`. The actions are the same as the app,
including `recover`, which reads original grading identities without resubmitting.
Use `-OutputFile` only with a new local receipt filename.

Read the exported `GUIDE.md` (also bundled as `benchmarks/NATIVE_CAMPAIGNS.md`) for the complete workflow,
capture formats, evidence boundaries, recovery behavior and PowerShell examples.
