cmake_minimum_required(VERSION 3.22.1)

project(localai_native LANGUAGES C CXX)

# Android's Debug variant propagates CMAKE_BUILD_TYPE=Debug to every
# llama.cpp/ggml target. That keeps symbols, but -O0 makes CPU prefill
# impractically slow on phones. Keep the APK debuggable while optimizing
# every native C/C++ target (not only the JNI bridge).
add_compile_options("$<$<COMPILE_LANGUAGE:C,CXX>:-O3>")
message(STATUS "LocalAI: enabling -O3 for all native C/C++ targets")

include(FetchContent)

# Keep the Android build portable: use the CPU backend with runtime feature detection.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_PREBUILT_UI OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG v1.0.0
    GIT_SHALLOW TRUE
    GIT_SUBMODULES ggml
    GIT_SUBMODULES_RECURSE TRUE
)

FetchContent_MakeAvailable(llama_cpp)

add_library(llama_jni SHARED llama_jni.cpp)
target_compile_features(llama_jni PRIVATE cxx_std_17)
target_compile_options(llama_jni PRIVATE -O3 -fexceptions -frtti)
target_link_libraries(
    llama_jni
    PRIVATE
    llama
    ggml
    llama-common
    android
    log
)
