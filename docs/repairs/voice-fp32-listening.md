# Kokoro FP32 listening qualification

Use the same ROG Phone 9 Pro, Heart voice, rate 1.0, two native threads, output route, volume, pause setting and initial thermal state for each capture. Record the build commit, FP32 digest, route, SDK bucket, text-arrival mode and temperature with each set. Collect native CPU, GPU and HTP separately. MediaPipe Kokoro CPU is the listening reference. The SDK exposes a requested GPU delegate without confirming which kernels ran there.

## Device and listening trial

Install the chosen build and its matching Android test APK. Install the original FP32 pack and the English Kokoro streaming bundle through Voice settings. The listening test accepts only the 24 numbered public fixtures in the Android test assets. Fixture 19 supplies an explicit pronunciation override. Run the same driver against the previous revision for the native baseline. The driver uses existing production entry points and requires verified native provider evidence throughout the fixture.

Use the installed test component shown by `adb shell pm list instrumentation`. For example, the following plays the seventh public phrase.

```sh
python3 scripts/voice_listening_trial.py play --runner "$VOICE_TEST_RUNNER" --backend QNN_GPU --fixture 07 --arrival character
```

Collect fixtures 01 through 24 as identically captured WAVs for each route. Use the same word or character arrival for each matched set. Capture through the same loopback or external recorder so recorded pauses include output stalls. Keep the actual sample rate and gain consistent. Keep private chats out of the recordings.

```sh
python3 scripts/voice_listening_trial.py prepare --previous previous-native --candidate candidate-native --streaming streaming-cpu --output trial
python3 scripts/voice_listening_trial.py score trial
```

Give each of three independent listeners one numbered folder. They hear 72 clips each, presented in a different order. In each sheet enter 1 or 0 for target correctness and for an unnatural mid-phrase pause. Keep the coordinator file separate until the three sheets are complete. The scorer reports agreement counts and passes at 95% target correctness, correctness at least equal to streaming CPU, and fewer marked pauses than the preceding native version. Repeat for each candidate or requested streaming delegate. The task does not upload any recordings or sheets.

## Device timing

In Voice settings select Original FP32, audible playback, Stability, all six workloads and a five-minute sustained target. Run controlled zero caption offset. Vary backend order across matched runs. Capture fresh sessions separately from the 20 resident samples per length. Compare warm first-presented p95 across eligible processors and the previous FP32 build. A candidate may trail the fastest valid reference by at most 100 ms. For text-ready unpunctuated joins, the p95 target is 150 ms across at least 20 joins. The join estimate combines observed retained waveform silence and an observed AudioTrack restart at that phrase boundary. Sustained runs require zero active-playback underruns. The matched listening recordings supply acoustic phrase boundaries for the SDK comparison. Check barge-in and subsequent replay on the same output route.

An unrun device or listening trial carries no qualification. Auto continues to require measured execution, paired native CPU audio validation and a 10% workload saving. Rebuilding or changing the planning policy invalidates the associated comparison.
