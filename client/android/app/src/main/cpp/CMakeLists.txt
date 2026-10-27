#-----------------------------------------------------------------------------
# Copyright (C) Proxmark3 contributors. See AUTHORS.md for details.
#
# This program is free software: you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation, either version 3 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU General Public License for more details.
#
# See LICENSE.txt for the text of the license.
#-----------------------------------------------------------------------------
# libpm3jni.so: the client library (experimental_lib) plus the JNI bridge.
# Built by Gradle with the NDK. Also builds on desktop Linux against the JDK
# headers, to try the bridge without a phone.
#-----------------------------------------------------------------------------

cmake_minimum_required(VERSION 3.18)
project(pm3jni C CXX)

get_filename_component(PM3_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../.. ABSOLUTE)

# Nothing of this is used by the app; BT, readline and the Qt plot window need a desktop
set(SKIPBT 1)
set(SKIPPYTHON 1)
set(SKIPREADLINE 1)
set(SKIPLINENOISE 1)
set(SKIPGD 1)
set(SKIPJANSSONSYSTEM 1)
set(SKIPWHEREAMISYSTEM 1)
if (ANDROID)
    # pthread is part of bionic
    set(SKIPPTHREAD 1)
endif (ANDROID)

# the static client library ends up inside a shared one
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_subdirectory(${PM3_ROOT}/client/experimental_lib pm3lib)

add_library(pm3jni SHARED pm3_jni.c)
target_include_directories(pm3jni PRIVATE
        ${PM3_ROOT}/common
        ${PM3_ROOT}/include
        ${PM3_ROOT}/client/src
        ${PM3_ROOT}/client/include
        ${PM3_ROOT}/client/deps/jansson
)
target_link_libraries(pm3jni PRIVATE pm3rrg_rdv4_static)

if (ANDROID)
    target_link_libraries(pm3jni PRIVATE log)
else (ANDROID)
    find_package(JNI REQUIRED)
    target_include_directories(pm3jni PRIVATE ${JNI_INCLUDE_DIRS})
endif (ANDROID)
set_property(TARGET pm3jni APPEND_STRING PROPERTY LINK_FLAGS " -Wl,--no-undefined")
