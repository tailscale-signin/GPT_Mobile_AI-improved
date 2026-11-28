#ifndef GPTMOBILE_ASR_QNN_FIXTURE_H_
#define GPTMOBILE_ASR_QNN_FIXTURE_H_

// Deterministic C ABI fixture only. These operations do not run any model or
// identify a physical device; the production pinned Sherpa ownership is tested.
enum AsrFixtureStage {
  kCreateLog = 1, kCreateBackend, kCreateDevice, kCreatePower,
  kCreateSystem, kReadMetadata, kCreateContext, kRetrieveGraph, kSetPower,
  kFreeContext = 20, kFreePower, kFreeDevice, kFreeBackend, kFreeLog, kFreeSystem
};
struct AsrQnnFixture {
  int fail_first = 0;
  int fail_second = 0;
  bool error_output = false;
  unsigned power_id = 4;
  int events[128]{};
  int event_count = 0;
  int unloads = 0;
};
using ConfigureAsrQnnFixture = void (*)(AsrQnnFixture *);
#endif
