cmake_minimum_required(VERSION 3.22.1)
project(phonemesh_upscale C)

# Pictures: a bridge to sherpa-onnx's own libonnxruntime.so, found at run time (dlopen), so nothing is linked to it.
add_library(phonemesh_upscale SHARED upscale_jni.c)
target_include_directories(phonemesh_upscale PRIVATE include)
target_compile_options(phonemesh_upscale PRIVATE -O2 -Wall -Werror -fvisibility=hidden)
target_link_libraries(phonemesh_upscale PRIVATE dl)
# 16 KB pages (Android 15+): keep the library loadable on such phones.
target_link_options(phonemesh_upscale PRIVATE "-Wl,-z,max-page-size=16384")
