cmake_minimum_required(VERSION 3.22.1)

project(ggequalizer_dsp LANGUAGES CXX)

add_library(
    ggequalizer_dsp
    SHARED
    native-lib.cpp
)

target_compile_features(ggequalizer_dsp PRIVATE cxx_std_17)

target_compile_options(
    ggequalizer_dsp
    PRIVATE
    -Wall
    -Wextra
    -Wpedantic
)

set_target_properties(
    ggequalizer_dsp
    PROPERTIES
    CXX_EXTENSIONS OFF
)
