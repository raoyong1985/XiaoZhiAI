cmake_minimum_required(VERSION 3.22.1)

project("native_opus_decoder")

# Include path for opus headers
include_directories(/home/ethan/.pub-cache/hosted/pub.dev/opus_flutter_android-3.0.1/android/libopus/include)
add_library(opus SHARED IMPORTED)
set_target_properties(opus PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI}/libopus.so)

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni)

# Our JNI wrapper
add_library(native_opus_decoder SHARED
    src/main/jni/native_opus_decoder.cpp)

target_link_libraries(native_opus_decoder opus log)
