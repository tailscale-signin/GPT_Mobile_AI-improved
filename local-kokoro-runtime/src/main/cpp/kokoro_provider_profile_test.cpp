#include <stdexcept>
#include "kokoro_profile.h"
static void Check(bool passed) { if (!passed) throw std::runtime_error("Provider evidence regression"); }
static void Reject(const std::string &json) {
  try { KokoroExecutedProviderStats(json, "QNNExecutionProvider"); }
  catch (const std::runtime_error &) { return; }
  throw std::runtime_error("Malformed profile was accepted");
}
int main() {
  Check(KokoroExecutedProviderKernels(R"([{"cat":"Session","ph":"X","dur":1,"name":"initialize_kernel_time","args":{"provider":"QNNExecutionProvider"}}])", "QNNExecutionProvider") == 0);
  Check(KokoroExecutedProviderKernels(R"([{"cat":"Node","ph":"X","dur":0,"name":"compute_kernel_time","args":{"provider":"QNNExecutionProvider"}}])", "QNNExecutionProvider") == 0);
  Check(KokoroExecutedProviderKernels(R"([{"cat":"Node","ph":"X","dur":3,"name":"compute_kernel_time","args":{"provider":"CPUExecutionProvider"}}])", "QNNExecutionProvider") == 0);
  Check(KokoroExecutedProviderKernels(R"([{"cat":"Node","ph":"X","dur":3,"name":"compute_kernel_time","args":{"shape":{"float":[1,4]},"provider":"QNNExecutionProvider","note":"a } brace"}},{"cat":"Session","ph":"X","dur":1,"name":"load","args":{}}])", "QNNExecutionProvider") == 1);
  Check(KokoroExecutedProviderKernels(R"([{"cat":"Node","ph":"X","dur":3,"name":"compute_kernel_time","args":{"provider":"CPUExecutionProvider"}},{"cat":"Session","ph":"X","dur":1,"name":"load","args":{"provider":"QNNExecutionProvider"}}])", "QNNExecutionProvider") == 0);
  const auto timed = KokoroExecutedProviderStats(R"([{"cat":"Node","ph":"X","dur":2.25,"name":"one_kernel_time","args":{"provider":"QNNExecutionProvider"}},{"cat":"Node","ph":"X","dur":4,"name":"two_kernel_time","args":{"provider":"QNNExecutionProvider"}},{"cat":"Node","ph":"X","dur":7,"name":"cpu_kernel_time","args":{"provider":"CPUExecutionProvider"}}])", "QNNExecutionProvider");
  Check(timed.kernels == 2 && timed.microseconds == 7);
  // Nested labels, quoted JSON and an event's unrelated provider field cannot
  // manufacture execution evidence.
  Check(KokoroExecutedProviderKernels(R"([{"cat":"Session","ph":"X","dur":3,"name":"load","args":{"provider":"QNNExecutionProvider","nested":{"cat":"Node","name":"fake_kernel_time"}}}])", "QNNExecutionProvider") == 0);
  Check(KokoroExecutedProviderKernels(R"([{"cat":"Node","ph":"X","dur":3,"name":"compute_kernel_time","provider":"QNNExecutionProvider","args":{"nested":{"provider":"QNNExecutionProvider"}}}])", "QNNExecutionProvider") == 0);
  Check(KokoroExecutedProviderKernels(R"([{"cat":"Node","ph":"X","dur":3,"name":"compute_kernel_time","args":{"provider":"CPUExecutionProvider","note":"\"provider\":\"QNNExecutionProvider\""}}])", "QNNExecutionProvider") == 0);
  const auto escaped = KokoroExecutedProviderStats(R"([{"args":{"provider":"QNNExecutionProvid\u0065r","shapes":[{},[null,true,false,-3.5e-2]],"note":"\\\"}["},"dur":2.25e1,"name":"\ud83d\ude00\u005fkernel_time","ph":"X","cat":"Node"}])", "QNNExecutionProvider");
  Check(escaped.kernels == 1 && escaped.microseconds == 23);
  for (const std::string duration : {"0", "-1", "1e309", "300000001", "\"3\"", "null"}) {
    Check(KokoroExecutedProviderKernels("[{\"cat\":\"Node\",\"ph\":\"X\",\"dur\":" + duration +
        ",\"name\":\"compute_kernel_time\",\"args\":{\"provider\":\"QNNExecutionProvider\"}}]", "QNNExecutionProvider") == 0);
  }
  Check(KokoroExecutedProviderKernels(" [null, true, 3, [], {}] \n", "QNNExecutionProvider") == 0);
  for (const auto *invalid : {"[{", "{}", "[] trailing", "[{} {}]", "[{},]", "[{\"dur\":01}]",
      "[{\"dur\":1.}]", "[{\"dur\":1e}]", "[{\"dur\":NaN}]", "[{\"cat\":\"Node\",\"cat\":\"Session\"}]",
      "[{\"args\":{\"provider\":\"CPUExecutionProvider\",\"provider\":\"QNNExecutionProvider\"}}]",
      "[{\"name\":\"\\uD800\"}]", "[{\"name\":\"\\uDC00\"}]", "[{\"name\":\"\\q\"}]"}) Reject(invalid);
  Reject(std::string("[{\"nested\":") + std::string(65, '[') + "0" + std::string(65, ']') + "}]");
  // A realistic event count keeps large nested profiler records exercised.
  std::string many = "[";
  for (int i = 0; i < 2500; ++i) {
    if (i) many += ',';
    many += R"({"cat":"Node","ph":"X","dur":1.25,"name":"conv_kernel_time","args":{"provider":"CPUExecutionProvider","output_type_shape":[{"float":[1,32,64]}],"thread_scheduling_stats":{"main_thread":{"thread_pool_name":"session","run":4},"sub_threads":{"42":{"num_run":2}}}}})";
  }
  many += ']';
  const auto cpu = KokoroExecutedProviderStats(many, "CPUExecutionProvider");
  Check(cpu.kernels == 2500 && cpu.microseconds == 5000);
  Check(KokoroExecutedProviderStats(many, "QNNExecutionProvider").kernels == 0);
  return 0;
}
