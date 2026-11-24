# Integração llama.cpp — implementação atual

A engine JNI está integrada ao app Android. A dependência nativa usa o tag fixo llama.cpp v1.0.0, compilado com CMake e Android NDK.

## Implementado

- JniLlamaEngineAdapter implementa LocalInferenceEngine e substitui o stub no LlamaChatViewModel.
- Arquivos selecionados via SAF são copiados para \`filesDir/gguf-models/model_<id>.gguf\`, um arquivo regular privado e persistente que garante acesso aleatório/mmap mesmo quando o provedor fornece pipes ou descritores não seekable. Descarregar o modelo libera apenas o handle nativo; a cópia permanece para os próximos carregamentos e só é removida pela ação explícita de remoção do modelo.
- A permissão de leitura do SAF é persistida quando o provedor permite.
- Modelos do catálogo são resolvidos pela URI de um download concluído no Android DownloadManager; modelos importados usam sua URI SAF na primeira cópia. O registro de modelos importados persiste o ID estável em SharedPreferences, permitindo reutilizar o arquivo privado após reiniciar o app.
- A ponte JNI carrega e libera o modelo, cria o contexto, tokeniza o prompt, faz prefill em lotes, executa sampling autoregressivo e envia tokens em streaming.
- A engine propaga n_ctx, n_batch, n_threads, tipos K/V do KV cache, maxTokens, temperature, top_p, top_k e repetição.
- A conversão de tokens mantém bytes UTF-8 incompletos entre tokens antes de enviar texto ao Kotlin.
- O cancelamento usa uma flag atômica nativa e o callback de aborto do contexto.
- useMmap e useMlock são convertidos para llama_model_params.load_mode, conforme a API v1.0.0.
- A build CPU-only usa variantes ARM selecionadas em runtime; os controles GPU são desativados e a engine rejeita n_gpu_layers > 0 enquanto não houver backend GPU.
- \`.github/workflows/android.yml\` compila e testa o app com Android SDK, NDK e CMake; publica o APK \`releaseLocal\` como artefato do workflow.

## Build local

Instale Android SDK, NDK 27.2.12479018, CMake 3.22.1 e Gradle 9.3.1, depois execute:

    gradle :app:testDebugUnitTest :app:assembleReleaseLocal

O workflow usa Gradle 9.3.1 diretamente porque este repositório ainda não versiona os scripts do Gradle Wrapper. O CMake baixa o código oficial de llama.cpp e o submódulo ggml durante a primeira configuração. É necessário acesso à internet nessa etapa.

## Limites atuais

- O backend incluído é CPU arm64-v8a; Vulkan e OpenCL ainda não estão habilitados.
- CI confirma compilação e testes JVM, mas não substitui teste de inferência em dispositivo ARM64 com um arquivo GGUF real.
- Sessões e configurações continuam em memória no GgufRepository; persistência Room/DataStore não faz parte desta integração.
- A renderização do prompt usa o template Jinja \`tokenizer.chat_template\` embutido no GGUF através do runtime Jinja/minja do \`llama.cpp\` (\`llama-common\`). Não há seleção de templates fixos por nome de arquivo.

## Observação sobre o armazenamento

\`JniLlamaEngineAdapter\` é atualmente o caminho efetivamente usado pelo carregamento da engine: ele mantém os GGUF preparados em \`filesDir/gguf-models\`, identificados pelo ID estável do modelo. O arquivo \`LocalModelStorageManager.kt\` presente nesta exportação define um gerenciador auxiliar em \`filesDir/models\`, mas ainda não está conectado ao \`LlamaChatViewModel\`; não é o caminho de arquivo entregue ao JNI. Manter os dois caminhos separados evita copiar um mesmo GGUF duas vezes sem necessidade.
