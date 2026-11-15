#include <jni.h>
#include <dlfcn.h>

#include <cstdint>
#include <cstddef>
#include <mutex>
#include <string>

// llama-android 0.1.1 bundles llama.cpp build b9878. Keep this ABI mirror in
// sync with that pinned build's llama_model_quantize_params declaration.
struct LlamaQuantizeParams {
    int32_t nthread;
    int32_t ftype;
    int32_t output_tensor_type;
    int32_t token_embedding_type;
    bool allow_requantize;
    bool quantize_output_tensor;
    bool only_copy;
    bool pure;
    bool keep_split;
    bool dry_run;
    const void* imatrix;
    const void* kv_overrides;
    const void* tensor_overrides;
    const int32_t* prune_layers;
};

static_assert(offsetof(LlamaQuantizeParams, allow_requantize) == 16);
static_assert(offsetof(LlamaQuantizeParams, imatrix) == 24);
static_assert(sizeof(LlamaQuantizeParams) == 56);

namespace {
using DefaultParamsFn = LlamaQuantizeParams (*)();
using BackendInitFn = void (*)();
using QuantizeFn = uint32_t (*)(const char*, const char*, const LlamaQuantizeParams*);

void* llamaLibrary() {
    static std::once_flag once;
    static void* handle = nullptr;
    std::call_once(once, [] {
        handle = dlopen("libllama.so", RTLD_NOW | RTLD_LOCAL);
    });
    return handle;
}

void throwIllegalState(JNIEnv* env, const std::string& message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message.c_str());
}

int32_t quantizationType(const std::string& value) {
    // llama.cpp build b9878 enum llama_ftype values.
    if (value == "Q4_0") return 2;
    if (value == "Q8_0") return 7;
    if (value == "Q3_K_M") return 12;
    if (value == "Q4_K_S") return 14;
    if (value == "Q4_K_M") return 15;
    if (value == "Q5_K_M") return 17;
    if (value == "Q6_K") return 18;
    if (value == "F16") return 1;
    return -1;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_example_oreshik_ai_GgufQuantizer_quantizeNative(
    JNIEnv* env,
    jclass,
    jstring input_path,
    jstring output_path,
    jstring type,
    jint threads,
    jboolean allow_requantize) {
    if (input_path == nullptr || output_path == nullptr || type == nullptr) {
        throwIllegalState(env, "Quantization requires input, output, and quantization type.");
        return -1;
    }

    const char* input_chars = env->GetStringUTFChars(input_path, nullptr);
    const char* output_chars = env->GetStringUTFChars(output_path, nullptr);
    const char* type_chars = env->GetStringUTFChars(type, nullptr);
    if (input_chars == nullptr || output_chars == nullptr || type_chars == nullptr) {
        if (input_chars != nullptr) env->ReleaseStringUTFChars(input_path, input_chars);
        if (output_chars != nullptr) env->ReleaseStringUTFChars(output_path, output_chars);
        if (type_chars != nullptr) env->ReleaseStringUTFChars(type, type_chars);
        return -1;
    }

    const std::string input(input_chars);
    const std::string output(output_chars);
    const std::string type_name(type_chars);
    env->ReleaseStringUTFChars(input_path, input_chars);
    env->ReleaseStringUTFChars(output_path, output_chars);
    env->ReleaseStringUTFChars(type, type_chars);

    const int32_t ftype = quantizationType(type_name);
    if (ftype < 0) {
        throwIllegalState(env, "Unsupported quantization type: " + type_name);
        return -1;
    }

    void* library = llamaLibrary();
    if (library == nullptr) {
        const char* reason = dlerror();
        throwIllegalState(env, std::string("The bundled llama.cpp engine could not be loaded: ") +
            (reason == nullptr ? "libllama.so is unavailable" : reason));
        return -1;
    }

    auto default_params = reinterpret_cast<DefaultParamsFn>(dlsym(library, "llama_model_quantize_default_params"));
    auto backend_init = reinterpret_cast<BackendInitFn>(dlsym(library, "llama_backend_init"));
    auto quantize = reinterpret_cast<QuantizeFn>(dlsym(library, "llama_model_quantize"));
    if (default_params == nullptr || backend_init == nullptr || quantize == nullptr) {
        throwIllegalState(env, "The bundled llama.cpp engine does not expose its GGUF quantization API.");
        return -1;
    }

    backend_init();
    LlamaQuantizeParams params = default_params();
    params.ftype = ftype;
    params.nthread = threads > 0 ? threads : 1;
    params.allow_requantize = allow_requantize == JNI_TRUE;
    return static_cast<jint>(quantize(input.c_str(), output.c_str(), &params));
}
