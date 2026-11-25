package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.GgufModelSpec
import com.example.domain.model.ModelDownloadProgress
import com.example.domain.model.ThinkingSupportMode
import com.example.state.LlamaChatAction
import com.example.state.LlamaChatUiState
import com.example.ui.components.formatTokenCount
import com.example.ui.theme.EmeraldStatus
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun ModelsScreen(
    uiState: LlamaChatUiState,
    onAction: (LlamaChatAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onAction(LlamaChatAction.ImportModelUri(uri))
        }
    }

    val downloadedModels = uiState.models.filter { it.isDownloaded }
    val catalogModels = uiState.models.filterNot { it.isDownloaded }

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
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Gerenciar modelos",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    FilledTonalButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier.testTag("import_gguf_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FileOpen,
                            contentDescription = stringResource(R.string.cd_pick_gguf),
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Importar GGUF")
                    }
                }
            }

            if (downloadedModels.isNotEmpty()) {
                item {
                    Text(
                        text = "Instalados",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(downloadedModels, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        isLoaded = uiState.loadedModel?.id == model.id,
                        isBusyLoading = uiState.isLoadingModel,
                        downloadProgress = uiState.downloads[model.id],
                        onDownload = { onAction(LlamaChatAction.DownloadModel(model)) },
                        onCancelDownload = { onAction(LlamaChatAction.CancelDownload(model.id)) },
                        onLoad = { onAction(LlamaChatAction.LoadModel(model)) },
                        onUnload = { onAction(LlamaChatAction.UnloadModel) },
                        onDelete = { onAction(LlamaChatAction.DeleteModel(model)) }
                    )
                }
            }

            if (catalogModels.isNotEmpty()) {
                item {
                    Text(
                        text = "Disponíveis para download",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                items(catalogModels, key = { it.id }) { model ->
                    ModelCard(
                        model = model,
                        isLoaded = uiState.loadedModel?.id == model.id,
                        isBusyLoading = uiState.isLoadingModel,
                        downloadProgress = uiState.downloads[model.id],
                        onDownload = { onAction(LlamaChatAction.DownloadModel(model)) },
                        onCancelDownload = { onAction(LlamaChatAction.CancelDownload(model.id)) },
                        onLoad = { onAction(LlamaChatAction.LoadModel(model)) },
                        onUnload = { onAction(LlamaChatAction.UnloadModel) },
                        onDelete = { onAction(LlamaChatAction.DeleteModel(model)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: GgufModelSpec,
    isLoaded: Boolean,
    isBusyLoading: Boolean,
    downloadProgress: ModelDownloadProgress?,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onDelete: () -> Unit
) {
    val borderColor = if (isLoaded) {
        EmeraldStatus.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, borderColor, RoundedCornerShape(18.dp))
            .padding(14.dp)
            .testTag("model_card_${model.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${model.parameterSize} • ${model.quantization} • ${formatMb(model.fileSizeMb)} • ${formatTokenCount(model.maxContextLength)} ctx",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (model.isDownloaded) {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.cd_delete_model),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (model.supportsVision || model.thinkingSupport != ThinkingSupportMode.NONE) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.thinkingSupport != ThinkingSupportMode.NONE) {
                    val label = if (model.thinkingSupport == ThinkingSupportMode.EFFORT_LEVELS) {
                        "Raciocínio (Low–XHigh)"
                    } else {
                        "Raciocínio"
                    }
                    CapabilityBadge(icon = Icons.Rounded.AutoAwesome, text = label)
                }
                if (model.supportsVision) {
                    CapabilityBadge(icon = Icons.Rounded.Visibility, text = "Visão")
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (downloadProgress != null) {
            Column(modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { downloadProgress.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val remainingMb = downloadProgress.remainingBytes / (1024.0 * 1024.0)
                    val speedMb = downloadProgress.speedBytesPerSec / (1024.0 * 1024.0)
                    Text(
                        text = "${downloadProgress.percent}% • Faltam ${String.format("%.0f", remainingMb)} MB • ${String.format("%.1f", speedMb)} MB/s • ${formatEta(downloadProgress.etaSeconds)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onCancelDownload,
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.cd_cancel_download),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        } else if (model.isDownloaded) {
            if (isLoaded) {
                OutlinedButton(
                    onClick = onUnload,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("unload_model_button_${model.id}")
                ) {
                    Text("Descarregar")
                }
            } else {
                Button(
                    onClick = onLoad,
                    enabled = !isBusyLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("load_model_button_${model.id}")
                ) {
                    if (isBusyLoading) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Carregar")
                }
            }
        } else {
            FilledTonalButton(
                onClick = onDownload,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("download_model_button_${model.id}")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Download,
                    contentDescription = null,
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Baixar")
            }
        }
    }
}

@Composable
private fun CapabilityBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(13.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatMb(mb: Int): String {
    return if (mb >= 1024) {
        String.format("%.1f GB", mb / 1024.0)
    } else {
        "$mb MB"
    }
}

private fun formatEta(seconds: Long): String {
    if (seconds <= 0L) return "--"
    val mins = seconds / 60
    val secs = seconds % 60
    return if (mins > 0) "${mins}m ${secs}s" else "${secs}s"
}
