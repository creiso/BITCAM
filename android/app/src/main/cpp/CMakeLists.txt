cmake_minimum_required(VERSION 3.22.1)
project(bitraw CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(bitraw SHARED bitraw.cpp jni.cpp)
target_compile_options(bitraw PRIVATE -O2 -Wall -Wextra -fno-rtti)
target_link_options(bitraw PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(bitraw jnigraphics)
