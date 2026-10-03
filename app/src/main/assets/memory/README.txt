Universal Sentence Encoder QA — on-device memory embeddings

Source: https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/1/universal_sentence_encoder.tflite
Official guide: https://developers.google.com/edge/mediapipe/solutions/text/text_embedder
SHA-256: 89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749
Size: 6,120,274 bytes. Two 100-dimensional output heads. Memory consistently uses the first head.
Redistributed as the Google MediaPipe sample model. Retain this provenance with the asset.
Gradle prepareMemoryModel downloads this exact asset at build time and checks SHA-256 before packaging.
For offline builds, provide -PmemoryModelFile=/path/to/universal_sentence_encoder.tflite.
The model ships in the APK. Memory never downloads models or sends text for embedding at runtime.
