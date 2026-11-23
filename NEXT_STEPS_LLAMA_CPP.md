# Integração llama.cpp — implementação atual

A engine JNI está integrada ao app Android. A dependência nativa usa o tag fixo llama.cpp v1.0.0, compilado com CMake e Android NDK.

## Implementado

- JniLlamaEngineAdapter implementa LocalInferenceEngine e substitui o stub no LlamaChatViewModel.
- O modelo GGUF é aberto pelo Android ContentResolver. O ParcelFileDescriptor permanece aberto até llama_model_free, permitindo que o llama.cpp use /proc/self/fd/<fd> sem copiar o arquivo selecionado pelo SAF.
- A permissão de leitura do SAF é persistida quando o provedor permite.
- Modelos do catálogo são resolvidos pela URI de um download concluído no Android DownloadManager; modelos importados usam sua URI SAF.
- A ponte JNI carrega e libera o modelo, cria o contexto, tokeniza o prompt, faz prefill em lotes, executa sampling autoregressivo e envia tokens em streaming.
- A engine propaga n_ctx, n_batch, n_threads, tipos K/V do KV cache, maxTokens, temperature, top_p, top_k e repetição.
- A conversão de tokens mantém bytes UTF-8 incompletos entre tokens antes de enviar texto ao Kotlin.
- O cancelamento usa uma flag atômica nativa e o callback de aborto do contexto.
- useMmap e useMlock são convertidos para llama_model_params.load_mode, conforme a API v1.0.0.
- A build inicial é CPU-only e limitada a arm64-v8a. Os controles GPU são desativados e a engine rejeita n_gpu_layers > 0 enquanto não houver backend GPU.
- .github/workflows/android.yml compila e testa o app com Android SDK, NDK e CMake; publica o APK debug como artifact do workflow.

## Build local

Instale Android SDK, NDK 27.2.12479018 e CMake 3.22.1, depois execute:

    ./gradlew :app:testDebugUnitTest :app:assembleDebug

O CMake baixa o código oficial de llama.cpp e o submódulo ggml durante a primeira configuração. É necessário acesso à internet nessa etapa.

## Limites atuais

- O backend incluído é CPU arm64-v8a; Vulkan e OpenCL ainda não estão habilitados.
- CI confirma compilação e testes JVM, mas não substitui teste de inferência em dispositivo ARM64 com um arquivo GGUF real.
- Sessões e configurações continuam em memória no GgufRepository; persistência Room/DataStore não faz parte desta integração.
- O formatter Kotlin continua fornecendo os templates Llama 3, ChatML, Gemma e Phi-3. O uso automático dos templates embutidos nos metadados GGUF permanece como melhoria futura.
