cmake_minimum_required(VERSION 3.22.1)
project(timed_kokoro LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
include(FetchContent)
find_package(Git REQUIRED)

# Prepared Gateway development images run the candidate checkout without a
# network.  Use the image's verified archive cache when it is present, while
# retaining the pinned public URLs for ordinary Android and release builds.
set(kokoro_dependency_cache "$ENV{GPTMOBILE_KOKORO_DEPENDENCY_CACHE}")
function(kokoro_dependency_url output remote filename)
    if(kokoro_dependency_cache)
        if(NOT IS_ABSOLUTE "${kokoro_dependency_cache}")
            message(FATAL_ERROR "GPTMOBILE_KOKORO_DEPENDENCY_CACHE must be absolute")
        endif()
        set(local "${kokoro_dependency_cache}/${filename}")
        if(NOT EXISTS "${local}")
            message(FATAL_ERROR "Missing prepared Kokoro dependency ${filename}")
        endif()
        set(${output} "file://${local}" PARENT_SCOPE)
    else()
        set(${output} "${remote}" PARENT_SCOPE)
    endif()
endfunction()

kokoro_dependency_url(sherpa_url
    "https://codeload.github.com/k2-fsa/sherpa-onnx/tar.gz/38016c6e0622e90bf417c35e8b47e0eb805af417"
    "sherpa-onnx-38016c6e0622e90bf417c35e8b47e0eb805af417.tar.gz")
FetchContent_Declare(sherpa
    URL ${sherpa_url}
    URL_HASH SHA256=39416f4bcb4e0535908bda03991c0a5580214617135057f653124b45eb783086
    PATCH_COMMAND ${GIT_EXECUTABLE} --git-dir=. --work-tree=. apply ${CMAKE_CURRENT_SOURCE_DIR}/sherpa-timing.patch
    COMMAND ${GIT_EXECUTABLE} --git-dir=. --work-tree=. apply ${CMAKE_CURRENT_SOURCE_DIR}/sherpa-streaming-asr.patch)
FetchContent_Populate(sherpa)

# Only the trained English letter-to-sound tables are linked into the frontend.
kokoro_dependency_url(flite_url
    "https://codeload.github.com/festvox/flite/tar.gz/e9e2e37c329dbe98bfeb27a1828ef9a71fa84f88"
    "flite-e9e2e37c329dbe98bfeb27a1828ef9a71fa84f88.tar.gz")
FetchContent_Declare(flite
    URL ${flite_url}
    URL_HASH SHA256=1eebf476797cea10b4706cfbb9db9ba34f7b8ccb1fe7e0069b0b7528e0b0be46)
FetchContent_Populate(flite)

set(UTF8PROC_INSTALL OFF CACHE BOOL "" FORCE)
kokoro_dependency_url(utf8proc_url
    "https://codeload.github.com/JuliaStrings/utf8proc/tar.gz/refs/tags/v2.12.0"
    "utf8proc-v2.12.0.tar.gz")
FetchContent_Declare(utf8proc
    URL ${utf8proc_url}
    URL_HASH SHA256=f564011d38b2888d583d510b08e69ffa15aa117155db1b9b49ef1dfe1fa25111)
FetchContent_MakeAvailable(utf8proc)
if(ANDROID)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(ort_sha 25f8a9649e775af85d4216e624ef1a820b1e1f26d82d5fc5145657fa9789c685)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(ort_sha 1d092333f363bd80c5d31a601f556e751679a2ddf063267a40406e54c7f0e05a)
    else()
        message(FATAL_ERROR "Unsupported Kokoro ABI ${ANDROID_ABI}")
    endif()
    set(ort_archive "onnxruntime-android-${ANDROID_ABI}-static_lib-1.31.0.zip")
    kokoro_dependency_url(ort_url
        "https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.31.0/${ort_archive}"
        "${ort_archive}")
    FetchContent_Declare(ort
        URL ${ort_url}
        URL_HASH SHA256=${ort_sha})
    FetchContent_Populate(ort)
    set(ORT_INCLUDE_DIR ${ort_SOURCE_DIR}/include)
    set(ORT_LIBRARY ${ort_SOURCE_DIR}/lib/libonnxruntime.a)
else()
    # A host JVM can exercise the identical frontend, model and JNI result.
    find_package(JNI REQUIRED)
endif()

set(core ${sherpa_SOURCE_DIR}/sherpa-onnx/csrc)
add_library(kokoro_pronunciation STATIC kokoro_pronunciation.cpp
    ${flite_SOURCE_DIR}/lang/cmulex/cmu_lts_model.c ${flite_SOURCE_DIR}/lang/cmulex/cmu_lts_rules.c)
target_include_directories(kokoro_pronunciation PRIVATE ${flite_SOURCE_DIR}/include)
add_library(timed_kokoro SHARED timed_kokoro.cpp
    ${core}/offline-tts-kokoro-model.cc ${core}/file-utils.cc
    ${core}/onnx-utils.cc ${core}/symbol-table.cc ${core}/text-utils.cc)
target_include_directories(timed_kokoro PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${sherpa_SOURCE_DIR} ${ORT_INCLUDE_DIR} ${JNI_INCLUDE_DIRS})
target_compile_options(timed_kokoro PRIVATE -O2 -ffunction-sections -fdata-sections)
target_link_options(timed_kokoro PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
target_link_libraries(timed_kokoro PRIVATE kokoro_pronunciation utf8proc ${ORT_LIBRARY} ${CMAKE_DL_LIBS})
# The adapter forwards QNN ownership and repairs only non-convex fusion
# proposals. It uses ORT's public plugin ABI and never bypasses validation.
add_library(kokoro_qnn_adapter SHARED kokoro_qnn_adapter.cpp)
target_include_directories(kokoro_qnn_adapter PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${ORT_INCLUDE_DIR})
target_link_libraries(kokoro_qnn_adapter PRIVATE ${CMAKE_DL_LIBS})
if(ANDROID)
    # The published static archive carries full debug data for the entire runtime.
    target_link_options(timed_kokoro PRIVATE -Wl,--strip-debug)
    target_link_libraries(timed_kokoro PRIVATE android log)
endif()
include("${CMAKE_CURRENT_SOURCE_DIR}/streaming_asr.cmake")
if(NOT ANDROID)
    include(CTest)
    if(BUILD_TESTING)
        add_executable(kokoro_pronunciation_test kokoro_pronunciation_test.cpp)
        target_link_libraries(kokoro_pronunciation_test PRIVATE kokoro_pronunciation)
        add_test(NAME english_dictionary_misses COMMAND kokoro_pronunciation_test)
        add_executable(kokoro_provider_profile_test kokoro_provider_profile_test.cpp)
        add_test(NAME speech_provider_execution_evidence COMMAND kokoro_provider_profile_test)
        add_executable(kokoro_measurement_test kokoro_measurement_test.cpp)
        add_test(NAME speech_cache_audio_and_device_discovery COMMAND kokoro_measurement_test)
        add_executable(kokoro_context_cache_test kokoro_context_cache_test.cpp)
        add_test(NAME speech_compiled_context_cache COMMAND kokoro_context_cache_test)
        find_package(Threads REQUIRED)
        add_executable(kokoro_cancellation_test kokoro_cancellation_test.cpp)
        target_link_libraries(kokoro_cancellation_test PRIVATE Threads::Threads)
        add_test(NAME speech_finalization_cancellation COMMAND kokoro_cancellation_test)
        add_executable(kokoro_progress_test kokoro_progress_test.cpp)
        target_link_libraries(kokoro_progress_test PRIVATE Threads::Threads)
        add_test(NAME speech_live_native_progress COMMAND kokoro_progress_test)
        add_executable(kokoro_partition_test kokoro_partition_test.cpp)
        add_test(NAME speech_convex_graph_partitions COMMAND kokoro_partition_test)
        add_executable(kokoro_qnn_adapter_test kokoro_qnn_adapter_test.cpp)
        target_include_directories(kokoro_qnn_adapter_test PRIVATE ${ORT_INCLUDE_DIR})
        target_link_libraries(kokoro_qnn_adapter_test PRIVATE ${CMAKE_DL_LIBS})
        add_test(NAME speech_provider_abi_ownership COMMAND kokoro_qnn_adapter_test)
    endif()
endif()
