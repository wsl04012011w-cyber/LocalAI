#include <jni.h>
#include <android/log.h>

#include "llama.h"
#include "chat.h"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <exception>
#include <fstream>
#include <cerrno>
#include <cstring>
#include <sys/stat.h>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

constexpr const char * kLogTag = "LocalAI-llama";
constexpr jint kFinishEos = 0;
constexpr jint kFinishMaxTokens = 1;
constexpr jint kFinishStopped = 2;
constexpr jint kFinishContextFull = 3;

std::once_flag g_backendInit;
std::atomic<jlong> g_stopGenerationId{0};
std::mutex g_logFileMutex;
std::string g_logFilePath;

struct NativeEngine {
    llama_model * model = nullptr;
    std::mutex generationMutex;
};

struct NativeAbortContext { jlong generationId; };

bool abortCallback(void * userData) {
    const auto * abortContext = static_cast<const NativeAbortContext *>(userData);
    return abortContext != nullptr &&
        g_stopGenerationId.load(std::memory_order_relaxed) == abortContext->generationId;
}

void llamaAndroidLog(enum ggml_log_level level, const char * text, void *) {
    int priority = ANDROID_LOG_INFO;
    const char * levelName = "INFO";
    if (level == GGML_LOG_LEVEL_ERROR) { priority = ANDROID_LOG_ERROR; levelName = "ERROR"; }
    else if (level == GGML_LOG_LEVEL_WARN) { priority = ANDROID_LOG_WARN; levelName = "WARN"; }
    else if (level == GGML_LOG_LEVEL_DEBUG) { priority = ANDROID_LOG_DEBUG; levelName = "DEBUG"; }

    const char * message = text == nullptr ? "" : text;
    __android_log_write(priority, kLogTag, message);

    std::lock_guard<std::mutex> lock(g_logFileMutex);
    if (!g_logFilePath.empty()) {
        std::ofstream file(g_logFilePath, std::ios::out | std::ios::app);
        if (file.good()) {
            file << "[LLAMA][" << levelName << "] " << message;
            const size_t length = std::strlen(message);
            if (length > 0 && message[length - 1] != '\n') file << '\n';
        }
    }
}

void logNativeMessage(enum ggml_log_level level, const std::string & message) {
    llamaAndroidLog(level, message.c_str(), nullptr);
}
void throwJava(JNIEnv * env, const char * className, const std::string & message) {
    jclass exceptionClass = env->FindClass(className);
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message.c_str());
        env->DeleteLocalRef(exceptionClass);
    }
}

std::string javaStringToUtf8(JNIEnv * env, jstring value) {
    if (value == nullptr) {
        return {};
    }

    const jsize length = env->GetStringLength(value);
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) {
        return {};
    }

    std::string result;
    result.reserve(static_cast<size_t>(length) * 3);

    for (jsize i = 0; i < length; ++i) {
        uint32_t codePoint = chars[i];
        if (codePoint >= 0xD800 && codePoint <= 0xDBFF && i + 1 < length) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                codePoint = 0x10000 + ((codePoint - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        }

        if (codePoint <= 0x7F) {
            result.push_back(static_cast<char>(codePoint));
        } else if (codePoint <= 0x7FF) {
            result.push_back(static_cast<char>(0xC0 | (codePoint >> 6)));
            result.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
        } else if (codePoint <= 0xFFFF) {
            result.push_back(static_cast<char>(0xE0 | (codePoint >> 12)));
            result.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
        } else {
            result.push_back(static_cast<char>(0xF0 | (codePoint >> 18)));
            result.push_back(static_cast<char>(0x80 | ((codePoint >> 12) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F)));
            result.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
        }
    }

    env->ReleaseStringChars(value, chars);
    return result;
}

