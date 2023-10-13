
# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.22.1)
set(THREADS_PREFER_PTHREAD_FLAG ON)
# Declares and names the project.

project("pdfium")

include_directories(${CMAKE_SOURCE_DIR}/include/)
#add_compile_options(-DHAVE_PTHREADS)
# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.

add_library(pdfium SHARED pdfium.cpp)

# Searches for a specified prebuilt library and stores the path as a
# variable. Because CMake includes system libraries in the search path by
# default, you only need to specify the name of the public NDK library
# you want to add. CMake verifies that the library exists before
# completing its build.
#add_library(libc++ SHARED IMPORTED)
#set_target_properties(libc++ PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/lib/${ANDROID_ABI}/libc++_shared.so)
#add_library(modft2 SHARED IMPORTED)
#set_target_properties(modft2 PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/lib/${ANDROID_ABI}/libmodft2.so)
#add_library(modpdfium SHARED IMPORTED)
#set_target_properties(modpdfium PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/lib/${ANDROID_ABI}/libmodpdfium.so)
#add_library(modpng SHARED IMPORTED)
#set_target_properties(modpng PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/lib/${ANDROID_ABI}/libmodpng.so)

add_library(pdfsdk SHARED IMPORTED)
set_target_properties(pdfsdk PROPERTIES IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/lib/${ANDROID_ABI}/libpdfsdk.so)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(android-lib android)


# Specifies libraries CMake should link to your target library. You
# can link multiple libraries, such as libraries you define in this
# build script, prebuilt third-party libraries, or system libraries.

target_link_libraries(pdfium
        PRIVATE pdfsdk
        ${log-lib}
        ${jnigraphics-lib}
        ${android-lib})
