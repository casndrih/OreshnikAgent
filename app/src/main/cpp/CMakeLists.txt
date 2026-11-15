cmake_minimum_required(VERSION 3.22.1)
project(gguf_quantizer LANGUAGES CXX)

add_library(gguf_quantizer SHARED gguf_quantizer.cpp)
target_compile_features(gguf_quantizer PRIVATE cxx_std_17)
target_compile_options(gguf_quantizer PRIVATE -Wall -Wextra -Werror=return-type)
target_link_libraries(gguf_quantizer PRIVATE ${CMAKE_DL_LIBS} log)
