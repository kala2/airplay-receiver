# Builds libairplayrx.so: UxPlay's lib/ (+ llhttp, playfair), libplist and our JNI/shim code,
# linked against the prebuilt static libcrypto from scripts/build-openssl.sh.
cmake_minimum_required(VERSION 3.22.1)
project(airplayrx C CXX)

set(TP ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)
set(UXPLAY ${TP}/UxPlay)
set(PLIST ${TP}/libplist)
set(OPENSSL ${TP}/openssl-android)

file(STRINGS ${UXPLAY}/VERSION UXPLAY_VERSION)
file(STRINGS ${PLIST}/VERSION PLIST_VERSION)

if(NOT EXISTS ${OPENSSL}/lib/${ANDROID_ABI}/libcrypto.a)
    message(FATAL_ERROR "libcrypto for ${ANDROID_ABI} missing; run scripts/build-openssl.sh")
endif()

# Smaller, faster .so: only JNI entry points are exported, unused code is dropped.
set(COMMON_FLAGS -fvisibility=hidden -ffunction-sections -fdata-sections)

# Release (AGP builds it as RelWithDebInfo: -O2 -g; the APK gets a stripped copy, the unstripped
# one stays in app/build/intermediates for ndk-stack). ThinLTO links faster than full LTO on the
# slow dev Mac. OpenSSL's libcrypto.a is prebuilt without LTO, which is fine to link against.
if(CMAKE_BUILD_TYPE MATCHES "Rel")
    list(APPEND COMMON_FLAGS -O2 -flto=thin)
    set(RELEASE_LINK_FLAGS -flto=thin -Wl,--icf=safe)
endif()

# ---- OpenSSL (prebuilt) ----------------------------------------------------------------------
add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES
    IMPORTED_LOCATION ${OPENSSL}/lib/${ANDROID_ABI}/libcrypto.a
    INTERFACE_INCLUDE_DIRECTORIES ${OPENSSL}/include)

# ---- libplist (C sources only; we don't need the C++ wrapper) --------------------------------
add_library(plist STATIC
    ${PLIST}/src/base64.c ${PLIST}/src/bplist.c ${PLIST}/src/bytearray.c ${PLIST}/src/common.c
    ${PLIST}/src/hashtable.c ${PLIST}/src/jplist.c ${PLIST}/src/jsmn.c ${PLIST}/src/oplist.c
    ${PLIST}/src/out-default.c ${PLIST}/src/out-limd.c ${PLIST}/src/out-plutil.c
    ${PLIST}/src/plist.c ${PLIST}/src/ptrarray.c ${PLIST}/src/time64.c ${PLIST}/src/xplist.c
    ${PLIST}/libcnary/node.c ${PLIST}/libcnary/node_list.c)
target_include_directories(plist
    PUBLIC ${PLIST}/include
    PRIVATE ${PLIST}/src ${PLIST}/libcnary/include)
# What libplist's ./configure would detect for bionic (API 31), passed directly instead of
# generating a config.h.
target_compile_definitions(plist PRIVATE
    LIBPLIST_STATIC PACKAGE_VERSION="${PLIST_VERSION}" __LITTLE_ENDIAN__=1
    HAVE_STRNDUP HAVE_STRERROR HAVE_GMTIME_R HAVE_LOCALTIME_R HAVE_TIMEGM HAVE_STRPTIME
    HAVE_MEMMEM HAVE_TM_TM_GMTOFF HAVE_TM_TM_ZONE)
target_compile_definitions(plist PUBLIC LIBPLIST_STATIC)
target_compile_options(plist PRIVATE ${COMMON_FLAGS} -w)

# ---- UxPlay lib/ -----------------------------------------------------------------------------
file(GLOB UXPLAY_SRCS
    ${UXPLAY}/lib/*.c ${UXPLAY}/lib/llhttp/*.c ${UXPLAY}/lib/playfair/*.c)
add_library(airplay STATIC ${UXPLAY_SRCS} shim/dns_sd_shim.c)
# shim/ comes first so <dns_sd.h> resolves to our replacement.
target_include_directories(airplay
    PUBLIC ${UXPLAY}/lib
    PRIVATE shim ${UXPLAY}/lib/llhttp ${UXPLAY}/lib/playfair)
# Same definitions UxPlay's own CMake uses on Linux (lib/CMakeLists.txt), plus the libplist
# feature flags it derives from pkg-config for libplist >= 2.3.
target_compile_definitions(airplay PRIVATE
    STANDALONE __STDC_CONSTANT_MACROS __STDC_LIMIT_MACROS TARGET_POSIX _LINUX _REENTRANT
    _LARGEFILE64_SOURCE _FILE_OFFSET_BITS=64 NOHOLD PLIST_210 PLIST_230
    OPENSSL_API_COMPAT=0x10101000L)
# -UNDEBUG keeps UxPlay's asserts in release builds: some carry side effects,
# e.g. raop_handlers.h `assert(!pairing_session_make_nonce(...))`, and release must behave like
# debug. Placed after CMake's -DNDEBUG in the compile line, so it wins.
target_compile_options(airplay PRIVATE ${COMMON_FLAGS} -w -UNDEBUG)
target_link_libraries(airplay PUBLIC plist crypto)

# ---- Our library -----------------------------------------------------------------------------
add_library(airplayrx SHARED
    jni_bridge.cpp jni_util.cpp server.cpp audio_sink.cpp video_sink.cpp video_stats.cpp)
target_compile_definitions(airplayrx PRIVATE UXPLAY_VERSION="${UXPLAY_VERSION}")
target_compile_options(airplayrx PRIVATE ${COMMON_FLAGS} -Wall -Wextra)
target_include_directories(airplayrx PRIVATE shim)
target_link_libraries(airplayrx airplay android log)
target_link_options(airplayrx PRIVATE -Wl,--gc-sections ${RELEASE_LINK_FLAGS})
