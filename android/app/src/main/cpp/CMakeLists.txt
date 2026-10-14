cmake_minimum_required(VERSION 3.22.1)
project(smartkey_pinyin LANGUAGES CXX)

file(GLOB PINYIN_SOURCES CONFIGURE_DEPENDS "aosp/share/*.cpp")
add_library(smartkey_pinyin SHARED native_pinyin.cpp ${PINYIN_SOURCES})
target_include_directories(smartkey_pinyin PRIVATE aosp/include compat)
target_compile_features(smartkey_pinyin PRIVATE cxx_std_17)
target_compile_options(smartkey_pinyin PRIVATE -Wno-unused-parameter -Wno-deprecated-declarations)
target_link_libraries(smartkey_pinyin log)
# Android 15 supports 16 KB memory pages; align the ELF load segments accordingly.
target_link_options(smartkey_pinyin PRIVATE "-Wl,-z,max-page-size=16384")
