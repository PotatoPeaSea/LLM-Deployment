cmake_minimum_required(VERSION 3.22.1)
project(geniebridge)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Populated by scripts/09_stage_qairt_for_app.sh, not committed.
set(QAIRT_INCLUDE "${CMAKE_CURRENT_SOURCE_DIR}/include")
set(JNILIBS "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

if(NOT EXISTS "${QAIRT_INCLUDE}/Genie/GenieDialog.h")
    message(FATAL_ERROR
        "QAIRT headers not staged. Run scripts/09_stage_qairt_for_app.sh first.")
endif()

# Link against the same libGenie.so that ships in the APK, so there is exactly
# one copy and no version skew between link time and run time.
add_library(Genie SHARED IMPORTED)
set_target_properties(Genie PROPERTIES IMPORTED_LOCATION "${JNILIBS}/libGenie.so")

add_library(geniebridge SHARED genie_bridge.cpp)
target_include_directories(geniebridge PRIVATE "${QAIRT_INCLUDE}")
target_link_libraries(geniebridge Genie log)
