package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.model.EngineLimits
import com.example.state.LlamaChatAction
import com.example.state.LlamaChatUiState
import com.example.ui.components.formatTokenCount
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlin.math.roundToInt

@Composable
fun EngineSettingsScreen(
    uiState: LlamaChatUiState,
    onAction: (LlamaChatAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val config = uiState.config
    val maxContext = uiState.maxModelContext.coerceAtLeast(EngineLimits.MIN_CONTEXT)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingsSectionCard(title = "Memória e execução") {
                    IntSliderRow(
                        label = "Contexto",
                        value = config.contextLength.coerceIn(EngineLimits.MIN_CONTEXT, maxContext),
                        range = EngineLimits.MIN_CONTEXT..maxContext,
                        step = if (maxContext >= 32768) 512 else 256,
                        valueLabel = "${formatTokenCount(config.contextLength)} tokens",
                        onValueChange = { onAction(LlamaChatAction.UpdateContextLength(it)) },
                        testTag = "slider_context_length"
                    )

                    IntSliderRow(
                        label = "Máximo de geração",
                        value = config.maxTokens,
                        range = EngineLimits.MIN_MAX_TOKENS..EngineLimits.MAX_MAX_TOKENS,
                        step = 256,
                        valueLabel = "${formatTokenCount(config.maxTokens)} tokens",
                        onValueChange = { onAction(LlamaChatAction.UpdateMaxTokens(it)) },
                        testTag = "slider_max_tokens"
                    )

                    IntSliderRow(
                        label = "Batch size",
                        value = config.batchSize,
                        range = EngineLimits.MIN_BATCH..EngineLimits.MAX_BATCH,
                        step = 32,
                        valueLabel = config.batchSize.toString(),
                        onValueChange = { onAction(LlamaChatAction.UpdateBatchSize(it)) },
                        testTag = "slider_batch_size"
                    )

                    IntSliderRow(
                        label = "Threads CPU",
                        value = config.threads,
                        range = EngineLimits.MIN_THREADS..EngineLimits.MAX_THREADS,
                        step = 1,
                        valueLabel = config.threads.toString(),
                        onValueChange = { onAction(LlamaChatAction.UpdateThreads(it)) },
                        testTag = "slider_threads"
                    )

                    Text(
                        text = "Backend nativo: llama.cpp CPU arm64 com otimizações KleidiAI. Offload GPU não está ativado nesta build.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    SwitchRow(
                        label = "mmap",
                        checked = config.useMmap,
                        onCheckedChange = { onAction(LlamaChatAction.UpdateUseMmap(it)) },
                        testTag = "switch_mmap"
                    )

                    SwitchRow(
                        label = "mlock",
                        checked = config.useMlock,
                        onCheckedChange = { onAction(LlamaChatAction.UpdateUseMlock(it)) },
                        testTag = "switch_mlock"
                    )
                }
            }

            item {
                SettingsSectionCard(title = "Amostragem") {
                    FloatSliderRow(
                        label = "Temperatura",
                        value = config.temperature,
                        range = 0.0f..2.0f,
                        valueLabel = String.format("%.2f", config.temperature),
                        onValueChange = { onAction(LlamaChatAction.UpdateTemperature(it)) },
                        testTag = "slider_temperature"
                    )

                    FloatSliderRow(
                        label = "Top-P",
                        value = config.topP,
                        range = 0.05f..1.0f,
                        valueLabel = String.format("%.2f", config.topP),
                        onValueChange = { onAction(LlamaChatAction.UpdateTopP(it)) },
                        testTag = "slider_top_p"
                    )

                    IntSliderRow(
                        label = "Top-K",
                        value = config.topK,
                        range = 1..200,
                        step = 1,
                        valueLabel = config.topK.toString(),
                        onValueChange = { onAction(LlamaChatAction.UpdateTopK(it)) },
                        testTag = "slider_top_k"
                    )

                    FloatSliderRow(
                        label = "Repeat penalty",
                        value = config.repeatPenalty,
                        range = 1.0f..1.5f,
                        valueLabel = String.format("%.2f", config.repeatPenalty),
                        onValueChange = { onAction(LlamaChatAction.UpdateRepeatPenalty(it)) },
                        testTag = "slider_repeat_penalty"
                    )
                }
            }

            item {
                SettingsSectionCard(title = "Sistema") {
                    OutlinedTextField(
                        value = config.systemPrompt,
                        onValueChange = { onAction(LlamaChatAction.UpdateSystemPrompt(it)) },
                        label = { Text("Instrução de sistema") },
                        minLines = 2,
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("system_prompt_input")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = { onAction(LlamaChatAction.ResetEngineDefaults) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("reset_defaults_button")
                    ) {
                        Text("Restaurar padrão")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        content()
    }
}

@Composable
private fun IntSliderRow(
    label: String,
    value: Int,
    range: IntRange,
    step: Int,
    valueLabel: String,
    onValueChange: (Int) -> Unit,
    testTag: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFontFamily),
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.coerceIn(range.first, range.last).toFloat(),
            onValueChange = { raw ->
                val snapped = ((raw / step).roundToInt() * step).coerceIn(range.first, range.last)
                onValueChange(snapped)
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        )
    }
}

@Composable
private fun FloatSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    testTag: String
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMonoFontFamily),
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = { onValueChange(it.coerceIn(range.start, range.endInclusive)) },
            valueRange = range.start..range.endInclusive,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}
