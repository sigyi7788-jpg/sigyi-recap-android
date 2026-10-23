cmake_minimum_required(VERSION 3.22.1)
project(sigyi_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(WHISPER_ROOT ${CMAKE_CURRENT_LIST_DIR})

set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_ROOT}/../ggml ${CMAKE_CURRENT_BINARY_DIR}/ggml-build EXCLUDE_FROM_ALL)

add_library(sigyi_whisper SHARED
    whisper.cpp
    whisper_jni.cpp
    aneforge/whisper-aneforge.cpp
)

target_include_directories(sigyi_whisper PRIVATE
    ${WHISPER_ROOT}
    ${WHISPER_ROOT}/../../../../../../../../include
    ${WHISPER_ROOT}/../ggml/include
    ${WHISPER_ROOT}/../ggml/src
    ${WHISPER_ROOT}/../ggml/src/ggml-cpu
)

target_compile_definitions(sigyi_whisper PRIVATE
    GGML_USE_CPU
    WHISPER_VERSION=\"sigyi-native\"
)

target_compile_options(sigyi_whisper PRIVATE
    -O3
    -ffunction-sections
    -fdata-sections
)

target_link_options(sigyi_whisper PRIVATE
    -Wl,--gc-sections
)

find_library(log-lib log)
find_library(android-lib android)
target_link_libraries(sigyi_whisper ${log-lib} ${android-lib} ggml)
