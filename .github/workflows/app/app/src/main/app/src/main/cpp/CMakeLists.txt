cmake_minimum_required(VERSION 3.22.1)
project("mysynth")

add_library(${CMAKE_PROJECT_NAME} SHARED native-lib.cpp)

find_package(oboe REQUIRED CONFIG)
target_link_libraries(${CMAKE_PROJECT_NAME} oboe::oboe log)
