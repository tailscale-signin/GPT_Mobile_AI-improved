#include "onnxruntime_cxx_api.h"
#include "kokoro_output_validation.h"
#include "kokoro_profile.h"
#include <array>
#include <fstream>
#include <iostream>
#include <iterator>
#include <map>
#include <string>
#include <unordered_map>
#include <vector>

int main(int argc, char **argv) {
  if (argc != 5) return 2;
  const std::string mode = argv[1], pack = argv[2], libraries = argv[3], out = argv[4];
  if (mode != "generate" && mode != "control" && mode != "reload") return 3;
  constexpr int64_t tokens_count = 17;
  try {
    Ort::Env env(ORT_LOGGING_LEVEL_ERROR, "kokoro-cache-probe");
    env.RegisterExecutionProviderLibrary("QNNExecutionProvider", libraries + "/libkokoro_qnn_adapter.so");
    std::vector<Ort::ConstEpDevice> selected;
    for (const auto &device : env.GetEpDevices())
      if (std::string(device.EpName()) == "QNNExecutionProvider" && device.Device().Type() == OrtHardwareDeviceType_CPU)
        selected.push_back(device);
    if (selected.size() != 1) return 4;
    Ort::SessionOptions options;
    options.SetIntraOpNumThreads(1);
    options.SetInterOpNumThreads(1);
    options.AddFreeDimensionOverrideByName("sequence_length", tokens_count);
    options.AppendExecutionProvider_V2(env, selected, std::unordered_map<std::string, std::string>{
        {"backend_path", libraries + "/libQnnHtp.so"}, {"htp_arch", "79"}, {"soc_model", "69"}});
    const auto cache = out + "/kokoro-17-ctx.onnx";
    options.AddConfigEntry("ep.context_enable", mode == "generate" ? "1" : "0");
    options.AddConfigEntry("ep.context_embed_mode", "1");
    options.AddConfigEntry("ep.context_file_path", cache.c_str());
    options.EnableProfiling((out + "/" + mode).c_str());
    Ort::Session session(env, (mode == "reload" ? cache : pack + "/model.int8.onnx").c_str(), options);

    std::vector<int64_t> tokens(tokens_count);
    const int64_t syllable[] = {47, 43, 54, 54, 57};
    for (int64_t i = 1; i < tokens_count - 1; ++i) tokens[i] = syllable[(i - 1) % 5];
    std::vector<float> style(256);
    std::ifstream voices(pack + "/voices.bin", std::ios::binary);
    voices.seekg((tokens_count - 2) * 256 * sizeof(float));
    if (!voices.read(reinterpret_cast<char *>(style.data()), style.size() * sizeof(float))) return 5;
    float speed = 1.f;
    const std::array<int64_t, 2> token_shape{1, tokens_count}, style_shape{1, 256};
    const std::array<int64_t, 1> speed_shape{1};
    const auto memory = Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
    std::array<Ort::Value, 3> inputs{
        Ort::Value::CreateTensor<int64_t>(memory, tokens.data(), tokens.size(), token_shape.data(), token_shape.size()),
        Ort::Value::CreateTensor<float>(memory, style.data(), style.size(), style_shape.data(), style_shape.size()),
        Ort::Value::CreateTensor<float>(memory, &speed, 1, speed_shape.data(), speed_shape.size())};
    const std::array<const char *, 3> input_names{"tokens", "style", "speed"};
    Ort::AllocatorWithDefaultOptions allocator;
    std::vector<Ort::AllocatedStringPtr> output_names;
    std::vector<const char *> names;
    for (size_t i = 0; i < session.GetOutputCount(); ++i) {
      output_names.push_back(session.GetOutputNameAllocated(i, allocator));
      names.push_back(output_names.back().get());
    }
    auto outputs = session.Run({}, input_names.data(), inputs.data(), inputs.size(), names.data(), names.size());
    kokoro_runtime::OutputValidationObservation observation;
    observation.output_count = outputs.size();
    if (outputs.size() != 2) return 6;
    const auto audio = outputs[0].GetTensorTypeAndShapeInfo(), durations = outputs[1].GetTensorTypeAndShapeInfo();
    observation.audio_frames = audio.GetElementCount(); observation.audio_type = audio.GetElementType(); observation.audio_rank = audio.GetDimensionsCount();
    observation.timing_count = durations.GetElementCount(); observation.timing_type = durations.GetElementType(); observation.timing_rank = durations.GetDimensionsCount();
    if (observation.timing_type == 7 && observation.timing_count == tokens_count)
      kokoro_runtime::ObserveDurations(outputs[1].GetTensorData<int64_t>(), tokens_count, observation);
    kokoro_runtime::ValidateOutputStructure(observation, tokens_count, 24000, 600, 1, 7);
    const auto measured = kokoro_runtime::MeasureAudio(outputs[0].GetTensorData<float>(), observation.audio_frames);
    kokoro_runtime::ValidateMeasuredAudio(measured, observation);

    const auto profile_name = session.EndProfilingAllocated(allocator);
    std::ifstream profile(profile_name.get(), std::ios::binary | std::ios::ate);
    if (!profile || profile.tellg() < 0 || profile.tellg() > 8 * 1024 * 1024) return 7;
    profile.seekg(0);
    const std::string json((std::istreambuf_iterator<char>(profile)), std::istreambuf_iterator<char>());
    const auto cpu = KokoroExecutedProviderStats(json, "CPUExecutionProvider"), qnn = KokoroExecutedProviderStats(json, "QNNExecutionProvider");
    if (cpu.kernels == 0 || qnn.kernels == 0) return 8;
    const auto meta = session.GetModelMetadata();
    const auto sample_rate = meta.LookupCustomMetadataMapAllocated("sample_rate", allocator);
    const auto speakers = meta.LookupCustomMetadataMapAllocated("n_speakers", allocator);
    if (!sample_rate || std::string(sample_rate.get()) != "24000" || !speakers) return 9;
    std::cout << "{\"status\":\"PASS\",\"mode\":\"" << mode << "\",\"ort\":\"" << Ort::GetVersionString()
              << "\",\"frames\":" << observation.audio_frames << ",\"tokens\":" << tokens_count
              << ",\"peak\":" << measured.peak << ",\"clipped_samples\":" << measured.clipped_samples
              << ",\"cpu_kernels\":" << cpu.kernels << ",\"qnn_kernels\":" << qnn.kernels << "}\n";
    std::cout << KokoroObservedOperatorSummary("stage=selected;host_htp_emulator=1", cpu, qnn, false) << '\n';
    return 0;
  } catch (const Ort::Exception &error) {
    const bool duplicate = std::string(error.what()).find("Duplicate definition of name (/Cast_5_output_0)") != std::string::npos;
    std::cout << "{\"status\":\"FAILED\",\"mode\":\"" << mode << "\",\"ort\":\"" << Ort::GetVersionString()
              << "\",\"error\":\"" << (duplicate ? "CONTEXT_DUPLICATE_CAST_DEFINITION" : "ORT_ERROR_OTHER")
              << "\",\"native_code\":" << error.GetOrtErrorCode() << "}\n";
    std::cerr << error.what() << '\n';
    return 1;
  }
}
