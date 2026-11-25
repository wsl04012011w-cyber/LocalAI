package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.AttachmentType
import com.example.domain.model.ChatAttachment
import com.example.domain.model.ChatRole
import com.example.domain.model.ThinkingEffort
import com.example.domain.model.ThinkingSupportMode
import com.example.state.AppDestination
import com.example.state.LlamaChatAction
import com.example.state.LlamaChatUiState
import com.example.ui.components.AttachmentSheet
import com.example.ui.components.MessageBubble
import com.example.ui.components.formatTokenCount
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun ChatScreen(
    uiState: LlamaChatUiState,
    onAction: (LlamaChatAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val messages = uiState.activeSession?.messages.orEmpty()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onAction(LlamaChatAction.AddImageAttachmentUri(uri))
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onAction(LlamaChatAction.AddDocumentAttachmentUri(uri))
        }
    }

    val lastAssistantIndex = messages.indexOfLast { it.role == ChatRole.ASSISTANT }

    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length, messages.lastOrNull()?.thinkingContent?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .widthIn(max = 720.dp)
        ) {
            if (messages.isEmpty()) {
                EmptyChatState(
                    uiState = uiState,
                    onPickModel = { onAction(LlamaChatAction.SelectDestination(AppDestination.MODELS)) },
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(messages, key = { _, msg -> msg.id }) { index, msg ->
                        MessageBubble(
                            message = msg,
                            isLastAssistantMessage = index == lastAssistantIndex,
                            isGenerating = uiState.isGenerating,
                            onCopy = { text ->
                                clipboard.setText(AnnotatedString(text))
                                onAction(LlamaChatAction.ShowBanner("Mensagem copiada."))
                            },
                            onRegenerate = { onAction(LlamaChatAction.RegenerateLastResponse) },
                            onToggleThinking = { msgId -> onAction(LlamaChatAction.ToggleThinkingBox(msgId)) }
                        )
                    }
                }
            }
        }

        if (uiState.pendingAttachments.isNotEmpty()) {
            PendingAttachmentsStrip(
                attachments = uiState.pendingAttachments,
                onRemove = { id -> onAction(LlamaChatAction.RemoveAttachment(id)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 720.dp)
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }

        MinimalComposerCard(
            uiState = uiState,
            onTextChange = { onAction(LlamaChatAction.UpdateComposerText(it)) },
            onSend = { onAction(LlamaChatAction.SendMessage) },
            onStop = { onAction(LlamaChatAction.StopGeneration) },
            onOpenModels = { onAction(LlamaChatAction.SelectDestination(AppDestination.MODELS)) },
            onOpenContext = { onAction(LlamaChatAction.SetContextSheetOpen(true)) },
            onCycleThinking = { onAction(LlamaChatAction.CycleThinkingEffort) },
            onOpenAttachment = { onAction(LlamaChatAction.SetAttachmentSheetOpen(true)) },
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 720.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        )
    }

    if (uiState.isAttachmentSheetOpen) {
        AttachmentSheet(
            supportsVision = uiState.loadedModel?.supportsVision == true,
            hasLoadedModel = uiState.loadedModel != null,
            onPickImage = {
                imagePickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onPickDocument = {
                documentPickerLauncher.launch(
                    arrayOf("text/*", "application/pdf", "application/json", "*/*")
                )
            },
            onDismiss = { onAction(LlamaChatAction.SetAttachmentSheetOpen(false)) }
        )
    }
}

@Composable
private fun EmptyChatState(
    uiState: LlamaChatUiState,
    onPickModel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.ayr_off_logo),
            contentDescription = stringResource(R.string.cd_app_logo),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(16.dp))
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Qual é o plano para hoje?",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        val loaded = uiState.loadedModel
        if (loaded != null) {
            Text(
                text = "${loaded.name} • ${loaded.quantization}",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onPickModel)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .testTag("empty_select_model_pill")
            ) {
                Text(
                    text = "Escolher um modelo para começar",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun PendingAttachmentsStrip(
    attachments: List<ChatAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { att ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (att.type == AttachmentType.IMAGE) Icons.Rounded.Image else Icons.Rounded.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = att.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp)
                )
                IconButton(
                    onClick = { onRemove(att.id) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.cd_remove_attachment),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MinimalComposerCard(
    uiState: LlamaChatUiState,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenContext: () -> Unit,
    onCycleThinking: () -> Unit,
    onOpenAttachment: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canSend = (uiState.composerText.isNotBlank() || uiState.pendingAttachments.isNotEmpty()) && !uiState.isLoadingModel
    val loadedModel = uiState.loadedModel
    val thinkingSupport = loadedModel?.thinkingSupport ?: ThinkingSupportMode.NONE
    val thinkingEffort = uiState.config.thinkingEffort

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
            .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 6.dp)
        ) {
            if (uiState.composerText.isEmpty()) {
                Text(
                    text = if (loadedModel != null) "Mensagem para ${loadedModel.name}" else "Mensagem",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            BasicTextField(
                value = uiState.composerText,
                onValueChange = onTextChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                singleLine = false,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("chat_composer_input")
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val modelLabel = if (loadedModel != null) loadedModel.parameterSize else "Modelos"
                ComposerChip(
                    label = modelLabel,
                    icon = Icons.Rounded.Memory,
                    selected = loadedModel != null,
                    onClick = onOpenModels,
                    modifier = Modifier.testTag("composer_model_chip")
                )

                ComposerChip(
                    label = "${formatTokenCount(uiState.config.contextLength)} ctx",
                    icon = Icons.Rounded.Tune,
                    selected = false,
                    onClick = onOpenContext,
                    modifier = Modifier.testTag("composer_context_chip")
                )

                if (loadedModel == null || thinkingSupport != ThinkingSupportMode.NONE) {
                    val thinkLabel = when {
                        thinkingSupport == ThinkingSupportMode.EFFORT_LEVELS && thinkingEffort != ThinkingEffort.OFF -> {
                            "Think • ${thinkingEffort.shortLabel}"
                        }
                        else -> "Think"
                    }
                    ComposerChip(
                        label = thinkLabel,
                        icon = Icons.Rounded.AutoAwesome,
                        selected = thinkingEffort.isEnabled,
                        onClick = onCycleThinking,
                        modifier = Modifier.testTag("composer_think_chip")
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = onOpenAttachment,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("composer_attach_button")
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = stringResource(R.string.cd_attach_file),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            if (uiState.isGenerating) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("composer_stop_button")
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Stop,
                            contentDescription = stringResource(R.string.cd_stop_generation),
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("composer_send_button")
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                if (canSend) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = stringResource(R.string.cd_send_prompt),
                            tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.outline
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = contentColor,
            maxLines = 1
        )
    }
}
