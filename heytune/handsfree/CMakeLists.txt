cmake_minimum_required(VERSION 3.22)
project(needle_bridge)

# Prebuilt Cactus engine: download once per ABI with
#   cactus download android-arm64 --out <ENGINE_DIR>/android-arm64
#   cactus download android-armv7  --out <ENGINE_DIR>/android-armv7
# which provides libcactus.a + cactus.h per Cactus platform folder
# (Cactus 4 renamed libneedle.a/needle.h -> libcactus.a/cactus.h;
#  the old names are still accepted as a fallback).
if(NOT DEFINED NEEDLE_ENGINE_DIR)
  set(NEEDLE_ENGINE_DIR "${CMAKE_SOURCE_DIR}/engine")
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
  set(CACTUS_ABI "android-arm64")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
  set(CACTUS_ABI "android-armv7")
elseif(ANDROID_ABI STREQUAL "riscv64")
  set(CACTUS_ABI "android-riscv64")
else()
  # No prebuilt engine for this ABI (e.g. x86_64 emulators):
  # build a stub so the APK still assembles; NeedleBridge reports
  # nativeAvailable=false at runtime.
  set(CACTUS_ABI "")
endif()

add_library(needle_bridge SHARED src/main/cpp/needle_bridge.cpp)

if(ANDROID)
  # 16 KB page-size alignment: without this, Android 15+ shows a
  # 16 KB-compatibility warning on every launch ("LOAD segment not
  # aligned") and 16 KB-page devices refuse to load the lib at all.
  # NDK r27's lld honours both flags.
  target_link_options(needle_bridge PRIVATE
    "LINKER:-z,max-page-size=16384"
    "LINKER:-z,common-page-size=16384")
endif()

if(CACTUS_ABI)
  target_include_directories(needle_bridge PRIVATE ${NEEDLE_ENGINE_DIR}/${CACTUS_ABI})

  # libcactus.a ships per-ABI next to cactus.h in the platform folder.
  # Use an explicit path (not find_library): the file is staged by CI
  # (`cactus download`) and must exist at configure time.
  # Accept the legacy libneedle.a name as a fallback.
  set(NEEDLE_LIB "")
  foreach(_candidate
      "${NEEDLE_ENGINE_DIR}/${CACTUS_ABI}/libcactus.a"
      "${NEEDLE_ENGINE_DIR}/${CACTUS_ABI}/libneedle.a")
    if(EXISTS "${_candidate}")
      set(NEEDLE_LIB "${_candidate}")
      break()
    endif()
  endforeach()
  if(NEEDLE_LIB)
    message(STATUS "Linking prebuilt engine: ${NEEDLE_LIB}")
    target_link_libraries(needle_bridge ${NEEDLE_LIB} log)
  else()
    message(WARNING "libcactus.a (or legacy libneedle.a) not found in ${NEEDLE_ENGINE_DIR}/${CACTUS_ABI}; run the engine download step first.")
    target_link_libraries(needle_bridge log)
  endif()
else()
  message(WARNING "No Cactus engine for ${ANDROID_ABI}; building stub.")
  target_compile_definitions(needle_bridge PRIVATE HANDSFREE_STUB)
  target_link_libraries(needle_bridge log)
endif()
