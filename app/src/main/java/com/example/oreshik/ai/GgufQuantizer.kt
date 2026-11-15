package com.example.oreshik.ai

/** Native adapter to llama.cpp's quantization API exposed by the bundled llama-android AAR. */
object GgufQuantizer {
    init {
        System.loadLibrary("gguf_quantizer")
    }

    @JvmStatic
    external fun quantizeNative(
        inputPath: String,
        outputPath: String,
        type: String,
        threads: Int,
        allowRequantize: Boolean,
    ): Int
}
