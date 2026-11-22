include(FetchContent)

set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "Build Opus statically" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "Do not build Opus tools" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "Do not build Opus tests" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "Do not install Opus CMake config" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "Do not install Opus pkg-config file" FORCE)
set(OPUS_DRED OFF CACHE BOOL "Do not build Opus DRED" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "Do not build Opus OSCE" FORCE)

FetchContent_Declare(
    opus_source
    GIT_REPOSITORY https://github.com/xiph/opus.git
    GIT_TAG v1.5.2
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(opus_source)

# Upstream Opus exposes headers as <opus.h> from its source include directory,
# while chiaki-ng includes them as <opus/opus.h>. Mirror the public headers into
# that namespaced layout for consumers; keep the Opus target itself unchanged.
set(OPUS_COMPAT_INCLUDE_DIR "${CMAKE_CURRENT_BINARY_DIR}/opus-compat-include")
file(MAKE_DIRECTORY "${OPUS_COMPAT_INCLUDE_DIR}/opus")
file(COPY "${opus_source_SOURCE_DIR}/include/"
     DESTINATION "${OPUS_COMPAT_INCLUDE_DIR}/opus"
     FILES_MATCHING PATTERN "*.h")

set(Opus_FOUND TRUE)
set(Opus_LIBRARIES opus)
set(Opus_INCLUDE_DIRS "${OPUS_COMPAT_INCLUDE_DIR}")
