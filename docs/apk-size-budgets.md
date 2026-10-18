# APK size gates

The size checker measures APK bytes and their largest ZIP entries. Select the
variant explicitly with `--build-type debug` or `--build-type release`. The
default remains release; filenames never select a more permissive budget.

| Variant | arm64-v8a | x86_64 | Universal |
| --- | ---: | ---: | ---: |
| Release | 180 MiB | 180 MiB | 230 MiB |
| Debug | 320 MiB | 250 MiB | 390 MiB |

Debug limits are based on main commit `20cb01de`, Actions run `39109834697`:
314,968,509 / 244,103,214 / 380,703,034 bytes respectively. They allow about
7% growth over that baseline. Debug retains unshrunk DEX, coverage instrumentation
and native debug code; its size is not a production download measurement.

That run compiled successfully and passed native-library verification, then
failed because the debug APKs were compared with release budgets. The debug
workflow now uses debug limits and runs on PRs changing its gate. Both signed
and unsigned release workflows enforce the unchanged release limits. Every
workflow retains its size report on failure as well as success. Exceeded
budgets remain failures and missing APKs cannot pass.

This change corrects variant classification; it does not reduce APK size or
claim runtime performance improvements. Dependency removal and modular runtime
delivery remain separate work.
