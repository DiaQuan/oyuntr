cmake_minimum_required(VERSION 3.22.1)
project(oyuntr_remoteplay LANGUAGES C CXX)

include(FetchContent)

# Pin the API and native dependency graph reviewed by OyunTR. This fetches only chiaki-ng's
# shared library sources; OyunTR has its own UI, JNI adapter, and product modules.
FetchContent_Declare(
    chiaki_ng
    GIT_REPOSITORY https://github.com/chiaki-ng/chiaki-ng.git
    GIT_TAG 43728af2e639fecb8a7af1cd68c29820a40de973
    GIT_SUBMODULES_RECURSE TRUE
    GIT_PROGRESS TRUE
)
FetchContent_GetProperties(chiaki_ng)
if(NOT chiaki_ng_POPULATED)
    FetchContent_Populate(chiaki_ng)
endif()

# Build the shared protocol library directly. Do not add chiaki-ng's Android frontend target.
set(CHIAKI_ENABLE_ANDROID ON)
set(CHIAKI_IS_SWITCH OFF)
set(CHIAKI_ENABLE_TESTS OFF)
set(CHIAKI_ENABLE_CLI OFF)
set(CHIAKI_ENABLE_GUI OFF)
set(CHIAKI_ENABLE_BOREALIS OFF)
set(CHIAKI_ENABLE_SETSU OFF)
set(CHIAKI_ENABLE_STEAMDECK_NATIVE OFF)
set(CHIAKI_ENABLE_STEAM_SHORTCUT OFF)
set(CHIAKI_ENABLE_FFMPEG_DECODER OFF)
set(CHIAKI_ENABLE_PI_DECODER OFF)
set(CHIAKI_ENABLE_RUDP ON)
set(CHIAKI_LIB_ENABLE_OPUS ON)
set(CHIAKI_LIB_ENABLE_MBEDTLS OFF)
set(CHIAKI_USE_SYSTEM_CURL OFF)
set(CHIAKI_USE_SYSTEM_JERASURE OFF)
set(CHIAKI_USE_SYSTEM_NANOPB OFF)
set(CHIAKI_USE_SYSTEM_CURL OFF)
set(CHIAKI_LIB_OPENSSL_EXTERNAL_PROJECT ON)
set(CHIAKI_LIB_JSONC_EXTERNAL_PROJECT ON)
set(CHIAKI_LIB_MINIUPNPC_EXTERNAL_PROJECT ON)
set(CHIAKI_VERSION_MAJOR 1)
set(CHIAKI_VERSION_MINOR 10)
set(CHIAKI_VERSION_PATCH 1)
set(CHIAKI_VERSION 1.10.1)
add_definitions(-DCHIAKI_VERSION_MAJOR=1 -DCHIAKI_VERSION_MINOR=10 -DCHIAKI_VERSION_PATCH=1 -DCHIAKI_VERSION=\"1.10.1\")
list(APPEND CMAKE_MODULE_PATH "${chiaki_ng_SOURCE_DIR}/cmake")
list(APPEND CMAKE_MODULE_PATH "${CMAKE_CURRENT_SOURCE_DIR}/cmake")

include(OpenSSLExternalProject)
include(JsoncExternalProject)
include(MiniupnpcExternalProject)
add_subdirectory("${chiaki_ng_SOURCE_DIR}/third-party" "${CMAKE_CURRENT_BINARY_DIR}/chiaki-third-party")
add_subdirectory("${chiaki_ng_SOURCE_DIR}/lib" "${chiaki_ng_BINARY_DIR}/lib")

add_library(oyuntr_remoteplay SHARED chiaki_session_jni.cpp)
target_compile_features(oyuntr_remoteplay PRIVATE cxx_std_17)
target_include_directories(oyuntr_remoteplay PRIVATE "${chiaki_ng_SOURCE_DIR}/lib/include")
target_link_libraries(oyuntr_remoteplay PRIVATE chiaki-lib log android)
