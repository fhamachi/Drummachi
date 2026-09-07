# v5.5: build do libdrummachi.so a partir do engine.cpp, usando o Oboe via prefab
# (com.google.oboe:oboe:1.10.0). ATIVADO APENAS no CI x86_64 com -PbuildNative=1.
# O build de dev local (aarch64) NÃO usa este CMake — continua com o .so
# precompilado em app/src/main/jniLibs/arm64-v8a/.
cmake_minimum_required(VERSION 3.22)
project(drummachi LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(oboe CONFIG REQUIRED)

add_library(drummachi SHARED
    engine.cpp
)

target_include_directories(drummachi PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(drummachi oboe::oboe log)

set_target_properties(drummachi PROPERTIES
    OUTPUT_NAME drummachi
    POSITION_INDEPENDENT_CODE ON
)