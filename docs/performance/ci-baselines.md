# CI and device performance evidence

`Workflow health` publishes a weekly/manual JSON history artifact and job summary, retaining
30 days of snapshots. It samples up to 300 runs and records success/failure/cancellation plus
median observed elapsed time. This interval includes queue/post-processing; it is not an
Android startup or memory measurement. Compare repeated runs on the same runner/image,
Gradle cache state, ABI and source scope before claiming a speedup.

Startup, memory and backend performance use the existing device-validation workflow and
`scripts/qualify_device_performance.py`. Reviewed baselines live under
`docs/implementation/performance-baselines`. Missing compatible hardware or trusted models
must remain NOT_QUALIFIED. This change creates no fabricated baseline or default passing
threshold. Use the existing measured device gates for regression alerts; establishing new
startup, memory or QNN thresholds requires fresh compatible-device evidence.
