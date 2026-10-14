cmake_minimum_required(VERSION 3.22.1)
project(phonemesh_upscale C CXX)

# Pictures: a bridge to sherpa-onnx's own libonnxruntime.so, found at run time (dlopen), so nothing is linked to it.
add_library(phonemesh_upscale SHARED upscale_jni.c)
target_include_directories(phonemesh_upscale PRIVATE include)
target_compile_options(phonemesh_upscale PRIVATE -O2 -Wall -Werror -fvisibility=hidden)
target_link_libraries(phonemesh_upscale PRIVATE dl)
# 16 KB pages (Android 15+): keep the library loadable on such phones.
target_link_options(phonemesh_upscale PRIVATE "-Wl,-z,max-page-size=16384")

# Pictures on the graphics chip: ncnn with Vulkan (prebuilt release 20260526, BSD-3-Clause, in ncnn/<abi>/).
# The prebuilt library is imported, so the Android build packages it beside the bridge.
set(NCNN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/ncnn/${ANDROID_ABI})
add_library(ncnn SHARED IMPORTED)
set_target_properties(ncnn PROPERTIES IMPORTED_LOCATION ${NCNN_DIR}/lib/libncnn.so)
add_library(phonemesh_gpu SHARED upscale_gpu.cpp)
target_include_directories(phonemesh_gpu PRIVATE ${NCNN_DIR}/include/ncnn)
target_compile_options(phonemesh_gpu PRIVATE -O2 -Wall -Werror -fvisibility=hidden -fno-rtti -fno-exceptions)
target_link_libraries(phonemesh_gpu PRIVATE ncnn)
target_link_options(phonemesh_gpu PRIVATE "-Wl,-z,max-page-size=16384")
