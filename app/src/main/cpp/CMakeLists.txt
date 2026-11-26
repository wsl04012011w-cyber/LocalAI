cmake_minimum_required(VERSION 3.22.1)
project(ayroff_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG -ffunction-sections -fdata-sections" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -ffunction-sections -fdata-sections" CACHE STRING "" FORCE)
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "-Wl,--gc-sections -Wl,--strip-all" CACHE STRING "" FORCE)

# Portable Android arm64 CPU configuration; upstream runtime dispatches CPU kernels.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b5010
    GIT_SHALLOW FALSE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(ayroff_llama SHARED llama_jni.cpp)
target_include_directories(ayroff_llama PRIVATE ${llama_cpp_SOURCE_DIR}/include)
target_compile_options(ayroff_llama PRIVATE -O3 -DNDEBUG -ffunction-sections -fdata-sections -fexceptions -frtti)
target_link_options(ayroff_llama PRIVATE -Wl,--gc-sections -Wl,--strip-all)
target_link_libraries(ayroff_llama PRIVATE llama android log)
