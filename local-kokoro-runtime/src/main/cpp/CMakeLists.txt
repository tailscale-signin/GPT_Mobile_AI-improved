cmake_minimum_required(VERSION 3.22.1)
project(timed_kokoro LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
include(FetchContent)
find_package(Git REQUIRED)
FetchContent_Declare(sherpa
    URL https://codeload.github.com/k2-fsa/sherpa-onnx/tar.gz/af2d8d81dbfa61c4cea4c568da78f3982edca005
    URL_HASH SHA256=afcafe3c34bc2f15a363ad791fa1644a69bf0977a58c1b4f617468976c2faeb5
    PATCH_COMMAND ${GIT_EXECUTABLE} --git-dir=. --work-tree=. apply ${CMAKE_CURRENT_SOURCE_DIR}/sherpa-timing.patch)
FetchContent_Populate(sherpa)

# Only the trained English letter-to-sound tables are linked into the frontend.
FetchContent_Declare(flite
    URL https://codeload.github.com/festvox/flite/tar.gz/e9e2e37c329dbe98bfeb27a1828ef9a71fa84f88
    URL_HASH SHA256=1eebf476797cea10b4706cfbb9db9ba34f7b8ccb1fe7e0069b0b7528e0b0be46)
FetchContent_Populate(flite)

set(UTF8PROC_INSTALL OFF CACHE BOOL "" FORCE)
FetchContent_Declare(utf8proc
    URL https://codeload.github.com/JuliaStrings/utf8proc/tar.gz/refs/tags/v2.12.0
    URL_HASH SHA256=f564011d38b2888d583d510b08e69ffa15aa117155db1b9b49ef1dfe1fa25111)
FetchContent_MakeAvailable(utf8proc)
if(ANDROID)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(ort_sha bc68410f35f13df82c260830bf476ee5565f5efd0ec7622297829a929613c6c0)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(ort_sha f49dbf456ffe71dd417afb2123ef15202f8f57a4b2a05f12af1cf71f55448137)
    else()
        message(FATAL_ERROR "Unsupported Kokoro ABI ${ANDROID_ABI}")
    endif()
    FetchContent_Declare(ort
        URL https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-${ANDROID_ABI}-static_lib-1.28.2.zip
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
target_include_directories(timed_kokoro PRIVATE ${sherpa_SOURCE_DIR} ${ORT_INCLUDE_DIR} ${JNI_INCLUDE_DIRS})
target_compile_options(timed_kokoro PRIVATE -O2 -ffunction-sections -fdata-sections)
target_link_options(timed_kokoro PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
target_link_libraries(timed_kokoro PRIVATE kokoro_pronunciation utf8proc ${ORT_LIBRARY})
if(ANDROID)
    # The published static archive carries full debug data for the entire runtime.
    target_link_options(timed_kokoro PRIVATE -Wl,--strip-debug)
    target_link_libraries(timed_kokoro PRIVATE android log)
endif()
if(NOT ANDROID)
    include(CTest)
    if(BUILD_TESTING)
        add_executable(kokoro_pronunciation_test kokoro_pronunciation_test.cpp)
        target_link_libraries(kokoro_pronunciation_test PRIVATE kokoro_pronunciation)
        add_test(NAME english_dictionary_misses COMMAND kokoro_pronunciation_test)
    endif()
endif()
