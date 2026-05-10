cmake_minimum_required(VERSION 3.22.1)
project("nexara_llama")

# ──────────────────────────────────────────────────────────────
# Option: Use pre-built llama.cpp .so files instead of building
# from source. Set -DNEXARA_LLAMA_PREBUILT=ON to enable.
#
# When PREBUILT=ON, place .so files at:
#   app/src/main/jniLibs/arm64-v8a/libllama.so
#   app/src/main/jniLibs/armeabi-v7a/libllama.so
# And place llama.h header in: app/src/main/cpp/llama.h
# ──────────────────────────────────────────────────────────────
option(NEXARA_LLAMA_PREBUILT "Use pre-built llama.cpp .so files" OFF)

if(NOT NEXARA_LLAMA_PREBUILT)
    # ── Build llama.cpp from source via FetchContent ──
    include(FetchContent)

    # Configure llama.cpp options BEFORE fetching
    set(GGML_VULKAN            OFF CACHE BOOL "Vulkan needs host shader cross-compilation; enable only with proper host build" FORCE)
    set(GGML_CUDA              OFF CACHE BOOL "Disable CUDA (not on Android)" FORCE)
    set(GGML_METAL             OFF CACHE BOOL "Disable Metal (not on Android)" FORCE)
    set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
    set(LLAMA_CURL             OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

    FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp
        GIT_TAG        master
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(llama_cpp)
endif()

# ── JNI bridge library ──
add_library(nexara_llama SHARED native-lib.cpp)

# Android log library
find_library(log-lib log)

if(NOT NEXARA_LLAMA_PREBUILT)
    target_link_libraries(nexara_llama
        llama
        ${log-lib}
    )
    target_include_directories(nexara_llama PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
        ${llama_cpp_SOURCE_DIR}/include
    )
else()
    target_link_libraries(nexara_llama ${log-lib})
    target_include_directories(nexara_llama PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}
    )
endif()

# Strip debug symbols for release builds
set_target_properties(nexara_llama PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)
