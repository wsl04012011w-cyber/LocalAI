#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <atomic>
#include <cctype>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {
struct Session {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    const llama_vocab * vocab = nullptr;
    std::atomic<bool> stop{false};
    std::mutex generation_mutex;
    ~Session() {
        if (context) llama_free(context);
        if (model) llama_model_free(model);
    }
};

std::mutex g_sessions_mutex;
std::unordered_map<jlong, std::shared_ptr<Session>> g_sessions;
std::atomic<jlong> g_next_handle{1};
std::once_flag g_backend_once;

void throw_java(JNIEnv * env, const std::string & message) {
    if (env->ExceptionCheck()) return;
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, message.c_str());
}

std::shared_ptr<Session> get_session(jlong handle) {
    std::lock_guard<std::mutex> guard(g_sessions_mutex);
    auto it = g_sessions.find(handle);
    return it == g_sessions.end() ? nullptr : it->second;
}

bool decode_tokens(Session & session, llama_batch & batch,
                   const std::vector<llama_token> & tokens, int32_t capacity) {
    size_t position = 0;
    while (position < tokens.size()) {
        const int32_t count = static_cast<int32_t>(
            std::min<size_t>(tokens.size() - position, static_cast<size_t>(capacity)));
        batch.n_tokens = count;
        for (int32_t i = 0; i < count; ++i) {
            const size_t token_index = position + static_cast<size_t>(i);
            batch.token[i] = tokens[token_index];
            batch.pos[i] = static_cast<llama_pos>(token_index);
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (token_index == tokens.size() - 1) ? 1 : 0;
        }
        if (llama_decode(session.context, batch) != 0) return false;
        position += static_cast<size_t>(count);
    }
    return true;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_engine_NativeLlamaBridge_nativeLoadModel(
    JNIEnv * env, jobject, jstring j_path, jint context_length, jint batch_size,
    jint threads, jint gpu_layers, jboolean use_mmap, jboolean use_mlock) {
    if (!j_path) {
        throw_java(env, "Caminho do modelo vazio.");
        return 0;
    }
    const char * chars = env->GetStringUTFChars(j_path, nullptr);
    if (!chars) return 0;
    const std::string path(chars);
    env->ReleaseStringUTFChars(j_path, chars);

    try {
        std::call_once(g_backend_once, [] { llama_backend_init(); });
        auto session = std::make_shared<Session>();

        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = std::max(0, gpu_layers);
        if (use_mmap == JNI_TRUE && use_mlock == JNI_TRUE) {
            model_params.load_mode = LLAMA_LOAD_MODE_MMAP_MLOCK;
        } else if (use_mmap == JNI_TRUE) {
            model_params.load_mode = LLAMA_LOAD_MODE_MMAP;
        } else if (use_mlock == JNI_TRUE) {
            model_params.load_mode = LLAMA_LOAD_MODE_MLOCK;
        } else {
            model_params.load_mode = LLAMA_LOAD_MODE_NONE;
        }
        session->model = llama_model_load_from_file(path.c_str(), model_params);
        if (!session->model) {
            throw_java(env, "llama.cpp não abriu o GGUF. Verifique o arquivo e a memória livre.");
            return 0;
        }

        llama_context_params params = llama_context_default_params();
        const uint32_t ctx = static_cast<uint32_t>(std::max(512, context_length));
        const uint32_t batch = static_cast<uint32_t>(
            std::clamp(batch_size, 1, static_cast<int>(ctx)));
        params.n_ctx = ctx;
        params.n_batch = batch;
        params.n_ubatch = std::min<uint32_t>(batch, 256);
        params.n_seq_max = 1;
        params.n_threads = std::max(1, threads);
        params.n_threads_batch = std::max(1, threads);
        session->context = llama_init_from_model(session->model, params);
        if (!session->context) {
            throw_java(env, "llama.cpp não criou o contexto. Reduza o contexto ou use um modelo menor.");
            return 0;
        }
        session->vocab = llama_model_get_vocab(session->model);

        const jlong handle = g_next_handle.fetch_add(1);
        {
            std::lock_guard<std::mutex> guard(g_sessions_mutex);
            g_sessions.emplace(handle, session);
        }
        return handle;
    } catch (const std::exception & error) {
        throw_java(env, error.what());
        return 0;
    } catch (...) {
        throw_java(env, "Erro desconhecido ao carregar o modelo llama.cpp.");
        return 0;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_engine_NativeLlamaBridge_nativeGenerate(
    JNIEnv * env, jobject, jlong handle, jstring j_prompt, jint max_tokens,
    jfloat temperature, jfloat top_p, jint top_k, jfloat repeat_penalty, jobject callback) {
    auto session = get_session(handle);
    if (!session || !session->context || !session->vocab) {
        throw_java(env, "A sessão llama.cpp não está carregada.");
        return 0;
    }
    if (!j_prompt || !callback) {
        throw_java(env, "Prompt ou callback de tokens vazio.");
        return 0;
    }

    std::lock_guard<std::mutex> generation_guard(session->generation_mutex);
    session->stop.store(false);
    const char * chars = env->GetStringUTFChars(j_prompt, nullptr);
    if (!chars) return 0;
    const std::string prompt(chars);
    env->ReleaseStringUTFChars(j_prompt, chars);

    const bool has_bos = prompt.rfind("<|begin_of_text|>", 0) == 0;
    int32_t count = llama_tokenize(session->vocab, prompt.c_str(),
        static_cast<int32_t>(prompt.size()), nullptr, 0, !has_bos, true);
    if (count < 0) count = -count;
    if (count <= 0) {
        throw_java(env, "O prompt não gerou tokens válidos.");
        return 0;
    }

    std::vector<llama_token> prompt_tokens(static_cast<size_t>(count));
    const int32_t actual = llama_tokenize(session->vocab, prompt.c_str(),
        static_cast<int32_t>(prompt.size()), prompt_tokens.data(), count, !has_bos, true);
    if (actual < 0) {
        throw_java(env, "Falha ao tokenizar o prompt.");
        return 0;
    }
    prompt_tokens.resize(static_cast<size_t>(actual));

    const uint32_t ctx_size = llama_n_ctx(session->context);
    if (prompt_tokens.size() >= ctx_size) {
        throw_java(env, "O histórico ultrapassa o contexto. Apague mensagens antigas ou aumente a janela.");
        return 0;
    }
    const int32_t output_limit = static_cast<int32_t>(std::min<size_t>(
        static_cast<size_t>(std::max(1, max_tokens)), ctx_size - prompt_tokens.size()));
    const int32_t batch_capacity = static_cast<int32_t>(
        std::min<uint32_t>(llama_n_batch(session->context), 512));

    llama_batch batch = llama_batch_init(std::max(1, batch_capacity), 0, 1);
    if (!batch.token || !batch.pos || !batch.n_seq_id || !batch.seq_id || !batch.logits) {
        llama_batch_free(batch);
        throw_java(env, "Não foi possível reservar memória para o batch.");
        return 0;
    }
    if (!decode_tokens(*session, batch, prompt_tokens, std::max(1, batch_capacity))) {
        llama_batch_free(batch);
        throw_java(env, "llama.cpp falhou no prompt. Pode haver memória insuficiente.");
        return 0;
    }

    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!sampler) {
        llama_batch_free(batch);
        throw_java(env, "Não foi possível criar o sampler.");
        return 0;
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(
        llama_vocab_n_tokens(session->vocab), 64, std::max(1.0f, repeat_penalty), 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(std::max(1, top_k)));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(std::clamp(top_p, 0.01f, 1.0f), 1));
    if (temperature <= 0.001f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    jclass callback_class = env->GetObjectClass(callback);
    jmethodID on_token = callback_class
        ? env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V") : nullptr;
    if (!on_token) {
        llama_sampler_free(sampler);
        llama_batch_free(batch);
        throw_java(env, "Método de callback de token Kotlin não encontrado.");
        return 0;
    }

    int32_t generated = 0;
    llama_pos next_position = static_cast<llama_pos>(prompt_tokens.size());
    while (generated < output_limit && !session->stop.load()) {
        const llama_token token = llama_sampler_sample(sampler, session->context, -1);
        llama_sampler_accept(sampler, token);
        if (llama_vocab_is_eog(session->vocab, token)) break;

        char piece_buffer[256];
        int32_t piece_size = llama_token_to_piece(
            session->vocab, token, piece_buffer, sizeof(piece_buffer), 0, false);
        std::vector<char> expanded;
        bool use_expanded = false;
        if (piece_size < 0) {
            expanded.resize(static_cast<size_t>(-piece_size));
            piece_size = llama_token_to_piece(session->vocab, token, expanded.data(),
                static_cast<int32_t>(expanded.size()), 0, false);
            use_expanded = true;
        }
        if (piece_size > 0) {
            const char * data = use_expanded ? expanded.data() : piece_buffer;
            jstring j_piece = env->NewStringUTF(
                std::string(data, static_cast<size_t>(piece_size)).c_str());
            if (j_piece) {
                env->CallVoidMethod(callback, on_token, j_piece);
                env->DeleteLocalRef(j_piece);
            }
            if (env->ExceptionCheck()) break;
        }
        ++generated;
        if (generated >= output_limit || session->stop.load() ||
            next_position >= static_cast<llama_pos>(ctx_size)) break;

        batch.n_tokens = 1;
        batch.token[0] = token;
        batch.pos[0] = next_position++;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;
        if (llama_decode(session->context, batch) != 0) {
            llama_sampler_free(sampler);
            llama_batch_free(batch);
            throw_java(env, "llama.cpp falhou ao decodificar o próximo token.");
            return generated;
        }
    }

    llama_sampler_free(sampler);
    llama_batch_free(batch);
    return generated;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_engine_NativeLlamaBridge_nativeDetectThinking(
    JNIEnv *, jobject, jlong handle) {
    auto session = get_session(handle);
    if (!session || !session->model) return 0;
    const char * raw_template = llama_model_chat_template(session->model, nullptr);
    if (!raw_template) return 0;
    std::string chat_template(raw_template);
    std::transform(chat_template.begin(), chat_template.end(), chat_template.begin(),
        [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
    const bool effort = chat_template.find("reasoning_effort") != std::string::npos ||
        chat_template.find("thinking_budget") != std::string::npos ||
        chat_template.find("think_level") != std::string::npos;
    const bool markers = chat_template.find("<think>") != std::string::npos ||
        chat_template.find("</think>") != std::string::npos ||
        chat_template.find("enable_thinking") != std::string::npos;
    return effort ? 2 : (markers ? 1 : 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_engine_NativeLlamaBridge_nativeStop(JNIEnv *, jobject, jlong handle) {
    auto session = get_session(handle);
    if (session) session->stop.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_engine_NativeLlamaBridge_nativeUnloadModel(JNIEnv *, jobject, jlong handle) {
    std::shared_ptr<Session> session;
    {
        std::lock_guard<std::mutex> guard(g_sessions_mutex);
        auto it = g_sessions.find(handle);
        if (it == g_sessions.end()) return;
        session = it->second;
        g_sessions.erase(it);
    }
    session->stop.store(true);
    std::lock_guard<std::mutex> generation_guard(session->generation_mutex);
    if (session->context) {
        llama_free(session->context);
        session->context = nullptr;
    }
    if (session->model) {
        llama_model_free(session->model);
        session->model = nullptr;
    }
}
