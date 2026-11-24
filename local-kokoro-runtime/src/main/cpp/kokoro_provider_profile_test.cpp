#include <stdexcept>
#include "kokoro_profile.h"
static void Check(bool passed) { if (!passed) throw std::runtime_error("Provider evidence regression"); }
static void Reject(const std::string &json) {
  try { KokoroExecutedProviderStats(json, "QNNExecutionProvider"); }
  catch (const std::runtime_error &) { return; }
  throw std::runtime_error("Malformed profile was accepted");
}

static void OptionalSummaryFailure() {
  const std::string discovery = "stage=selected;profile=available";
  std::string storage;
  const auto &allocated = KokoroBestEffortEvidenceSummary(discovery, storage,
      []() -> std::string { throw std::bad_alloc(); });
  Check(&allocated == &discovery && allocated == "stage=selected;profile=available");
  const auto &failed = KokoroBestEffortEvidenceSummary(discovery, storage, [&]() -> std::string {
    storage = "partial";
    throw std::runtime_error("optional formatting failed");
  });
  Check(&failed == &discovery && failed == "stage=selected;profile=available");
  const auto &completed = KokoroBestEffortEvidenceSummary(discovery, storage,
      [] { return std::string("stage=selected;observed_ops_reused=0"); });
  Check(&completed == &storage && completed == "stage=selected;observed_ops_reused=0");
}

static void OperatorObservations() {
  const std::string profile = R"([
    {"cat":"Node","ph":"X","dur":21.2,"name":"private_node_one_kernel_time","args":{"provider":"CPUExecutionProvider","op_name":"ConvInteger"}},
    {"cat":"Node","ph":"X","dur":5,"name":"private_node_two_kernel_time","args":{"provider":"CPUExecutionProvider","op_name":"ConvInteger"}},
    {"cat":"Node","ph":"X","dur":19,"name":"private_node_three_kernel_time","args":{"provider":"CPUExecutionProvider","op_name":"Sin"}},
    {"cat":"Node","ph":"X","dur":4,"name":"private_node_four_kernel_time","args":{"provider":"CPUExecutionProvider","op_name":"private/path;injected=value"}},
    {"cat":"Node","ph":"X","dur":100,"name":"private_node_five_kernel_time","args":{"provider":"QNNExecutionProvider","op_name":"QNNExecutionProvider_private_graph_hash_39"}},
    {"cat":"Node","ph":"X","dur":2,"name":"private_node_six_kernel_time","args":{"provider":"QNNExecutionProvider","op_name":"QNNExecutionProvider_private_graph_hash_42"}},
    {"cat":"Session","ph":"X","dur":500,"name":"load_kernel_time","args":{"provider":"CPUExecutionProvider","op_name":"ConvInteger"}}
  ])";
  const auto cpu = KokoroExecutedProviderStats(profile, "CPUExecutionProvider");
  const auto qnn = KokoroExecutedProviderStats(profile, "QNNExecutionProvider");
  Check(cpu.kernels == 4 && cpu.microseconds == 50 && cpu.operators.size() == 3);
  Check(qnn.kernels == 2 && qnn.microseconds == 102 && qnn.operators.size() == 1);
  Check(qnn.operators[0].operation == "QnnPartition" && qnn.operators[0].kernels == 2);
  const std::string discovery = "stage=selected;gpuProbe=available;ep=QNNExecutionProvider,type=2,vendor=1,id=0";
  const auto summary = KokoroObservedOperatorSummary(discovery, cpu, qnn, false);
  Check(summary.rfind(discovery, 0) == 0 && summary.size() <= 512);
  Check(summary.find(";observed_ops_scope=first_inference;observed_ops_reused=0") != std::string::npos);
  Check(summary.find(";observed_cpu_ops=ConvInteger:2:27") != std::string::npos);
  Check(summary.find(";observed_cpu_ops=Sin:1:19") != std::string::npos);
  Check(summary.find(";observed_cpu_ops=Other:1:4") != std::string::npos);
  Check(summary.find(";observed_qnn_ops=QnnPartition:2:102") != std::string::npos);
  for (const auto *private_value : {"private", "path", "injected", "hash"})
    Check(summary.find(private_value) == std::string::npos);
  Check(KokoroObservedOperatorSummary(discovery, cpu, qnn, true).find("observed_ops_reused=1") != std::string::npos);
  Check(KokoroObservedOperatorSummary(discovery, {}, {}, true) == discovery);

  const auto nested = KokoroExecutedProviderStats(R"([{"cat":"Node","ph":"X","dur":3,"name":"real_kernel_time","args":{"provider":"CPUExecutionProvider","nested":{"op_name":"Conv"}}}])", "CPUExecutionProvider");
  Check(nested.operators.size() == 1 && nested.operators[0].operation == "Other");
  Reject(R"([{"cat":"Node","ph":"X","dur":3,"name":"real_kernel_time","args":{"provider":"CPUExecutionProvider","op_name":"Conv","op_name":"Sin"}}])");

  // Many arbitrary labels collapse to one bounded public bucket. Long device
  // inventories and hotspot lists end only at complete record boundaries.
  std::string arbitrary = "[";
  for (int i = 0; i < 1000; ++i) {
    if (i) arbitrary += ',';
    arbitrary += "{\"cat\":\"Node\",\"ph\":\"X\",\"dur\":1,\"name\":\"node_kernel_time\",\"args\":{\"provider\":\"CPUExecutionProvider\",\"op_name\":\"private_label_" + std::to_string(i) + "\"}}";
  }
  arbitrary += ']';
  const auto bounded = KokoroExecutedProviderStats(arbitrary, "CPUExecutionProvider");
  Check(bounded.operators.size() == 1 && bounded.operators[0].kernels == 1000);
  auto many = cpu;
  for (const char *label : {"InstanceNormalization", "DynamicQuantizeLinear", "DynamicQuantizeLSTM", "ConvTranspose"})
    kokoro_profile_detail::ObserveOperator(many, label, "CPUExecutionProvider", 300000000);
  std::string devices = discovery;
  while (devices.size() < 450) devices += ";ep=CPUExecutionProvider,type=1,vendor=123456789,id=123456789";
  const auto limited = KokoroObservedOperatorSummary(devices, many, many, true);
  Check(limited.size() <= 512 && limited.find(";truncated=1") != std::string::npos);
  Check(limited.find(";observed_ops_more=1") != std::string::npos);
  Check(limited.find(";observed_cpu_ops=DynamicQuantizeLSTM:1:300000000") != std::string::npos);
  Check(limited.find(";observed_qnn_ops=DynamicQuantizeLSTM:1:300000000") != std::string::npos);
  for (char c : limited) Check((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
      (c >= '0' && c <= '9') || std::string("_.=,:;-").find(c) != std::string::npos);
}

int main() {
  OptionalSummaryFailure();
  OperatorObservations();
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
