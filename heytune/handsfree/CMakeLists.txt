cmake_minimum_required(VERSION 3.22)
project(needle_bridge)

# Prebuilt Cactus engine: download once per ABI with
#   needle download android-arm64 --out <ENGINE_DIR>/android-arm64
#   needle download android-armv7  --out <ENGINE_DIR>/android-armv7
# which provides libneedle.a + needle.h per Cactus platform folder.
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

  # libneedle.a ships per-ABI next to needle.h in the platform folder.
  # Use an explicit path (not find_library): the file is staged by CI
  # (`needle download`) and must exist at configure time.
  set(NEEDLE_LIB "${NEEDLE_ENGINE_DIR}/${CACTUS_ABI}/libneedle.a")
  if(EXISTS "${NEEDLE_LIB}")
    message(STATUS "Linking prebuilt engine: ${NEEDLE_LIB}")
    target_link_libraries(needle_bridge ${NEEDLE_LIB} log)
  else()
    message(WARNING "libneedle.a not found in ${NEEDLE_ENGINE_DIR}/${CACTUS_ABI}; run the engine download step first.")
    target_link_libraries(needle_bridge log)
  endif()
else()
  message(WARNING "No Cactus engine for ${ANDROID_ABI}; building stub.")
  target_compile_definitions(needle_bridge PRIVATE HANDSFREE_STUB)
  target_link_libraries(needle_bridge log)
endif()
