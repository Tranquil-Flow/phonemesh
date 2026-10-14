// Phonemesh picture upscaling: a small bridge from Kotlin to the ONNX Runtime that sherpa-onnx already ships
// (libonnxruntime.so, 1.28). Nothing is linked: the library is found at run time, after Kotlin has loaded it.
// One model file in, one picture out (NCHW float RGB in 0..1, output 4x). Runs on the caller's thread.
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "onnxruntime_c_api.h"

static const OrtApi *g_api = NULL;
static OrtEnv *g_env = NULL;
static char g_error[512] = "";

static int fail(const char *message) {
    strncpy(g_error, message ? message : "unknown error", sizeof g_error - 1);
    g_error[sizeof g_error - 1] = 0;
    return 0;
}

static int ok(OrtStatus *status) {
    if (status == NULL) return 1;
    fail(g_api->GetErrorMessage(status));
    g_api->ReleaseStatus(status);
    return 0;
}

static int ready(void) {
    if (g_api != NULL && g_env != NULL) return 1;
    void *lib = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_GLOBAL);
    if (lib == NULL) return fail(dlerror());
    const OrtApiBase *(*base)(void) = (const OrtApiBase *(*)(void)) dlsym(lib, "OrtGetApiBase");
    if (base == NULL) return fail("OrtGetApiBase not found");
    g_api = base()->GetApi(ORT_API_VERSION);
    if (g_api == NULL) return fail("this ONNX Runtime is older than the bridge expects");
    return ok(g_api->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "phonemesh-upscale", &g_env));
}

/*
 * engine 0: ONNX Runtime's own CPU kernels with [threads] threads.
 * engine 1: the XNNPACK kernels built into the same runtime, with [threads] threads of their own; ONNX Runtime's pool
 * then gets one thread, as its documentation advises, and runs only what XNNPACK cannot.
 */
JNIEXPORT jlong JNICALL Java_org_phonemesh_app_NativeUpscale_open(JNIEnv *env, jclass type, jstring path, jint threads, jint engine) {
    if (!ready()) return 0;
    const char *file = (*env)->GetStringUTFChars(env, path, NULL);
    OrtSessionOptions *options = NULL;
    OrtSession *session = NULL;
    int good = ok(g_api->CreateSessionOptions(&options));
    if (good) good = ok(g_api->SetIntraOpNumThreads(options, engine == 1 ? 1 : threads));
    if (good) good = ok(g_api->SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL));
    if (good && engine == 1) {
        char count[8];
        snprintf(count, sizeof(count), "%d", threads);
        const char *keys[] = {"intra_op_num_threads"};
        const char *values[] = {count};
        good = ok(g_api->AddSessionConfigEntry(options, "session.intra_op.allow_spinning", "0"));
        if (good) good = ok(g_api->SessionOptionsAppendExecutionProvider(options, "XNNPACK", keys, values, 1));
    }
    if (good) good = ok(g_api->CreateSession(g_env, file, options, &session));
    if (options != NULL) g_api->ReleaseSessionOptions(options);
    (*env)->ReleaseStringUTFChars(env, path, file);
    return good ? (jlong) (intptr_t) session : 0;
}

JNIEXPORT jfloatArray JNICALL Java_org_phonemesh_app_NativeUpscale_run(
    JNIEnv *env, jclass type, jlong handle, jfloatArray input, jint height, jint width) {
    OrtSession *session = (OrtSession *) (intptr_t) handle;
    if (session == NULL || g_api == NULL) { fail("no model"); return NULL; }
    jsize count = (*env)->GetArrayLength(env, input);
    if (height <= 0 || width <= 0 || count != 3 * height * width) { fail("input is not 3 x height x width"); return NULL; }
    jfloat *data = (*env)->GetFloatArrayElements(env, input, NULL);
    OrtMemoryInfo *memory = NULL;
    OrtValue *in = NULL, *out = NULL;
    OrtTensorTypeAndShapeInfo *shape = NULL;
    jfloatArray result = NULL;
    int64_t dims[4] = {1, 3, height, width};
    const char *inputs[] = {"input"};
    const char *outputs[] = {"output"};
    int good = ok(g_api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memory));
    if (good) good = ok(g_api->CreateTensorWithDataAsOrtValue(memory, data, (size_t) count * sizeof(float), dims, 4,
        ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &in));
    if (good) good = ok(g_api->Run(session, NULL, inputs, (const OrtValue *const *) &in, 1, outputs, 1, &out));
    size_t produced = 0;
    float *pixels = NULL;
    if (good) good = ok(g_api->GetTensorTypeAndShape(out, &shape));
    if (good) good = ok(g_api->GetTensorShapeElementCount(shape, &produced));
    if (good) good = ok(g_api->GetTensorMutableData(out, (void **) &pixels));
    if (good && produced != (size_t) count * 16) good = fail("the model did not make the picture 4 times larger");
    if (good) {
        result = (*env)->NewFloatArray(env, (jsize) produced);
        if (result != NULL) (*env)->SetFloatArrayRegion(env, result, 0, (jsize) produced, pixels);
    }
    if (shape != NULL) g_api->ReleaseTensorTypeAndShapeInfo(shape);
    if (out != NULL) g_api->ReleaseValue(out);
    if (in != NULL) g_api->ReleaseValue(in);
    if (memory != NULL) g_api->ReleaseMemoryInfo(memory);
    (*env)->ReleaseFloatArrayElements(env, input, data, JNI_ABORT);
    return result;
}

JNIEXPORT void JNICALL Java_org_phonemesh_app_NativeUpscale_close(JNIEnv *env, jclass type, jlong handle) {
    if (handle != 0 && g_api != NULL) g_api->ReleaseSession((OrtSession *) (intptr_t) handle);
}

JNIEXPORT jstring JNICALL Java_org_phonemesh_app_NativeUpscale_lastError(JNIEnv *env, jclass type) {
    return (*env)->NewStringUTF(env, g_error);
}
