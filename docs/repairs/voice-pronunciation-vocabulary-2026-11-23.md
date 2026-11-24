# Voice pronunciation and vocabulary

## Implemented behavior

The user reported incorrect and missing word pronunciation in voice chat. This change improves the text delivered to speech engines and preserves its relationship to the displayed answer.

- Keep accented letters, numeric and common named HTML entities, useful symbols and short technical inline elements. Ordinary underscores separate words. Hidden reasoning, source addresses and full code blocks retain their existing treatment.
- Add curated technical names and initialisms, dotted abbreviations, ordinal numbers, fractions, scientific notation, common formulas and unit forms to the native English frontend.
- Read recognized model labels such as GPT-4 and standards such as UTF-8 without treating their separator as a negative sign. Arithmetic and unknown labels retain their previous semantics.
- Accept saved corrections for accented names and literal terms such as C++, C#, .NET and GPT-4. Match bounded whole terms once before tokenization and preserve every substituted word's original caption range.
- Apply the same saved corrections in native Kokoro, SDK phrase planning and Android speech. SDK engines retain their natural-language frontend. Android captures a correction snapshot for pending playback and retains cancellation checks.

The shipped settings remain under Pronunciation in voice tuning. Use one word or technical term per line, for example:

```text
Melo = mellow
C++ = see plus plus
GPT-4 = gee pee tee four
```

## Confirmed cause and scope

Independent inspection of the pinned US and GB packs found 178,401 and 191,924 lexicon entries, respectively, with no missing dictionary phone symbols. Actual dictionary misses such as github, chatgpt, opengl, glm, qnn and onnx reach an English letter-to-sound fallback that does not reliably interpret their intended technical reading. Explicit aliases such as git hub, chat g p t and open g l use known dictionary words.

The text projection also discarded useful encoded characters and some short inline technical elements. Saved corrections previously accepted only ASCII words and could run after the original term had already been split. These concrete defects are addressed in the source.

Latin folding extends accepted English input for letters such as Ł, ø, æ, œ and ß. It does not establish native foreign-language pronunciation. Ambiguous homographs such as read, live, content and record retain their existing behavior: broad substitutions can change meaning, and lookahead is not uniformly available across speech chunks. Some dictionary-missing inflections still use predicted stress. General formulas outside explicitly supported forms retain literal component readings.

## Verification observed before workspace loss

| Verification | Result |
| --- | --- |
| Kokoro vocabulary, retained normalizer and lexicon JUnit suites | 50 methods passed: 23 new, 23 retained normalizer, 4 retained lexicon |
| Shared projection, corrections, SDK phrase plan and editor suites | 46 methods passed |
| Canonical host validation | 711 tests passed; policy, dependency, secret, generated-reference, regex and resource checks passed |
| Actual pinned US/GB lexicon corpus | 64 cases returned with no projection failures and all source spans in bounds |
| Character-by-character production speech buffer versus final replay | 48 comparisons matched across both dialects, with corrections on and off |
| Actual emitted dictionary misses through CMU fallback | 24 sequences were nonempty and supported by the pinned model symbols |

All existing test methods are retained. The required-method inventory identifies 128 ordinary project methods across nine suites, including Android and plugin tests that still need hosted execution.

Full Android compilation was not admitted locally: the production memory preflight could not establish required cgroup headroom. AndroidVoiceAudioTest also lacked local Mockito dependencies, and the plugin component harness lacked its complete hardware/diagnostic dependency closure. No gate, production heap or test assertion was weakened.

## Recovery and provenance

The workspace became unavailable after all production source, tests, generated application inventory, release notes, feature references and the canonical validation log had been uploaded as immutable GitHub blobs. Every successful upload was checked against the exact blob identity in the tested local candidate, 3a2870aa2defd307313e67ffcade7f16d7f08ca6, whose parent was e40659976e33b2e305169f66fbb5c017f6e93282.

The final publication candidate reuses those unchanged production and test blobs. Only this documentation, the task/evidence summaries, the release-scope manifest and the document hashes in the feature map were reconstructed through the connected GitHub account. Unuploaded original raw probe and component-test receipt files are unavailable; their recorded hashes and directly observed outcomes are retained as historical provenance, not fabricated replacement logs. The uploaded canonical-fast.log and independent Kotlin probe sources remain available.

The companion task checkpoint records exact uploaded source identities, evidence limits and integration ownership. Current hosted project checks remain required. This work does not establish physical-phone acoustics, accelerator performance, a complete Android build or a published release.

## Integration and publication

Use the existing PR #2367 and fix/diagnostic-execution-completion-2026-11-22 branch. Preserve all incoming source and existing owners' active work. This pronunciation source reservation is complete; the shared PR remains subject to the other source holds, final applicable PR checks, a guarded merge, actual-main checks and the existing coordinated Android 0.9.66.15/code149 plus Gateway18.1.3 publisher.