std::vector<jchar> utf8ToUtf16(const std::string & value) {
    std::vector<jchar> result;
    result.reserve(value.size());

    size_t i = 0;
    while (i < value.size()) {
        const uint8_t first = static_cast<uint8_t>(value[i]);
        uint32_t codePoint = 0xFFFD;
        size_t count = 1;

        if (first <= 0x7F) {
            codePoint = first;
        } else if (first >= 0xC2 && first <= 0xDF && i + 1 < value.size()) {
            const uint8_t second = static_cast<uint8_t>(value[i + 1]);
            if ((second & 0xC0) == 0x80) {
                codePoint = ((first & 0x1F) << 6) | (second & 0x3F);
                count = 2;
            }
        } else if (first >= 0xE0 && first <= 0xEF && i + 2 < value.size()) {
            const uint8_t second = static_cast<uint8_t>(value[i + 1]);
            const uint8_t third = static_cast<uint8_t>(value[i + 2]);
            const bool valid = (second & 0xC0) == 0x80 && (third & 0xC0) == 0x80 &&
                !(first == 0xE0 && second < 0xA0) &&
                !(first == 0xED && second >= 0xA0);
            if (valid) {
                codePoint = ((first & 0x0F) << 12) |
                    ((second & 0x3F) << 6) | (third & 0x3F);
                count = 3;
            }
        } else if (first >= 0xF0 && first <= 0xF4 && i + 3 < value.size()) {
            const uint8_t second = static_cast<uint8_t>(value[i + 1]);
            const uint8_t third = static_cast<uint8_t>(value[i + 2]);
            const uint8_t fourth = static_cast<uint8_t>(value[i + 3]);
            const bool valid = (second & 0xC0) == 0x80 &&
                (third & 0xC0) == 0x80 && (fourth & 0xC0) == 0x80 &&
                !(first == 0xF0 && second < 0x90) &&
                !(first == 0xF4 && second >= 0x90);
            if (valid) {
                codePoint = ((first & 0x07) << 18) |
                    ((second & 0x3F) << 12) |
                    ((third & 0x3F) << 6) | (fourth & 0x3F);
                count = 4;
            }
        }

        if (codePoint <= 0xFFFF) {
            result.push_back(static_cast<jchar>(codePoint));
        } else if (codePoint <= 0x10FFFF) {
            codePoint -= 0x10000;
            result.push_back(static_cast<jchar>(0xD800 + (codePoint >> 10)));
            result.push_back(static_cast<jchar>(0xDC00 + (codePoint & 0x3FF)));
        } else {
            result.push_back(static_cast<jchar>(0xFFFD));
        }
        i += count;
    }

    return result;
}

jstring newJavaString(JNIEnv * env, const std::string & value) {
    const std::vector<jchar> utf16 = utf8ToUtf16(value);
    return env->NewString(
        utf16.empty() ? nullptr : utf16.data(),
        static_cast<jsize>(utf16.size())
    );
}

size_t completeUtf8Prefix(const std::string & value) {
    size_t cursor = 0;

    while (cursor < value.size()) {
        const uint8_t first = static_cast<uint8_t>(value[cursor]);
        size_t count = 1;

        if (first >= 0xC2 && first <= 0xDF) {
            count = 2;
        } else if (first >= 0xE0 && first <= 0xEF) {
            count = 3;
        } else if (first >= 0xF0 && first <= 0xF4) {
            count = 4;
        }

        if (cursor + count > value.size()) {
            break;
        }

        bool valid = true;
        for (size_t offset = 1; offset < count; ++offset) {
            const uint8_t continuation = static_cast<uint8_t>(value[cursor + offset]);
            if ((continuation & 0xC0) != 0x80) {
                valid = false;
                break;
            }
        }

        if (valid && count == 3) {
            const uint8_t second = static_cast<uint8_t>(value[cursor + 1]);
            valid = !((first == 0xE0 && second < 0xA0) ||
                      (first == 0xED && second >= 0xA0));
        } else if (valid && count == 4) {
            const uint8_t second = static_cast<uint8_t>(value[cursor + 1]);
            valid = !((first == 0xF0 && second < 0x90) ||
                      (first == 0xF4 && second >= 0x90));
        }

        if (valid) {
            cursor += count;
        } else {
            ++cursor;
        }
    }

    return cursor;
}

