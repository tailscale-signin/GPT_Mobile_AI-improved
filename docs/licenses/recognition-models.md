# Local speech recognition model licenses and identities

The recognition catalog downloads models only after an explicit installation
request. Every installed file has a fixed byte length and SHA-256 digest; Hugging
Face URLs select immutable commits. Archive installations verify both the whole
archive and each required extracted file. The complete provenance and host
compatibility record is in [voice-recognition-models.json](../audits/voice-recognition-models.json).

| Pack | Publisher / export | Model license | Download bytes |
| --- | --- | --- | ---: |
| Moonshine Tiny Streaming English | Moonshine AI | MIT | 45,233,659 |
| Moonshine Small Streaming English with attention decoder | Moonshine AI | MIT | 224,067,582 |
| Moonshine Medium Streaming English with attention decoder | Moonshine AI | MIT | 415,954,967 |
| Zipformer Streaming English 20M INT8 | Desh Raj / sherpa-onnx export by Fangjun Kuang | Apache-2.0 | 43,649,301 |
| Zipformer Streaming French INT8 | Shaojie Li | Apache-2.0 | 128,227,451 |
| Nemotron 3.5 ASR Streaming 0.6B, 560 ms INT8 CPU | NVIDIA / sherpa-onnx export | OpenMDW-1.1 | 682,215,356 |
| Nemotron 3.5 ASR Streaming 0.6B, 560 ms FP32 GPU inputs | NVIDIA / sherpa-onnx export | OpenMDW-1.1 | 2,594,373,279 |
| Nemotron 3.5 ASR Streaming 0.6B, 320 ms SM8750 HTP contexts | NVIDIA / sherpa-onnx QNN export | OpenMDW-1.1 | 442,444,790 |

Download sizes describe storage transfers, not peak runtime memory. The HTP
archive expands to 649,101,700 installed bytes. Selecting an accelerator pack
is not evidence that all model operations executed on that accelerator.

## Moonshine

The English streaming models and SDK are covered by the MIT license in the
[pinned v0.1.6 source](https://github.com/moonshine-ai/moonshine/blob/d8d8fa29d05ca67441a7719e70099f9f48917a93/LICENSE).
Copyright (c) 2025 Useful Sensors, Inc. (dba Moonshine AI).

The original Tiny installation and the separate Pocket caption alignment pack
retain their existing `quantized_26_08_21` files, download identities and storage
ownership. Small and Medium use the corresponding generation from the
[immutable publisher mirror](https://huggingface.co/moonshine-ai/moonshine-voice-assets/tree/e318117203f245fda2ca0142db6b8c057d86671c/model).
Both include their matching optional attention decoder for native word timing.

All Small and Medium files were downloaded and independently hashed. The Linux
x86-64 SDK 0.1.6 loaded both packs and transcribed the same public English test
recording with word timestamp output. This establishes host compatibility with
those weights; it does not establish Android latency, acoustic alignment,
thermal behavior or battery consumption. The SDK's placeholder word confidence
must not be presented as calibrated recognition confidence.

MIT license notice:

> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in
> all copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.

## Zipformer and sherpa-onnx

The [English export model card](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/blob/d42f2d9f7ca24806fb667456a18a9f1b60f70d16/README.md)
and [French export model card](https://huggingface.co/shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14/blob/3db9565d9633758d6b87b9a7b3dc09ebfb6b2c73/README.md)
declare Apache-2.0. Their cards link the original training model repositories.
The runtime is maintained by [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
under [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0).

The English and French packs are language-specific. The catalog does not
substitute one language's weights for another. Both packs were downloaded,
hashed and exercised with the official sherpa-onnx 2.0.3 Linux CPU wheel on
public recordings from their model repositories. Forty millisecond input chunks
and one-shot input produced identical final text and token timestamps. These
fixtures establish runtime compatibility, not an accuracy or device ranking.
Native token timing is converted by the recognition adapter; it is not a forced
alignment of generated speech.

## Nemotron

The NVIDIA source model is pinned at
[`ea30d66debe3740a08b573244286791d423d6b3e`](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b/tree/ea30d66debe3740a08b573244286791d423d6b3e).
Its governing license is [OpenMDW-1.1](https://openmdw.ai/license/1-1/), including
the model's upstream notices and terms. The sherpa-onnx export does not change
the model's license.

The CPU export is pinned at
[`ab43d895f5985b1bbab8b6eac8607fcdc05343f3`](https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-int8-2026-06-11/tree/ab43d895f5985b1bbab8b6eac8607fcdc05343f3).
The GPU input export is pinned at
[`2072aba9fb2d6ee6202b69e441761599ab7520b8`](https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-560ms-2026-06-11/tree/2072aba9fb2d6ee6202b69e441761599ab7520b8)
and requires `encoder.data` as well as all three ONNX graphs and `tokens.txt`.
The fixed 560 ms export cannot be relabeled as another chunk size. The complete
INT8 CPU pack was downloaded and hashed, then transcribed a public English
recording with the official sherpa-onnx 2.0.3 Linux wheel. Forty millisecond input
chunks and one-shot input produced identical final text and token timestamps;
the quantized decoder required no float-graph substitution.

The [SM8750 QNN archive](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn-binary-3/sherpa-onnx-qnn-SM8750-binary-nemotron-3.5-asr-streaming-0.6b-320ms.tar.bz2)
contains separate encoder, decoder and joiner HTP contexts for SM8750 (v79) and a
fixed 320 ms chunk. It must not be loaded on a different HTP architecture or
sent to the GPU. Qualcomm runtime redistribution and device compatibility are
separate from the model license and remain subject to the runtime's provenance
and packaging checks.

The catalog admits only NVIDIA's transcription-ready and broad-coverage
languages, totaling 32 locales across 28 language codes. It excludes the eight
adaptation-ready locales that require additional fine tuning. Native timestamps,
source model accuracy tables and successful CPU host tests do not qualify
physical phone behavior or establish a universal accuracy ranking.
