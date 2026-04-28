cmake_minimum_required(VERSION 3.22.1)

project("native_opus_decoder")

# Include path for opus headers (opus.h is in opus/ subdir)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/include)

# Import prebuilt opus library
add_library(opus SHARED IMPORTED)
set_target_properties(opus PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}/libopus.so
)

# Our JNI wrapper
add_library(native_opus_decoder SHARED
    src/main/jni/native_opus_decoder.cpp)

target_link_libraries(native_opus_decoder opus log)