void invokeError(
    JNIEnv * env,
    jobject callback,
    jmethodID onError,
    const std::string & message
) {
    jstring javaMessage = newJavaString(env, message);
    if (javaMessage == nullptr) {
        return;
    }
    env->CallVoidMethod(callback, onError, javaMessage);
    env->DeleteLocalRef(javaMessage);
}

void invokeToken(
    JNIEnv * env,
    jobject callback,
    jmethodID onToken,
    const std::string & text,
    jint totalTokens,
    jfloat tokensPerSecond
) {
    if (text.empty()) {
        return;
    }
    jstring javaText = newJavaString(env, text);
    if (javaText == nullptr) {
        return;
    }
    env->CallVoidMethod(callback, onToken, javaText, totalTokens, tokensPerSecond);
    env->DeleteLocalRef(javaText);
}

bool appendTokenPiece(
    const llama_vocab * vocab,
    llama_token token,
    std::string & pendingUtf8,
    std::string & completeText
) {
    char smallBuffer[256];
    int32_t pieceSize = llama_token_to_piece(
        vocab, token, smallBuffer, static_cast<int32_t>(sizeof(smallBuffer)), 0, false
    );

    std::vector<char> largeBuffer;
    const char * pieceData = smallBuffer;

    if (pieceSize < 0) {
        const int64_t required = -static_cast<int64_t>(pieceSize);
        if (required <= 0 || required > std::numeric_limits<int32_t>::max()) {
            return false;
        }
        largeBuffer.resize(static_cast<size_t>(required));
        pieceSize = llama_token_to_piece(
            vocab, token, largeBuffer.data(), static_cast<int32_t>(largeBuffer.size()), 0, false
        );
        if (pieceSize < 0) {
            return false;
        }
        pieceData = largeBuffer.data();
    }

    if (pieceSize > 0) {
        pendingUtf8.append(pieceData, static_cast<size_t>(pieceSize));
    }

    const size_t safeLength = completeUtf8Prefix(pendingUtf8);
    if (safeLength > 0) {
        completeText.assign(pendingUtf8.data(), safeLength);
        pendingUtf8.erase(0, safeLength);
    } else {
        completeText.clear();
    }
    return true;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_engine_JniLlamaEngineAdapter_nativeLoadModel(
    JNIEnv * env,
    jobject /* thiz */,
    jstring fileDescriptorPath,
    jint gpuLayers,
    jboolean useMmap,
    jboolean useMlock,
    jstring diagnosticsFilePath
) {
    try {
        {
            std::lock_guard<std::mutex> lock(g_logFileMutex);
            g_logFilePath = javaStringToUtf8(env, diagnosticsFilePath);
        }
        std::call_once(g_backendInit, [] {
            llama_log_set(llamaAndroidLog, nullptr);
            llama_backend_init();
        });

        if (gpuLayers > 0 && !llama_supports_gpu_offload()) {
            throw std::runtime_error("This llama.cpp build has no GPU backend.");
        }

        const std::string path = javaStringToUtf8(env, fileDescriptorPath);
        if (path.empty()) throw std::runtime_error("The GGUF model path is empty.");

        struct stat fileInfo {};
        if (stat(path.c_str(), &fileInfo) != 0) {
            throw std::runtime_error("Cannot access prepared GGUF file: " + std::string(std::strerror(errno)));
        }
        if (!S_ISREG(fileInfo.st_mode) || fileInfo.st_size < 4) {
            throw std::runtime_error("The selected GGUF is not a readable regular file.");
        }
        std::ifstream headerFile(path, std::ios::binary);
        char magic[4] = {};
        headerFile.read(magic, sizeof(magic));
        if (headerFile.gcount() != static_cast<std::streamsize>(sizeof(magic)) ||
            std::memcmp(magic, "GGUF", sizeof(magic)) != 0) {
            throw std::runtime_error("The selected file does not have a valid GGUF header.");
        }

        llama_model_params modelParams = llama_model_default_params();
        modelParams.n_gpu_layers = std::max(0, static_cast<int32_t>(gpuLayers));

        // llama.cpp v1.0.0 replaced use_mmap/use_mlock with load_mode.
        if (useMmap == JNI_TRUE && useMlock == JNI_TRUE) {
            modelParams.load_mode = LLAMA_LOAD_MODE_MMAP_MLOCK;
        } else if (useMmap == JNI_TRUE) {
            modelParams.load_mode = LLAMA_LOAD_MODE_MMAP;
        } else if (useMlock == JNI_TRUE) {
            modelParams.load_mode = LLAMA_LOAD_MODE_MLOCK;
        } else {
            modelParams.load_mode = LLAMA_LOAD_MODE_NONE;
        }

        std::unique_ptr<NativeEngine> engine(new NativeEngine());
        engine->model = llama_model_load_from_file(path.c_str(), modelParams);
        if (engine->model == nullptr) {
            throw std::runtime_error(
                "llama.cpp rejected the GGUF after header validation. Check Logcat (LocalAI-llama) for model, tensor or memory details."
            );
        }

        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception & error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Model load failed: %s", error.what());
        llamaAndroidLog(GGML_LOG_LEVEL_ERROR, error.what(), nullptr);
        throwJava(env, "java/lang/IllegalStateException", error.what());
        return 0;
    } catch (...) {
        throwJava(env, "java/lang/IllegalStateException", "Unknown native model-loading error.");
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_engine_JniLlamaEngineAdapter_nativeFormatPrompt(
    JNIEnv * env,
    jobject /* thiz */,
    jlong handle,
    jobjectArray roles,
    jobjectArray contents
) {
    auto * engine = reinterpret_cast<NativeEngine *>(handle);
    if (engine == nullptr || engine->model == nullptr) {
        throwJava(env, "java/lang/IllegalStateException", "No GGUF model is loaded for Jinja formatting.");
        return nullptr;
    }
    if (roles == nullptr || contents == nullptr ||
        env->GetArrayLength(roles) != env->GetArrayLength(contents)) {
        throwJava(env, "java/lang/IllegalArgumentException", "Chat roles and contents must have matching lengths.");
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(engine->generationMutex);
    try {
        const char * embeddedTemplate = llama_model_chat_template(engine->model, nullptr);
        if (embeddedTemplate == nullptr || embeddedTemplate[0] == 0) {
            throw std::runtime_error("This GGUF does not contain tokenizer.chat_template.");
        }

        const jsize messageCount = env->GetArrayLength(roles);
        common_chat_templates_ptr templates = common_chat_templates_init(engine->model, "");
        common_chat_templates_inputs inputs;
        inputs.use_jinja = true;
        inputs.add_generation_prompt = true;
        inputs.messages.reserve(static_cast<size_t>(messageCount));

        for (jsize i = 0; i < messageCount; ++i) {
            auto roleObject = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
            auto contentObject = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
            if (roleObject == nullptr || contentObject == nullptr) {
                if (roleObject != nullptr) env->DeleteLocalRef(roleObject);
                if (contentObject != nullptr) env->DeleteLocalRef(contentObject);
                throw std::runtime_error("Chat message role or content is null.");
            }
            common_chat_msg message;
            message.role = javaStringToUtf8(env, roleObject);
            message.content = javaStringToUtf8(env, contentObject);
            env->DeleteLocalRef(roleObject);
            env->DeleteLocalRef(contentObject);
            if (env->ExceptionCheck()) return nullptr;
            inputs.messages.emplace_back(std::move(message));
        }

        common_chat_params formatted = common_chat_templates_apply(templates.get(), inputs);
        const std::string prompt = formatted.prompt.str();
        if (prompt.empty()) throw std::runtime_error("The GGUF Jinja template rendered an empty prompt.");
        return newJavaString(env, prompt);
    } catch (const std::exception & error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Jinja template failed: %s", error.what());
        llamaAndroidLog(GGML_LOG_LEVEL_ERROR, error.what(), nullptr);
        throwJava(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    } catch (...) {
        llamaAndroidLog(GGML_LOG_LEVEL_ERROR, "Unknown GGUF Jinja template error.", nullptr);
        throwJava(env, "java/lang/IllegalStateException", "Unknown GGUF Jinja template error.");
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_engine_JniLlamaEngineAdapter_nativeFreeModel(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong handle
) {
    auto * engine = reinterpret_cast<NativeEngine *>(handle);
    if (engine == nullptr) {
        return;
    }

    std::lock_guard<std::mutex> lock(engine->generationMutex);
    if (engine->model != nullptr) {
        llama_model_free(engine->model);
        engine->model = nullptr;
    }
    delete engine;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_engine_JniLlamaEngineAdapter_nativeRequestStop(
    JNIEnv * /* env */,
    jobject /* thiz */,
    jlong generationId
) {
    if (generationId > 0) {
        g_stopGenerationId.store(generationId, std::memory_order_relaxed);
        logNativeMessage(GGML_LOG_LEVEL_INFO, "Stop requested for generation " + std::to_string(static_cast<long long>(generationId)));
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_engine_JniLlamaEngineAdapter_nativeSupportsGpuOffload(
    JNIEnv * /* env */,
    jobject /* thiz */
) {
    return llama_supports_gpu_offload() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_engine_JniLlamaEngineAdapter_nativeGenerate(
    JNIEnv * env,
    jobject /* thiz */,
    jlong handle,
    jlong generationId,
    jstring promptValue,
    jint contextLength,
    jint batchSize,
    jint threads,
    jint kvCacheType,
    jint maxTokens,
    jfloat temperature,
    jfloat topP,
    jint topK,
    jfloat repeatPenalty,
    jobject callback
) {
    auto * engine = reinterpret_cast<NativeEngine *>(handle);
    if (engine == nullptr || engine->model == nullptr) {
        throwJava(env, "java/lang/IllegalStateException", "The native model handle is invalid.");
        return;
    }
    if (callback == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", "The generation callback is missing.");
        return;
    }

    jclass callbackClass = env->GetObjectClass(callback);
    if (callbackClass == nullptr) {
        return;
    }

    const jmethodID onStarted = env->GetMethodID(callbackClass, "onStarted", "(IJ)V");
    const jmethodID onToken = env->GetMethodID(
        callbackClass, "onToken", "(Ljava/lang/String;IF)V"
    );
    const jmethodID onCompleted = env->GetMethodID(callbackClass, "onCompleted", "(IIFJ)V");
    const jmethodID onError = env->GetMethodID(
        callbackClass, "onError", "(Ljava/lang/String;)V"
    );
    env->DeleteLocalRef(callbackClass);

    if (onStarted == nullptr || onToken == nullptr ||
        onCompleted == nullptr || onError == nullptr || env->ExceptionCheck()) {
        return;
    }

    std::lock_guard<std::mutex> generationLock(engine->generationMutex);
    logNativeMessage(GGML_LOG_LEVEL_INFO, "Generation " + std::to_string(static_cast<long long>(generationId)) + " started");

    const auto reportError = [&](const std::string & message) {
        invokeError(env, callback, onError, message);
    };

    try {
        const std::string prompt = javaStringToUtf8(env, promptValue);
        if (prompt.empty()) {
            throw std::runtime_error("The formatted prompt is empty.");
        }
        if (prompt.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
            throw std::runtime_error("The formatted prompt is too large to tokenize.");
        }

        const uint32_t safeContext = static_cast<uint32_t>(
            std::max(512, static_cast<int>(contextLength))
        );
        const uint32_t safeBatch = static_cast<uint32_t>(
            std::clamp(static_cast<int>(batchSize), 1, static_cast<int>(safeContext))
        );
        const int32_t safeThreads = std::max(1, static_cast<int32_t>(threads));
        const int32_t safeMaxTokens = std::max(1, static_cast<int32_t>(maxTokens));
        const uint32_t safeMicroBatch = std::min<uint32_t>(64, safeBatch);
        logNativeMessage(GGML_LOG_LEVEL_INFO,
            "Generation " + std::to_string(static_cast<long long>(generationId)) +
            " params: n_ctx=" + std::to_string(safeContext) +
            " n_batch=" + std::to_string(safeBatch) +
            " n_ubatch=" + std::to_string(safeMicroBatch) +
            " threads=" + std::to_string(safeThreads) +
            " max_tokens=" + std::to_string(safeMaxTokens));

        llama_context_params contextParams = llama_context_default_params();
        contextParams.n_ctx = safeContext;
        contextParams.n_batch = safeBatch;
        contextParams.n_ubatch = safeMicroBatch;
        contextParams.n_threads = safeThreads;
        contextParams.n_threads_batch = safeThreads;
        contextParams.type_k = static_cast<ggml_type>(kvCacheType);
        contextParams.type_v = static_cast<ggml_type>(kvCacheType);
        NativeAbortContext abortContext{generationId};
        contextParams.abort_callback = abortCallback;
        contextParams.abort_callback_data = &abortContext;
        contextParams.no_perf = false;

        std::unique_ptr<llama_context, decltype(&llama_free)> inferenceContext(
            llama_init_from_model(engine->model, contextParams),
            &llama_free
        );
        if (inferenceContext == nullptr) {
            throw std::runtime_error(
                "Could not create the inference context. Try lowering context length or KV cache size."
            );
        }

        const llama_vocab * vocab = llama_model_get_vocab(engine->model);
        if (vocab == nullptr) {
            throw std::runtime_error("The loaded model does not expose a tokenizer vocabulary.");
        }

        const int32_t requiredTokens = llama_tokenize(
            vocab,
            prompt.data(),
            static_cast<int32_t>(prompt.size()),
            nullptr,
            0,
            true,
            true
        );
        if (requiredTokens == std::numeric_limits<int32_t>::min()) {
            throw std::runtime_error("Prompt tokenization overflowed.");
        }
        const int32_t promptTokenCount = requiredTokens < 0 ? -requiredTokens : requiredTokens;
        logNativeMessage(GGML_LOG_LEVEL_INFO,
            "Generation " + std::to_string(static_cast<long long>(generationId)) +
            " prompt tokenized: " + std::to_string(promptTokenCount) + " tokens");
        if (promptTokenCount <= 0) {
            throw std::runtime_error("The formatted prompt did not produce any tokens.");
        }
        if (static_cast<uint32_t>(promptTokenCount) >= safeContext) {
            throw std::runtime_error(
                "Context window exceeded: the prompt already fills the configured context."
            );
        }

        std::vector<llama_token> promptTokens(static_cast<size_t>(promptTokenCount));
        const int32_t tokenizedCount = llama_tokenize(
            vocab,
            prompt.data(),
            static_cast<int32_t>(prompt.size()),
            promptTokens.data(),
            static_cast<int32_t>(promptTokens.size()),
            true,
            true
        );
        if (tokenizedCount < 0 || tokenizedCount != promptTokenCount) {
            throw std::runtime_error("Failed to tokenize the formatted prompt.");
        }

        std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)> batch(
            llama_batch_ext_init(inferenceContext.get()),
            &llama_batch_ext_free
        );
        if (batch == nullptr) {
            throw std::runtime_error("Could not allocate the llama.cpp token batch.");
        }

        // Prefill the prompt in n_batch-sized chunks.
        const int64_t promptEvalStartUs = llama_time_us();
        for (int32_t offset = 0; offset < promptTokenCount;) {
            if (g_stopGenerationId.load(std::memory_order_relaxed) == generationId) {
                logNativeMessage(GGML_LOG_LEVEL_WARN,
                    "Generation " + std::to_string(static_cast<long long>(generationId)) +
                    " interrupted before prompt prefill completed; no output tokens generated.");
                env->CallVoidMethod(
                    callback, onCompleted, kFinishStopped, 0, 0.0f, static_cast<jlong>(0)
                );
                return;
            }

            const int32_t chunkSize = std::min(
                static_cast<int32_t>(safeBatch),
                promptTokenCount - offset
            );
            llama_batch_ext_clear(batch.get());

            for (int32_t i = 0; i < chunkSize; ++i) {
                const int32_t batchIndex = llama_batch_ext_add_token(
                    batch.get(), 0, promptTokens[static_cast<size_t>(offset + i)]
                );
                if (batchIndex < 0) {
                    throw std::runtime_error("The prompt batch is full or contains an invalid token.");
                }

                const llama_pos position = static_cast<llama_pos>(offset + i);
                if (!llama_batch_ext_set_pos(batch.get(), batchIndex, &position)) {
                    throw std::runtime_error("Could not set the prompt token position.");
                }

                const bool isFinalPromptToken = offset + i == promptTokenCount - 1;
                if (isFinalPromptToken &&
                    !llama_batch_ext_set_output_logits(batch.get(), batchIndex, true)) {
                    throw std::runtime_error("Could not enable logits for the final prompt token.");
                }
            }

            const int32_t decodeResult = llama_process(
                inferenceContext.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get()
            );
            if (decodeResult != 0) {
                if (g_stopGenerationId.load(std::memory_order_relaxed) == generationId) {
                    logNativeMessage(GGML_LOG_LEVEL_WARN,
                        "Generation " + std::to_string(static_cast<long long>(generationId)) +
                        " interrupted during prompt decode; decode_result=" + std::to_string(decodeResult));
                    env->CallVoidMethod(
                        callback, onCompleted, kFinishStopped, 0, 0.0f, static_cast<jlong>(0)
                    );
                    return;
                }
                throw std::runtime_error(
                    "llama.cpp failed while evaluating the prompt (decode code " +
                    std::to_string(decodeResult) + ")."
                );
            }
            offset += chunkSize;
        }

        const int64_t promptEvalMs = std::max<int64_t>(
            0, (llama_time_us() - promptEvalStartUs) / 1000
        );
        logNativeMessage(GGML_LOG_LEVEL_INFO,
            "Generation " + std::to_string(static_cast<long long>(generationId)) +
            " prompt evaluation complete: " + std::to_string(promptTokenCount) +
            " tokens in " + std::to_string(static_cast<long long>(promptEvalMs)) + " ms");
        env->CallVoidMethod(
            callback,
            onStarted,
            static_cast<jint>(promptTokenCount),
            static_cast<jlong>(promptEvalMs)
        );
        if (env->ExceptionCheck()) {
            return;
        }

        llama_sampler_chain_params samplerParams = llama_sampler_chain_default_params();
        samplerParams.no_perf = false;
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_chain_init(samplerParams),
            &llama_sampler_free
        );
        if (sampler == nullptr) {
            throw std::runtime_error("Could not create the token sampler.");
        }

        // Reduce the candidate set before applying repetition penalties to it.
        llama_sampler_chain_add(
            sampler.get(), llama_sampler_init_top_k(std::max(1, static_cast<int>(topK)))
        );
        llama_sampler_chain_add(
            sampler.get(),
            llama_sampler_init_penalties(
                llama_vocab_n_tokens(vocab),
                std::min(static_cast<int32_t>(safeContext), 256),
                std::max(1.0f, repeatPenalty),
                0.0f,
                0.0f
            )
        );

        // Seed the repetition-penalty history with tokens already present in the prompt.
        for (const llama_token promptToken : promptTokens) {
            llama_sampler_accept(sampler.get(), promptToken);
        }

        if (temperature <= 0.0f) {
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        } else {
            llama_sampler_chain_add(
                sampler.get(), llama_sampler_init_top_p(
                    std::clamp(topP, 0.01f, 1.0f), 1
                )
            );
            llama_sampler_chain_add(
                sampler.get(), llama_sampler_init_temp(std::max(0.01f, temperature))
            );
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
        }

        int32_t generatedTokens = 0;
        jint finishReason = kFinishEos;
        std::string pendingUtf8;
        const int64_t generationStartUs = llama_time_us();

        while (generatedTokens < safeMaxTokens) {
            if (g_stopGenerationId.load(std::memory_order_relaxed) == generationId) {
                finishReason = kFinishStopped;
                break;
            }

            const llama_token nextToken = llama_sampler_sample(sampler.get(), inferenceContext.get(), -1);
            if (llama_vocab_is_eog(vocab, nextToken)) {
                finishReason = kFinishEos;
                break;
            }

            std::string completePiece;
            if (!appendTokenPiece(vocab, nextToken, pendingUtf8, completePiece)) {
                throw std::runtime_error("Failed to decode a generated token into UTF-8.");
            }

            ++generatedTokens;
            const int64_t elapsedUs = std::max<int64_t>(1, llama_time_us() - generationStartUs);
            const float tokensPerSecond =
                static_cast<float>(generatedTokens) * 1000000.0f / static_cast<float>(elapsedUs);
            invokeToken(
                env,
                callback,
                onToken,
                completePiece,
                static_cast<jint>(generatedTokens),
                tokensPerSecond
            );
            if (env->ExceptionCheck()) {
                g_stopGenerationId.store(generationId, std::memory_order_relaxed);
                return;
            }

            if (generatedTokens >= safeMaxTokens) {
                finishReason = kFinishMaxTokens;
                break;
            }
            if (promptTokenCount + generatedTokens >= static_cast<int32_t>(safeContext)) {
                finishReason = kFinishContextFull;
                break;
            }

            // Feed the sampled token back into the context to obtain the next logits.
            llama_batch_ext_clear(batch.get());
            const int32_t batchIndex = llama_batch_ext_add_token(batch.get(), 0, nextToken);
            if (batchIndex < 0) {
                throw std::runtime_error("Could not append the generated token to the decode batch.");
            }
            const llama_pos position = static_cast<llama_pos>(
                promptTokenCount + generatedTokens - 1
            );
            if (!llama_batch_ext_set_pos(batch.get(), batchIndex, &position) ||
                !llama_batch_ext_set_output_logits(batch.get(), batchIndex, true)) {
                throw std::runtime_error("Could not configure the next-token decode batch.");
            }

            const int32_t decodeResult = llama_process(
                inferenceContext.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get()
            );
            if (decodeResult != 0) {
                if (g_stopGenerationId.load(std::memory_order_relaxed) == generationId) {
                    finishReason = kFinishStopped;
                    break;
                }
                throw std::runtime_error(
                    "llama.cpp failed during token generation (decode code " +
                    std::to_string(decodeResult) + ")."
                );
            }
        }

        if (!pendingUtf8.empty()) {
            invokeToken(
                env,
                callback,
                onToken,
                "\xEF\xBF\xBD",
                static_cast<jint>(generatedTokens),
                0.0f
            );
        }

        const int64_t durationMs = std::max<int64_t>(
            1, (llama_time_us() - generationStartUs) / 1000
        );
        const float finalTokensPerSecond = static_cast<float>(generatedTokens) * 1000.0f /
            static_cast<float>(durationMs);

        logNativeMessage(GGML_LOG_LEVEL_INFO,
            "Generation " + std::to_string(static_cast<long long>(generationId)) +
            " completed: finish=" + std::to_string(finishReason) +
            " tokens=" + std::to_string(generatedTokens) +
            " duration=" + std::to_string(static_cast<long long>(durationMs)) + " ms");
        env->CallVoidMethod(
            callback,
            onCompleted,
            finishReason,
            static_cast<jint>(generatedTokens),
            finalTokensPerSecond,
            static_cast<jlong>(durationMs)
        );
    } catch (const std::exception & error) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Generation failed: %s", error.what());
        llamaAndroidLog(GGML_LOG_LEVEL_ERROR, error.what(), nullptr);
        reportError(error.what());
    } catch (...) {
        llamaAndroidLog(GGML_LOG_LEVEL_ERROR, "Unknown native inference error.", nullptr);
        reportError("Unknown native inference error.");
    }
}
