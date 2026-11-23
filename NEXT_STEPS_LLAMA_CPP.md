# Guia de Integração NDK & `llama.cpp` — Próximos Passos (Handoff)

Este documento descreve **exatamente o que já está estruturado** e **o que falta implementar** para conectar a engine nativa `llama.cpp`, compilar os binários C++ via CMake/NDK e rodar inferência GGUF real diretamente no dispositivo Android.

---

## 1. O que já está pronto no projeto

1. **Contrato da Engine (`app/src/main/java/com/example/engine/LocalInferenceEngine.kt`)**
   - `EngineLifecycleState`: `UNLOADED`, `LOADING_MODEL`, `READY`, `GENERATING`, `ERROR`.
   - `InferenceStreamEvent`: `Started`, `Token`, `Completed`, `Error`.
   - `LocalInferenceEngine`: interface desacoplada consumida pelo `LlamaChatViewModel`.
   - `StubLlamaEngineAdapter`: harness atual em Kotlin puro (sem rede/API) para testar streaming, cancelamento e propagação de configuração.

2. **Configurações Seletivas com Limites (`app/src/main/java/com/example/domain/model/ChatModels.kt` & `LlamaChatViewModel.kt`)**
   - `n_ctx` limitado por `model.maxContextLength` (`512..8192`).
   - `n_batch` limitado por `n_batch <= n_ctx`.
   - `maxTokens` limitado por `maxTokens <= n_ctx`.
   - `n_threads` limitado dinamicamente por `1..deviceMemory.cpuCores`.
   - `n_gpu_layers` limitado em `0..99`.
   - `kvCacheQuant` (`F16`, `Q8_0`, `Q4_0`), `useMmap`, `useMlock`, `temperature`, `topP`, `topK`, `repeatPenalty`.

3. **Serialização de Prompt (`app/src/main/java/com/example/engine/PromptFormatter.kt`)**
   - Já formata o histórico + `systemPrompt` nos templates `LLAMA3`, `CHATML`, `GEMMA` e `PHI3`.
   - *Nota*: Ao integrar `llama.cpp`, prefira usar `llama_chat_apply_template()` nativo lendo o chat template embutido nos metadados GGUF quando disponível, usando `PromptFormatter` como fallback.

4. **Download e Seleção de Modelos GGUF**
   - `GgufRepository.kt`: contém links reais verificados do Hugging Face (`.gguf`).
   - `ModelDownloadDispatcher.kt`: usa `android.app.DownloadManager` nativo para salvar os arquivos diretamente em `Downloads/` do aparelho.
   - `DeviceHardwareInspector.kt`: lê metadados de arquivos `.gguf` selecionados via Storage Access Framework (`ActivityResultContracts.OpenDocument`) retornando `uriString` (`content://...`).

---

## 2. O que falta implementar (Passo a Passo para o Próximo Modelo/Engenheiro)

### Etapa A — Adicionar `llama.cpp` + CMake + NDK no Gradle

1. Adicione o código-fonte do `llama.cpp` como submódulo Git ou diretório nativo em `app/src/main/cpp/llama.cpp`.
2. Em `app/build.gradle.kts`, dentro de `android { ... }`, configure o NDK e o CMake:

```kotlin
android {
    defaultConfig {
        ndk {
            // Foque em arm64-v8a para performance NEON/dotprod/i8mm em dispositivos reais
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_CURL=OFF",
                    "-DGGML_OPENMP=OFF"
                )
                cppFlags += listOf("-O3", "-fexceptions", "-frtti")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
```

3. Crie `app/src/main/cpp/CMakeLists.txt` compilando `llama` / `ggml` junto com a ponte JNI (`llama_jni.cpp`) e linkando `android` e `log`:
   - Para CPUs ARM modernas, habilite otimizações `march=armv8.2-a+dotprod` (e opcionalmente `+i8mm` / `+fp16` verificando compatibilidade de runtime com `ggml-cpu`).
   - Se quiser offload de GPU (`n_gpu_layers > 0`), ative `-DGGML_VULKAN=ON` (requer NDK r26+ e headers Vulkan) ou `-DGGML_OPENCL=ON` para Adreno.

---

### Etapa B — Resolver URI `content://` (SAF) para File Descriptor / Path no C++

Como o usuário seleciona `.gguf` via `OpenDocument` (`content://...`), o C++ não abre URIs diretamente com `fopen()`. Existem duas abordagens:

