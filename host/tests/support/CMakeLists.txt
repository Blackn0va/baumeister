# Testrahmen (doctest-main, Hilfen) – aus kraken, ohne die dortigen Fakes.

add_library(swbh_test_main STATIC test_main.cpp)
add_library(swbh::test_main ALIAS swbh_test_main)
target_link_libraries(swbh_test_main PUBLIC swbh::compile_defaults swbh::doctest)
swbh_strict_warnings(swbh_test_main)
set_target_properties(swbh_test_main PROPERTIES FOLDER "tests")

add_library(swbh_test_support INTERFACE)
add_library(swbh::test_support ALIAS swbh_test_support)
target_include_directories(swbh_test_support INTERFACE "${CMAKE_CURRENT_SOURCE_DIR}")
target_link_libraries(swbh_test_support INTERFACE swbh::doctest swbh::base)
