# SPDX-License-Identifier: AGPL-3.0-or-later
cmake_minimum_required(VERSION 3.22)
project(xpreview_native CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(PDFIUM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/pdfium/${ANDROID_ABI})
if(NOT EXISTS ${PDFIUM_DIR}/lib/libpdfium.so)
  message(FATAL_ERROR "PDFium missing for ${ANDROID_ABI}. Run scripts/fetch-android-deps.sh")
endif()

add_library(pdfium SHARED IMPORTED)
set_target_properties(pdfium PROPERTIES IMPORTED_LOCATION ${PDFIUM_DIR}/lib/libpdfium.so)

add_library(xpreview_pdf SHARED jni_pdfium.cpp)
target_include_directories(xpreview_pdf PRIVATE ${PDFIUM_DIR}/include)
# Hardening (plan 12.1): stack protector, FORTIFY, full RELRO, 16 KB pages.
target_compile_options(xpreview_pdf PRIVATE
  -Wall -Wextra -Werror=return-type -fstack-protector-strong -D_FORTIFY_SOURCE=2 -fvisibility=hidden)
target_link_options(xpreview_pdf PRIVATE
  -Wl,-z,relro -Wl,-z,now -Wl,-z,max-page-size=16384 -Wl,--gc-sections)
target_link_libraries(xpreview_pdf PRIVATE pdfium jnigraphics log)