1. **Via File Descriptor (`/proc/self/fd/<fd>`) — Recomendado (Zero Cópia, compatível com `mmap`)**:
   - No Kotlin, antes de chamar a função JNI `nativeLoadModel(...)`:
     ```kotlin
     val pfd = context.contentResolver.openFileDescriptor(Uri.parse(model.uriString), "r")
     val fd = pfd?.fd ?: error("Falha ao abrir FD do arquivo GGUF")
     // Passe "/proc/self/fd/$fd" para llama_model_load_from_file mantendo `pfd` aberto enquanto o modelo estiver carregado!
     ```
   - Alternativamente, copie o `.gguf` escolhido via SAF para `context.filesDir/models/<fileName>` na primeira abertura e passe o caminho POSIX absoluto.

2. **Persistir permissão SAF**:
   - Em `ModelsScreen.kt`, ao receber o `Uri` do `OpenDocument`, chame:
     `context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)`
     para que o modelo continue acessível após reiniciar o app.

---

### Etapa C — Implementar `JniLlamaEngineAdapter` substituindo `StubLlamaEngineAdapter`

1. Crie `app/src/main/java/com/example/engine/JniLlamaEngineAdapter.kt` implementando `LocalInferenceEngine`:
   - Defina `override val isNativeBridgeLinked: Boolean = true`.
   - Use um `CoroutineDispatcher` dedicado single-thread (ou `Executors.newSingleThreadExecutor().asCoroutineDispatcher()`) para todas as chamadas JNI (`llama_model`, `llama_context` não são thread-safe).
   - Mapeie `InferenceConfig` diretamente para `llama_model_params` e `llama_context_params`:
     - `model_params.n_gpu_layers = config.gpuLayers`
     - `model_params.use_mmap = config.useMmap`
     - `model_params.use_mlock = config.useMlock`
     - `ctx_params.n_ctx = config.contextLength`
     - `ctx_params.n_batch = config.batchSize`
     - `ctx_params.n_threads = config.threads`
     - `ctx_params.n_threads_batch = config.threads`
     - `ctx_params.type_k` / `ctx_params.type_v` de acordo com `config.kvCacheQuant` (`GGML_TYPE_F16`, `GGML_TYPE_Q8_0`, `GGML_TYPE_Q4_0`).
   - No loop de geração em C++ (`llama_jni.cpp`):
     - Tokenize o prompt com `llama_tokenize()`.
     - Processe em chunks de `n_batch` usando `llama_batch` + `llama_decode()`.
     - Em cada passo autoregressivo:
       - Verifique uma flag atômica `std::atomic<bool> g_abort_requested` (setada por `stopGeneration()`).
       - Amostre o próximo token com `llama_sampler_chain` (`top_k`, `top_p`, `temp`, `penalties`).
       - Converta o token para texto UTF-8 com `llama_token_to_piece()`, cuidando de sequências UTF-8 incompletas (buffer de bytes pendentes antes de enviar ao Kotlin via callback JNI!).
       - Pare em `llama_vocab_is_eog(vocab, token)` ou ao atingir `maxTokens` / `n_ctx`.
   - No `LlamaChatViewModel.kt`, troque apenas a linha:
     ```kotlin
     private val engine: LocalInferenceEngine = StubLlamaEngineAdapter()
     ```
     por:
     ```kotlin
     private val engine: LocalInferenceEngine = JniLlamaEngineAdapter(application.applicationContext)
     ```

---

### Etapa D — Persistência Local & Refinamentos Opcionais

1. **Persistência de Conversas e Configurações**:
   - Hoje `GgufRepository` mantém sessões e configurações em memória (`StateFlow`).
   - Descomente `androidx.room.*` ou `androidx.datastore.preferences` em `app/build.gradle.kts` caso queira salvar o histórico de chats e os parâmetros da engine localmente entre reinicializações.
2. **Progresso de Carregamento Real (`progress_callback`)**:
   - Conecte `model_params.progress_callback` do `llama.cpp` a um `StateFlow<Float>` para exibir progresso real de carregamento dos tensores GGUF na memória quando o arquivo for grande (ex: 2–4 GB).
3. **Detecção automática de arquivos baixados**:
   - Observe `DownloadManager.ACTION_DOWNLOAD_COMPLETE` via `BroadcastReceiver` para cadastrar automaticamente o `.gguf` recém-baixado na lista de modelos locais.
4. **Compilação & Testes**:
   - Este ambiente sandbox não possui toolchain NDK/CMake de compilação C++ para `llama.cpp` nem dispositivo físico `arm64-v8a`.
   - Para compilar e validar a engine nativa:
     1. Instale Android NDK (r26+) e CMake (3.22.1+) no Android Studio / CI local.
     2. Rode `./gradlew :app:assembleDebug` (ou `gradle :app:assembleDebug`).
     3. Teste em aparelho físico `arm64-v8a` (emuladores x86_64 sem NEON terão desempenho muito baixo e não refletem `tok/s` real).
