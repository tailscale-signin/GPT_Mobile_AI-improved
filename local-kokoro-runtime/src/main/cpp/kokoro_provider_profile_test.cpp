#include <stdexcept>
#include "kokoro_profile.h"
static void Check(bool passed) { if (!passed) throw std::runtime_error("Provider evidence regression"); }
int main() {
  Check(KokoroExecutedProviderKernels(R"([{"cat":"Session","ph":"X","dur":1,"name":"initialize_kernel_time","args":{"provider":"QNNExecutionProvider"}}])", "QNNExecutionProvider") == 0);
  Check(KokoroExecutedProviderKernels(R"([{"cat":"Node","ph":"X","dur":0,"name":"compute_kernel_time","args":{"provider":"QNNExecutionProvider"}}])", "QNNExecutionProvider") == 0);
  Check(KokoroExecutedProviderKernels(R"([{"cat":"Node","ph":"X","dur":3,"name":"compute_kernel_time","args":{"provider":"CPUExecutionProvider"}}])", "QNNExecutionProvider") == 0);
  Check(KokoroExecutedProviderKernels(R"([{"cat":"Node","ph":"X","dur":3,"name":"compute_kernel_time","args":{"shape":{"float":[1,4]},"provider":"QNNExecutionProvider","note":"a } brace"}},{"cat":"Session","ph":"X","dur":1,"name":"load","args":{}}])", "QNNExecutionProvider") == 1);
  Check(KokoroExecutedProviderKernels(R"([{"cat":"Node","ph":"X","dur":3,"name":"compute_kernel_time","args":{"provider":"CPUExecutionProvider"}},{"cat":"Session","ph":"X","dur":1,"name":"load","args":{"provider":"QNNExecutionProvider"}}])", "QNNExecutionProvider") == 0);
  const auto timed = KokoroExecutedProviderStats(R"([{"cat":"Node","ph":"X","dur":2.25,"name":"one_kernel_time","args":{"provider":"QNNExecutionProvider"}},{"cat":"Node","ph":"X","dur":4,"name":"two_kernel_time","args":{"provider":"QNNExecutionProvider"}},{"cat":"Node","ph":"X","dur":7,"name":"cpu_kernel_time","args":{"provider":"CPUExecutionProvider"}}])", "QNNExecutionProvider");
  Check(timed.kernels == 2 && timed.microseconds == 7);
  try { KokoroExecutedProviderKernels("[{", "QNNExecutionProvider"); return 1; } catch (const std::runtime_error &) {}
  return 0;
}
