package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.model.EngineLimits
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextSliderSheet(
    currentContext: Int,
    maxModelContext: Int,
    modelName: String?,
    onContextChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val minCtx = EngineLimits.MIN_CONTEXT
    val maxCtx = maxModelContext.coerceAtLeast(minCtx)
    val clampedValue = currentContext.coerceIn(minCtx, maxCtx)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Janela de contexto",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (modelName != null) {
                        Text(
                            text = modelName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = "${formatTokenCount(clampedValue)} tokens",
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Slider(
                value = clampedValue.toFloat(),
                onValueChange = { raw ->
                    val step = if (maxCtx >= 32768) 512 else 256
                    val snapped = ((raw / step).roundToInt() * step).coerceIn(minCtx, maxCtx)
                    onContextChange(snapped)
                },
                valueRange = minCtx.toFloat()..maxCtx.toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("quick_context_slider")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatTokenCount(minCtx),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Máx: ${formatTokenCount(maxCtx)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

fun formatTokenCount(tokens: Int): String {
    return if (tokens >= 1024 && tokens % 1024 == 0) {
        "${tokens / 1024}K"
    } else {
        tokens.toString()
    }
}
