# Reuse the pinned Sherpa source and ONNX Runtime image already used by Kokoro.
# Only the ASR archive sees the boundary/provider patches; the TTS source keeps
# its existing provider, duration and graph-cache behavior.
function(add_streaming_asr)
  set(PROJECT_SOURCE_DIR "${sherpa_SOURCE_DIR}")
  list(PREPEND CMAKE_MODULE_PATH "${sherpa_SOURCE_DIR}/cmake")
  set(SHERPA_ONNX_ENABLE_PYTHON OFF)
  set(SHERPA_ONNX_ENABLE_TTS OFF)
  set(SHERPA_ONNX_ENABLE_TESTS OFF)
  set(SHERPA_ONNX_ENABLE_BINARY OFF)
  set(SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION OFF)
  set(SHERPA_ONNX_ENABLE_QNN ON)
  set(SHERPA_ONNX_ENABLE_CHECK OFF)
  set(BUILD_SHARED_LIBS OFF)
  set(onnxruntime_lib_files "${ORT_LIBRARY}")

  find_package(Python3 REQUIRED COMPONENTS Interpreter)
  set(QNN_SDK_ROOT "${CMAKE_CURRENT_BINARY_DIR}/asr-qairt-2.52.0.261104")
  set(ASR_QNN_MANIFEST "${CMAKE_CURRENT_SOURCE_DIR}/../../../../docs/audits/qnn-runtime-candidate.json"
    CACHE FILEPATH "Publisher-audited ASR QNN SDK identity")
  set(asr_header_arguments)
  if(kokoro_dependency_cache)
    set(QNN_SDK_ROOT "${kokoro_dependency_cache}/asr-qairt-2.52.0.261104")
    list(APPEND asr_header_arguments --offline)
  endif()
  execute_process(COMMAND "${Python3_EXECUTABLE}"
    "${CMAKE_CURRENT_SOURCE_DIR}/prepare_asr_qnn_headers.py"
    --manifest "${ASR_QNN_MANIFEST}"
    --output "${QNN_SDK_ROOT}"
    ${asr_header_arguments}
    RESULT_VARIABLE asr_headers_result)
  if(NOT asr_headers_result EQUAL 0)
    message(FATAL_ERROR "Could not prepare the pinned ASR QNN headers")
  endif()

  # The first FetchContent declaration wins, including transitive fbank/Eigen
  # requests. A prepared build fails on missing cache inputs and stays offline.
  file(READ "${CMAKE_CURRENT_SOURCE_DIR}/streaming_asr_dependencies.json" asr_dependency_json)
  string(JSON asr_dependency_count LENGTH "${asr_dependency_json}" dependencies)
  math(EXPR asr_dependency_last "${asr_dependency_count} - 1")
  foreach(index RANGE 0 ${asr_dependency_last})
    foreach(field name file url sha256)
      string(JSON asr_${field} GET "${asr_dependency_json}" dependencies ${index} ${field})
    endforeach()
    kokoro_dependency_url(asr_source_url "${asr_url}" "${asr_file}")
    FetchContent_Declare(${asr_name} URL "${asr_source_url}" URL_HASH "SHA256=${asr_sha256}")
  endforeach()

  include(kaldi-native-fbank)
  include(kaldi-native-io)
  include(kissfft)
  include(openfst)
  include(kaldifst)
  include(kaldi-decoder)
  include(simple-sentencepiece)
  # v0.7 uses uint8_t without including cstdint; libc++ supplied it transitively
  # but a standard host libstdc++ build must not depend on that accident.
  target_compile_options(ssentencepiece_core PRIVATE -include cstdint)
  include(json)
  include_directories("${ORT_INCLUDE_DIR}")
  add_subdirectory("${sherpa_SOURCE_DIR}/sherpa-onnx/csrc"
    "${CMAKE_CURRENT_BINARY_DIR}/sherpa-asr" EXCLUDE_FROM_ALL)
  # Upstream combines offline ASR, forced alignment, source separation and
  # speaker models in this archive. This boundary exposes streaming ASR only;
  # retain its complete dependency set without compiling unrelated GMM/Eigen
  # translation units on every Android build.
  set(asr_common base64-decode bbpe cat circular-buffer context-graph endpoint
    features file-utils fst-utils homophone-replacer hypothesis lfr lodr-fst math
    normal-data-generator onnx-utils packed-sequence pad-sequence parse-options
    phrase-matcher provider-config provider qnn-config session slice stack offline-whisper-model
    symbol-table text-utils timer transpose unbind utils version wave-reader wave-writer)
  get_target_property(sherpa_sources sherpa-onnx-core SOURCES)
  set(asr_sources)
  foreach(source IN LISTS sherpa_sources)
    get_filename_component(stem "${source}" NAME_WE)
    if(stem IN_LIST asr_common OR
       (source MATCHES "^online-" AND NOT source MATCHES "^online-(speech-denoiser|punctuation|cnn-bilstm)") OR
       source MATCHES "^./qnn/(online-|qnn-|utils\\.cc)")
      list(APPEND asr_sources "${sherpa_SOURCE_DIR}/sherpa-onnx/csrc/${source}")
    endif()
  endforeach()
  set_property(TARGET sherpa-onnx-core PROPERTY SOURCES "${asr_sources}")
  foreach(property LINK_LIBRARIES INTERFACE_LINK_LIBRARIES)
    get_target_property(asr_links sherpa-onnx-core ${property})
    list(REMOVE_ITEM asr_links kaldi-hmm-gmm-core)
    set_property(TARGET sherpa-onnx-core PROPERTY ${property} "${asr_links}")
  endforeach()
  target_compile_definitions(sherpa-onnx-core PRIVATE
    GPTMOBILE_ASR_BOUNDARY=1 SHERPA_ONNX_ENABLE_QNN=1)
  target_compile_options(sherpa-onnx-core PRIVATE -ffunction-sections -fdata-sections)
  target_include_directories(sherpa-onnx-core PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
  add_library(streaming_asr_boundary OBJECT streaming_asr.cpp)
  target_compile_definitions(streaming_asr_boundary PRIVATE GPTMOBILE_ASR_BOUNDARY=1 SHERPA_ONNX_ENABLE_QNN=1)
  target_include_directories(streaming_asr_boundary PRIVATE
    "${sherpa_SOURCE_DIR}" "${ORT_INCLUDE_DIR}" ${JNI_INCLUDE_DIRS})
  target_link_libraries(streaming_asr_boundary PRIVATE sherpa-onnx-core)
  # Keep JNI objects outside archives: --exclude-libs,ALL deliberately hides
  # third-party archive symbols, but the Java entry points must stay exported.
  target_link_libraries(timed_kokoro PRIVATE streaming_asr_boundary sherpa-onnx-core)
endfunction()

add_streaming_asr()
