cmake_minimum_required(VERSION 3.22.1)
project(gptmobile_gguf LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
option(GGUF_NATIVE_DEBUG "Unoptimized native stepping build" OFF)
if(NOT GGUF_NATIVE_DEBUG)
    # Set variant flags before dependencies: includes ggml-cpu and quantization kernels.
    set(CMAKE_C_FLAGS_DEBUG "-O2 -g" CACHE STRING "Optimized diagnostic inference" FORCE)
    set(CMAKE_CXX_FLAGS_DEBUG "-O2 -g" CACHE STRING "Optimized diagnostic inference" FORCE)
endif()
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# Android x86_64 includes devices without AVX; keep the shipped baseline portable.
foreach(feature AVX AVX2 AVX512 FMA F16C BMI2)
    set(GGML_${feature} OFF CACHE BOOL "" FORCE)
endforeach()
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
option(GGUF_VULKAN "Build the Android Vulkan GPU backend" OFF)
if(ANDROID AND GGUF_VULKAN)
    # Vulkan-Hpp and SPIR-V are host-installed, architecture-independent headers.
    # Stage just these headers: adding /usr/include to an Android target would
    # also expose host libc headers and break NDK cross-compilation.
    find_path(GGUF_HOST_VULKAN_INCLUDE_DIR vulkan/vulkan.hpp
        HINTS "$ENV{VULKAN_SDK}/include" NO_CMAKE_FIND_ROOT_PATH REQUIRED)
    find_path(GGUF_HOST_SPIRV_INCLUDE_DIR spirv/unified1/spirv.hpp
        HINTS "$ENV{VULKAN_SDK}/include" NO_CMAKE_FIND_ROOT_PATH REQUIRED)
    find_package(SPIRV-Headers CONFIG REQUIRED NO_CMAKE_FIND_ROOT_PATH)
    set(GGUF_VULKAN_HEADERS "${CMAKE_CURRENT_BINARY_DIR}/vulkan-headers/include")
    file(COPY "${GGUF_HOST_VULKAN_INCLUDE_DIR}/vulkan"
              "${GGUF_HOST_SPIRV_INCLUDE_DIR}/spirv" DESTINATION "${GGUF_VULKAN_HEADERS}")
    if(EXISTS "${GGUF_HOST_VULKAN_INCLUDE_DIR}/vk_video")
        file(COPY "${GGUF_HOST_VULKAN_INCLUDE_DIR}/vk_video" DESTINATION "${GGUF_VULKAN_HEADERS}")
    endif()
    set(Vulkan_INCLUDE_DIR "${GGUF_VULKAN_HEADERS}" CACHE PATH "GGUF Vulkan headers" FORCE)
    # Only the Android platform loader may be linked, never a host SDK library.
    find_library(GGUF_ANDROID_VULKAN_LIBRARY vulkan ONLY_CMAKE_FIND_ROOT_PATH REQUIRED)
    set(Vulkan_LIBRARY "${GGUF_ANDROID_VULKAN_LIBRARY}" CACHE FILEPATH "Android Vulkan loader" FORCE)
    find_program(Vulkan_GLSLC_EXECUTABLE glslc HINTS "$ENV{VULKAN_SDK}/bin"
        PATHS "${CMAKE_ANDROID_NDK}/shader-tools/linux-x86_64"
              "${CMAKE_ANDROID_NDK}/shader-tools/darwin-x86_64"
              "${CMAKE_ANDROID_NDK}/shader-tools/windows-x86_64"
        NO_CMAKE_FIND_ROOT_PATH REQUIRED)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
else()
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
endif()
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
include(FetchContent)
FetchContent_Declare(llama
    URL https://codeload.github.com/ggml-org/llama.cpp/tar.gz/4413a192410fad1f054e9216114087a3e01527a0
    URL_HASH SHA256=4d0cb840af6e67de6afc72cd85aaa5d1419a9c6071a403d7a2069086f0bfe21b
)
FetchContent_MakeAvailable(llama)
# Build the pinned Jinja renderer without llama-common's server, download and
# tool execution helpers. All sources come from the same verified archive.
add_library(gguf_chat_template STATIC
    gguf_chat_template.cpp
    ${llama_SOURCE_DIR}/common/jinja/lexer.cpp
    ${llama_SOURCE_DIR}/common/jinja/parser.cpp
    ${llama_SOURCE_DIR}/common/jinja/runtime.cpp
    ${llama_SOURCE_DIR}/common/jinja/value.cpp
    ${llama_SOURCE_DIR}/common/jinja/string.cpp
    ${llama_SOURCE_DIR}/common/jinja/caps.cpp
    ${llama_SOURCE_DIR}/common/json.cpp
    ${llama_SOURCE_DIR}/common/unicode.cpp
)
target_include_directories(gguf_chat_template PUBLIC ${CMAKE_CURRENT_SOURCE_DIR} ${llama_SOURCE_DIR}/common)
target_link_libraries(gguf_chat_template PUBLIC llama PRIVATE vendor::nlohmann)
target_compile_options(gguf_chat_template PRIVATE -fvisibility=hidden)
add_library(gptmobile_gguf SHARED gguf_jni.cpp)
target_link_libraries(gptmobile_gguf PRIVATE gguf_chat_template)
target_compile_definitions(gptmobile_gguf PRIVATE GGUF_BUILD_TYPE="${CMAKE_BUILD_TYPE}" GGUF_NATIVE_DEBUG=$<BOOL:${GGUF_NATIVE_DEBUG}>)
target_compile_options(gptmobile_gguf PRIVATE -fvisibility=hidden)
target_link_options(gptmobile_gguf PRIVATE -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
if(NOT ANDROID)
    find_package(JNI REQUIRED)
    target_include_directories(gptmobile_gguf PRIVATE ${JNI_INCLUDE_DIRS})
    enable_testing()
    add_executable(gguf_chat_template_test ../../testHost/gguf_chat_template_test.cpp)
    target_link_libraries(gguf_chat_template_test PRIVATE gguf_chat_template)
    add_test(NAME gguf_chat_template COMMAND gguf_chat_template_test ${llama_SOURCE_DIR}/models/templates)
endif()
