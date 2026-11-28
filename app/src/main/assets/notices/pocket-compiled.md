# Compiled Pocket TTS provenance and licenses

The experimental GPU/NPU adapter uses the published English preset-voice graphs from
[mlboydaisuke/Pocket-TTS-LiteRT](https://huggingface.co/mlboydaisuke/Pocket-TTS-LiteRT/tree/e0e68d4fb79feb529d7d0f8d0bbfad8d15a18a1e),
revision `e0e68d4fb79feb529d7d0f8d0bbfad8d15a18a1e`. The complete 224,355,715-byte
installation, including the original tokenizer, is pinned by length and SHA-256 in
`PocketCompiledSpeechCatalog`. Its aggregate manifest SHA-256 is
`f41281301454d45ff9a4064add84bd9fd680b70b5db78e3b68a4223b6ccb51bf`.

## Weights and voice attribution

The graph publisher credits [Kyutai Pocket TTS](https://github.com/kyutai-labs/pocket-tts)
and applies [Creative Commons Attribution 4.0](https://creativecommons.org/licenses/by/4.0/)
to the exported weights. Conversion/export by john-rocky / LiteRT-Models. This app
uses the published files unchanged. The tokenizer comes from
[Kyutai pocket-tts-without-voice-cloning](https://huggingface.co/kyutai/pocket-tts-without-voice-cloning/tree/d29db7978e464fb90cb3359ee0c69a273b9142cc)
at the exact revision used by the graph conversion script; the 59,339-byte model
has SHA-256 `d461765ae179566678c93091c5fa6f2984c31bbe990bf1aa62d92c64d91bc3f6`.
A copy is included only in test resources to verify tokenizer parity.

Preset voice attribution, as recorded by the graph publisher:

| Voice | Source | License |
| --- | --- | --- |
| alba | Alba MacKenna, alba-mackenna | CC BY 4.0 |
| marius, javert | Kyutai voice donations | CC0 |
| charles, mary, eve | VCTK corpus | CC BY 4.0 |

Voice sources: [Kyutai tts-voices](https://huggingface.co/kyutai/tts-voices) and the
Kyutai model repository above. This pack contains no voice-cloning encoder and no
Expresso/EARS voices subject to the publisher's noncommercial restrictions.

## Adapted source

`PocketCompiledTokenizer`, `PocketCompiledSynthesizer`, and `PocketCompiledDecoder`
adapt host orchestration from [LiteRT-Models Pocket TTS](https://github.com/john-rocky/LiteRT-Models/tree/5adaa31a7e733cb126f30ec25652e020ee723ece/pockettts),
commit `5adaa31a7e733cb126f30ec25652e020ee723ece`. Changes include direct parsing of
the original SentencePiece model, verified model leases, bounded sentence handling,
incremental causal-prefix decode, cancellation checks, strict raw PCM validation,
explicit accelerator requests, and failure/retirement receipts. Upstream's Mimi
decoder transformer CPU placement is preserved because its publisher reports
GPU numerical/audio degradation for that graph.

MIT License

Copyright (c) 2026 Daisuke Majima

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Runtime and qualification boundary

Google LiteRT 2.4.1 runtime, GPU accelerator and Qualcomm plugin artifacts are
published under Apache 2.0. `docs/audits/pocket-compiled-runtime.json` records the
Google Maven URLs, publisher SHA-1 confirmations, SHA-256 hashes, byte lengths,
and native inventories. The NPU compiler/dispatch pair is extracted to a private
immutable directory so it does not replace the existing LiteRT-LM dispatch.
The standard build uses its one audited QAIRT 2.52.0 runtime; the separate GenieX
build does not package this Pocket NPU plugin pair.

GPU/NPU are requested through `CompiledModel`, with automatic whole-model CPU
fallback disabled. The language-model and vocoder graphs request the chosen
accelerator; the Mimi transformer explicitly uses CPU with the configured thread
count. Native failures remain failures. A completed invocation or an available
accelerator does not establish kernel placement, numerical/semantic fidelity,
real-time performance, or device qualification. Receipts retain requested
placement and completed invocation counts; kernel attribution remains unmeasured.

The published graphs have fixed shapes. The streaming adapter recomputes the
incomplete transformer block, then crops newly produced PCM from the causal
vocoder prefix. It emits the first four latent frames before generation finishes
and subsequently decodes every sixteen frames, without duplicating earlier audio.
This can add work compared with the publisher's whole-utterance decoder. Native
`run` has no public interruption API; cancellation suppresses delivery as soon as
the current invocation returns and retains all live native resources until then.
The graphs provide no native word alignment. Caption-enabled playback uses the
separate local alignment pack below; the adapter emits no fabricated word cues.
Physical Qualcomm/GPU execution and listening/transcript qualification
must be measured on a compatible device before these experimental routes can be
promoted over the default MediaPipe Pocket CPU route.

## Optional local caption alignment

The existing Moonshine Voice 0.1.6 runtime can derive word timings from generated
audio with its English streaming attention decoder. The English model assets are
MIT licensed; the app's existing Moonshine notice remains applicable. The derived
pack uses the same immutable `quantized_26_08_21` base assets as the microphone
recognizer and adds this file from publisher mirror revision
`0bf2f2e5aff22e6fbba4300b00a4e00bbc4f8aae`:

- Path: `model/tiny-streaming-en/quantized_26_08_21/decoder_kv_with_attention.ort`
- Bytes: `32,515,016`
- SHA-256: `c1b41adca4ef75ad8dfdfc71875b5d0ae9be959865a14498da01cbddab8c02b8`
- Complete derived pack: `77,748,675` bytes
- Model identity: `cb069e5b36a52ef17b15dbcf38689c038a3b75a2f0a399babd09eec70dc7b95d`

Pinned source: <https://huggingface.co/moonshine-ai/moonshine-voice-assets/tree/0bf2f2e5aff22e6fbba4300b00a4e00bbc4f8aae/model/tiny-streaming-en/quantized_26_08_21>.
The attention decoder is installed independently and cannot replace or invalidate
the recognizer's active files. Existing verified base files may be copied while
their installation lease is held; all destination files retain digest checks.

Caption alignment disables speculative decoding to avoid the pinned runtime's
attention-layout defect. It uses final recognition of each bounded phrase's
actual post-tempo PCM, with exact normalized lexical matching. The model's
constant word-confidence field does not qualify acoustic accuracy. Missing or
unmatched timings remain explicit; no duration is allocated by word count.
