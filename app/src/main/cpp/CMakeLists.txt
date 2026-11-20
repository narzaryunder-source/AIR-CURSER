# Offline speech-to-text for the assistant: whisper.cpp (MIT, third_party/whisper.cpp, pinned to v1.9.5).
#
# ggml is built with every Android CPU level as a separate library (armv8.0 … armv9.2); at run time
# the best one for the phone's CPU is loaded, so old phones do not crash and new ones go fast.
cmake_minimum_required(VERSION 3.22)
# Optimized also in the debug APK (that is the one we release): speech recognition is heavy maths.
set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
project(aircursor_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp")
if (NOT EXISTS "${WHISPER_SRC}/CMakeLists.txt")
    message(FATAL_ERROR "whisper.cpp is missing: run  git submodule update --init  (third_party/whisper.cpp)")
endif ()

set(BUILD_SHARED_LIBS        ON  CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL          ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS    ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE              OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP              OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE           ON  CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2             OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL             OFF CACHE BOOL "" FORCE)

add_subdirectory("${WHISPER_SRC}" whisper.cpp)

add_library(aircursor_whisper SHARED whisper_jni.cpp)
target_link_libraries(aircursor_whisper PRIVATE whisper ggml)
if (ANDROID)
    target_link_libraries(aircursor_whisper PRIVATE log)
else ()
    # Built on a computer to test this bridge from the JVM.
    find_package(JNI REQUIRED)
    target_include_directories(aircursor_whisper PRIVATE ${JNI_INCLUDE_DIRS})
endif ()
target_compile_options(aircursor_whisper PRIVATE -O2)
