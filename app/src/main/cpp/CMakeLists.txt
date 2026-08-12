# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

project("androidcodestudio")

# Add libnode.so as a prebuilt shared library
add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so)

# Include node headers
include_directories(${CMAKE_SOURCE_DIR}/../jniLibs/include/node/)

# Create our native-lib library
add_library(native-lib SHARED native-lib.cpp)

# Link against libnode and log
target_link_libraries(native-lib
    libnode
    android
    log)
