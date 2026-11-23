package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.domain.model.EngineLimits
import com.example.domain.model.KvCacheQuant
import com.example.state.LlamaChatAction
import com.example.state.LlamaChatUiState
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlin.math.abs

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EngineSettingsScreen(
    uiState: LlamaChatUiState,
    onAction: (LlamaChatAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val config = uiState.config
    val context = LocalContext.current
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onAction(LlamaChatAction.ExportDiagnostics)
    }
    val maxCores = uiState.deviceMemory.cpuCores.coerceAtLeast(EngineLimits.MIN_THREADS)
    val recommendedThreads = (maxCores / 2).coerceIn(2, maxCores)
    val maxModelCtx = uiState.loadedModel?.maxContextLength ?: 8192


    Box(
        modifier = modifier.fillMaxSize().navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxSize()
        ) {
            // Selective Bounded Hardware & Context Controls
            item {
                SectionCard(title = "Limites Seletivos de Hardware & Contexto") {
                Text(
                    text = "Preset móvel inicial: n_ctx=2048, n_batch=128 e n_ubatch=64. O microbatch menor reduz o pico temporário de memória durante o prefill. Aumente os valores aos poucos se o aparelho suportar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                    // 1. Context Window (n_ctx) bounded by model maxContextLength
                    ParameterHeader(
                        title = "Janela de Contexto (n_ctx)",
                        limitLabel = "Limite do modelo: $maxModelCtx",
                        valueLabel = "${config.contextLength} tok"
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        EngineLimits.CONTEXT_OPTIONS.forEach { ctx ->
                            val allowed = ctx <= maxModelCtx
                            FilterChip(
                                selected = config.contextLength == ctx,
                                enabled = allowed,
                                onClick = { onAction(LlamaChatAction.UpdateContextLength(ctx)) },
                                label = { Text("$ctx") }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 2. Batch Size (n_batch) strictly bounded by n_ctx
                    ParameterHeader(
                        title = "Batch Size (n_batch)",
                        limitLabel = "Limite: ≤ n_ctx (${config.contextLength})",
                        valueLabel = "${config.batchSize}"
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        EngineLimits.BATCH_OPTIONS.forEach { batch ->
                            val allowed = batch <= config.contextLength
                            FilterChip(
                                selected = config.batchSize == batch,
                                enabled = allowed,
                                onClick = { onAction(LlamaChatAction.UpdateBatchSize(batch)) },
                                label = { Text("$batch") }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 3. Max Output Tokens strictly bounded by n_ctx
                    ParameterHeader(
                        title = "Máx. Tokens por Resposta",
                        limitLabel = "Limite: 128..${config.contextLength}",
                        valueLabel = "${config.maxTokens} tok"
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        EngineLimits.MAX_TOKENS_OPTIONS.forEach { limit ->
                            val allowed = limit <= config.contextLength
                            FilterChip(
                                selected = config.maxTokens == limit,
                                enabled = allowed,
                                onClick = { onAction(LlamaChatAction.UpdateMaxTokens(limit)) },
                                label = { Text("$limit") }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 4. CPU Threads strictly bounded to 1..deviceMemory.cpuCores
                    BoundedStepperControl(
                        title = "Threads CPU (n_threads)",
                        limitText = "Limite do dispositivo: 1..$maxCores núcleos (ideal: $recommendedThreads)",
                        valueText = "${config.threads}",
                        canDecrement = config.threads > EngineLimits.MIN_THREADS,
                        canIncrement = config.threads < maxCores,
                        onDecrement = { onAction(LlamaChatAction.UpdateThreads(config.threads - 1)) },
                        onIncrement = { onAction(LlamaChatAction.UpdateThreads(config.threads + 1)) },
                        testTagPrefix = "threads"
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 2, recommendedThreads, maxCores).distinct().sorted().forEach { t ->
                            FilterChip(
                                selected = config.threads == t,
                                onClick = { onAction(LlamaChatAction.UpdateThreads(t)) },
                                label = {
                                    Text(if (t == recommendedThreads) "$t (Ideal)" else "$t")
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 5. GPU Layers strictly bounded 0..99
                    BoundedStepperControl(
                        title = "Camadas GPU Vulkan/OpenCL (n_gpu_layers)",
                        limitText = if (uiState.gpuOffloadAvailable) {
                            "Limite: 0 (somente CPU) .. ${EngineLimits.MAX_GPU_LAYERS}"
                        } else {
                            "Backend GPU indisponível nesta build (somente CPU)."
                        },
                        valueText = "${config.gpuLayers}",
                        canDecrement = uiState.gpuOffloadAvailable && config.gpuLayers > 0,
                        canIncrement = uiState.gpuOffloadAvailable &&
                            config.gpuLayers < EngineLimits.MAX_GPU_LAYERS,
                        onDecrement = { onAction(LlamaChatAction.UpdateGpuLayers(config.gpuLayers - 1)) },
                        onIncrement = { onAction(LlamaChatAction.UpdateGpuLayers(config.gpuLayers + 1)) },
                        testTagPrefix = "gpu_layers"
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EngineLimits.GPU_LAYER_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = config.gpuLayers == preset,
                                enabled = uiState.gpuOffloadAvailable || preset == 0,
                                onClick = { onAction(LlamaChatAction.UpdateGpuLayers(preset)) },
                                label = { Text(if (preset == 0) "0 (CPU)" else "$preset") }
                            )
                        }
                    }
                    if (!uiState.gpuOffloadAvailable) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Esta build inclui apenas o backend CPU arm64-v8a. Os controles de offload GPU serão habilitados quando Vulkan ou OpenCL forem adicionados.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Quantização do KV Cache",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KvCacheQuant.entries.forEach { quant ->
                            FilterChip(
                                selected = config.kvCacheQuant == quant,
                                onClick = { onAction(LlamaChatAction.UpdateKvCacheQuant(quant)) },
                                label = { Text(quant.label) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    ToggleRow(
                        title = "Memory-Mapped I/O (use_mmap)",
                        subtitle = "Mapeia pesos GGUF direto do arquivo, reduzindo pico de RAM.",
                        checked = config.useMmap,
                        onCheckedChange = { onAction(LlamaChatAction.ToggleMmap(it)) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ToggleRow(
                        title = "Memory Lock (use_mlock)",
                        subtitle = "Solicita ao sistema manter as páginas do modelo na RAM. Pode aumentar a pressão de memória; o controle fica a seu critério.",
                        checked = config.useMlock,
                        onCheckedChange = { onAction(LlamaChatAction.ToggleMlock(it)) }
                    )
                }
            }

            // Selective Bounded Sampling Presets
            item {
                SectionCard(title = "Amostragem Seletiva (Samplers)") {
                    ParameterHeader(
                        title = "Temperature",
                        limitLabel = "Faixa: 0.0 (preciso) .. 1.2 (criativo)",
                        valueLabel = String.format("%.2f", config.temperature)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EngineLimits.TEMPERATURE_OPTIONS.forEach { temp ->
                            FilterChip(
                                selected = abs(config.temperature - temp) < 0.02f,
                                onClick = { onAction(LlamaChatAction.UpdateTemperature(temp)) },
                                label = { Text(String.format("%.1f", temp)) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    ParameterHeader(
                        title = "Top-P (Nucleus)",
                        limitLabel = "Faixa: 0.50 .. 1.00",
                        valueLabel = String.format("%.2f", config.topP)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EngineLimits.TOP_P_OPTIONS.forEach { p ->
                            FilterChip(
                                selected = abs(config.topP - p) < 0.02f,
                                onClick = { onAction(LlamaChatAction.UpdateTopP(p)) },
                                label = { Text(String.format("%.2f", p)) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    ParameterHeader(
                        title = "Top-K",
                        limitLabel = "Faixa: 1 .. 100",
                        valueLabel = "${config.topK}"
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EngineLimits.TOP_K_OPTIONS.forEach { k ->
                            FilterChip(
                                selected = config.topK == k,
                                onClick = { onAction(LlamaChatAction.UpdateTopK(k)) },
                                label = { Text("$k") }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    ParameterHeader(
                        title = "Repeat Penalty",
                        limitLabel = "Faixa: 1.00 .. 1.25",
                        valueLabel = String.format("%.2f", config.repeatPenalty)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EngineLimits.REPEAT_PENALTY_OPTIONS.forEach { pen ->
                            FilterChip(
                                selected = abs(config.repeatPenalty - pen) < 0.02f,
                                onClick = { onAction(LlamaChatAction.UpdateRepeatPenalty(pen)) },
                                label = { Text(String.format("%.2f", pen)) }
                            )
                        }
                    }
                }
            }

            // Embedded GGUF Jinja template & System Prompt
            item {
                SectionCard(title = "Jinja do Modelo & System Prompt") {
                    Text(
                        text = "O formato das mensagens vem de tokenizer.chat_template dentro do GGUF. O app usa esse template automaticamente e não aplica templates fixos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = config.systemPrompt,
                        onValueChange = { onAction(LlamaChatAction.UpdateSystemPrompt(it)) },
                        label = { Text("System Prompt") },
                        minLines = 3,
                        maxLines = 6,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().testTag("system_prompt_input")
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { onAction(LlamaChatAction.ResetDefaultConfig) },
                        modifier = Modifier.fillMaxWidth().testTag("reset_engine_config_button")
                    ) {
                        Text("Restaurar Padrões Otimizados")
                    }
                }
            }

            // JNI Integration Contract Status
            item {
                SectionCard(title = "Engine nativa / NDK") {
                    Text(
                        text = if (uiState.isNativeBridgeLinked) {
                            "Ponte JNI conectada ao llama.cpp. Os modelos GGUF são carregados localmente pelo NDK, sem enviar prompts ou arquivos para uma API remota."
                        } else {
                            "A ponte JNI nativa não está carregada."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (uiState.gpuOffloadAvailable) {
                            "Backend GPU disponível nesta compilação."
                        } else {
                            "Backend CPU arm64-v8a ativo. Vulkan/OpenCL não fazem parte desta build."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "n_ctx=${config.contextLength} | n_batch=${config.batchSize} | n_threads=${config.threads} | max_tokens=${config.maxTokens} | n_gpu=${config.gpuLayers}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
            item {
                SectionCard(title = "Diagnóstico e logs") {
                    Text(
                        text = "Salva falhas de carregamento, ciclo de vida, mensagens do llama.cpp e erros nativos em um arquivo que pode ser enviado para análise.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else {
                                onAction(LlamaChatAction.ExportDiagnostics)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_diagnostics_button")
                    ) {
                        Text("Exportar logs para Downloads")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(18.dp)
            )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ParameterHeader(
    title: String,
    limitLabel: String,
    valueLabel: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = limitLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = valueLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun BoundedStepperControl(
    title: String,
    limitText: String,
    valueText: String,
    canDecrement: Boolean,
    canIncrement: Boolean,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    testTagPrefix: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = limitText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalIconButton(
                onClick = onDecrement,
                enabled = canDecrement,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("${testTagPrefix}_dec_button")
            ) {
                Icon(Icons.Rounded.Remove, contentDescription = "Diminuir $title")
            }

            Text(
                text = valueText,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            FilledTonalIconButton(
                onClick = onIncrement,
                enabled = canIncrement,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("${testTagPrefix}_inc_button")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Aumentar $title")
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange
        )
    }
}
