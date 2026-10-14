// Picture upscaling on the phone's graphics chip, through ncnn and Vulkan (https://github.com/Tencent/ncnn, BSD-3-Clause).
// One model in ncnn's format (.param + .bin, converted from the same ONNX files by pnnx), a picture four times larger
// out. Input and output are planar RGB floats in 0..1, like the ONNX Runtime bridge (upscale_jni.c), so the app can
// compare the two engines on the same piece and keep the graphics chip only when it gives the same picture.

#include <jni.h>
#include <string.h>
#include <string>
#include <mutex>

#include "net.h"
#include "gpu.h"

static std::string g_error;
static std::mutex g_lock;

static void fail(const std::string &why) { g_error = why; }

// Created once, on first use; never destroyed while the app lives (ncnn's own advice for Android).
static bool gpu_ready() {
    static bool tried = false, ready = false;
    std::lock_guard<std::mutex> guard(g_lock);
    if (!tried) {
        tried = true;
        ready = ncnn::create_gpu_instance() == 0 && ncnn::get_gpu_count() > 0;
        if (!ready) fail("no Vulkan device");
    }
    return ready;
}

extern "C" {

JNIEXPORT jstring JNICALL Java_org_phonemesh_app_NativeGpu_lastError(JNIEnv *env, jclass) {
    return env->NewStringUTF(g_error.c_str());
}

JNIEXPORT jstring JNICALL Java_org_phonemesh_app_NativeGpu_device(JNIEnv *env, jclass) {
    if (!gpu_ready()) return env->NewStringUTF("");
    const ncnn::GpuInfo &info = ncnn::get_gpu_info(ncnn::get_default_gpu_index());
    return env->NewStringUTF(info.device_name());
}

// [vulkan] false runs ncnn on the processor instead: used by the checks to tell a conversion fault from a driver fault.
// [fp16] lets ncnn store and compute in 16-bit floats where the hardware can; false keeps 32-bit throughout.
JNIEXPORT jlong JNICALL Java_org_phonemesh_app_NativeGpu_open(
    JNIEnv *env, jclass, jstring param, jstring bin, jint threads, jboolean vulkan, jboolean fp16) {
    if (vulkan && !gpu_ready()) return 0;
    const char *p = env->GetStringUTFChars(param, nullptr);
    const char *b = env->GetStringUTFChars(bin, nullptr);
    ncnn::Net *net = new ncnn::Net();
    net->opt.use_vulkan_compute = vulkan;
    net->opt.num_threads = threads;
    // fp16 storage and arithmetic where the chip supports them; ncnn checks the device and falls back to fp32.
    net->opt.use_fp16_packed = fp16;
    net->opt.use_fp16_storage = fp16;
    net->opt.use_fp16_arithmetic = fp16;
    net->opt.use_bf16_storage = false;
    bool good = net->load_param(p) == 0;
    if (!good) fail(std::string("cannot read ") + p);
    if (good && net->load_model(b) != 0) { good = false; fail(std::string("cannot read ") + b); }
    env->ReleaseStringUTFChars(param, p);
    env->ReleaseStringUTFChars(bin, b);
    if (!good) { delete net; return 0; }
    return (jlong) (intptr_t) net;
}

JNIEXPORT jfloatArray JNICALL Java_org_phonemesh_app_NativeGpu_run(
    JNIEnv *env, jclass, jlong handle, jfloatArray input, jint height, jint width) {
    ncnn::Net *net = (ncnn::Net *) (intptr_t) handle;
    if (net == nullptr) { fail("no model"); return nullptr; }
    jsize count = env->GetArrayLength(input);
    if (height <= 0 || width <= 0 || count != 3 * height * width) { fail("input is not 3 x height x width"); return nullptr; }
    ncnn::Mat in(width, height, 3);
    jfloat *data = env->GetFloatArrayElements(input, nullptr);
    for (int c = 0; c < 3; c++) memcpy(in.channel(c), data + (size_t) c * width * height, (size_t) width * height * sizeof(float));
    env->ReleaseFloatArrayElements(input, data, JNI_ABORT);
    ncnn::Mat out;
    ncnn::Extractor ex = net->create_extractor();
    if (ex.input("in0", in) != 0 || ex.extract("out0", out) != 0) { fail("the model did not run"); return nullptr; }
    if (out.c != 3 || out.w != width * 4 || out.h != height * 4) { fail("unexpected output size"); return nullptr; }
    size_t plane = (size_t) out.w * out.h;
    jfloatArray result = env->NewFloatArray((jsize) (3 * plane));
    if (result == nullptr) { fail("out of memory"); return nullptr; }
    for (int c = 0; c < 3; c++) env->SetFloatArrayRegion(result, (jsize) (c * plane), (jsize) plane, (const jfloat *) out.channel(c).data);
    return result;
}

JNIEXPORT void JNICALL Java_org_phonemesh_app_NativeGpu_close(JNIEnv *, jclass, jlong handle) {
    delete (ncnn::Net *) (intptr_t) handle;
}

}
