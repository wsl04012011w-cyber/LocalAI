package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.MessageRole
import com.example.state.AppDestination
import com.example.state.LlamaChatAction
import com.example.state.LlamaChatUiState
import com.example.ui.components.MessageBubble

@Composable
fun ChatScreen(
    uiState: LlamaChatUiState,
    onAction: (LlamaChatAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val messages = uiState.activeSession?.messages.orEmpty()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    val isNearBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            layoutInfo.totalItemsCount == 0 || lastVisible >= layoutInfo.totalItemsCount - 2
        }
    }

    val lastContentLength = messages.lastOrNull()?.content?.length ?: 0
    LaunchedEffect(messages.size, lastContentLength) {
        if (messages.isNotEmpty() && isNearBottom) {
            listState.scrollToItem(messages.lastIndex)
        }
    }

    val lastAssistantId = remember(messages) {
        messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (messages.isEmpty()) {
                MinimalEmptyChatState(
                    uiState = uiState,
                    onQuickModelTap = { onAction(LlamaChatAction.SelectDestination(AppDestination.MODELS)) }
                )
            } else {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .widthIn(max = 720.dp)
                        .fillMaxSize()
                ) {
                    itemsIndexed(messages, key = { _, msg -> msg.id }) { _, message ->
                        MessageBubble(
                            message = message,
                            isLastAssistantMessage = message.id == lastAssistantId,
                            isEngineBusy = uiState.isGenerating || uiState.isLoadingModel,
                            onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
                            onRegenerate = { onAction(LlamaChatAction.RegenerateLastResponse) }
                        )
                    }
                }
            }
        }

        // Signature Floating Pill Composer
        MinimalComposerCard(
            uiState = uiState,
            onDraftChange = { onAction(LlamaChatAction.UpdateDraft(it)) },
            onSend = { onAction(LlamaChatAction.SendPrompt()) },
            onStop = { onAction(LlamaChatAction.StopGeneration) },
            onOpenModels = { onAction(LlamaChatAction.SelectDestination(AppDestination.MODELS)) },
            onOpenEngine = { onAction(LlamaChatAction.SelectDestination(AppDestination.ENGINE)) },
            onOpenPromptInspector = { onAction(LlamaChatAction.SetPromptInspectorOpen(true)) }
        )
    }
}

@Composable
private fun MinimalEmptyChatState(
    uiState: LlamaChatUiState,
    onQuickModelTap: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.llama_local_icon_1795437762783),
            contentDescription = stringResource(R.string.cd_app_logo),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .clickable(onClick = onQuickModelTap)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Qual é o plano para hoje?",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        uiState.loadedModel?.let { model ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "${model.name} • ${model.quantization.label}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun MinimalComposerCard(
    uiState: LlamaChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenEngine: () -> Unit,
    onOpenPromptInspector: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(26.dp)
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)
            ) {
                // Clean borderless input field
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 6.dp)
                ) {
                    if (uiState.composerDraft.isEmpty()) {
                        Text(
                            text = "Mensagem para inferência local",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    BasicTextField(
                        value = uiState.composerDraft,
                        onValueChange = onDraftChange,
                        enabled = !uiState.isLoadingModel,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        minLines = 1,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = { if (uiState.canSendPrompt) onSend() }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 112.dp)
                            .verticalScroll(rememberScrollState())
                            .testTag("chat_prompt_input")
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom action row inside composer pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ComposerModePill(
                            icon = Icons.Rounded.Memory,
                            label = uiState.loadedModel?.parameterLabel?.let { "GGUF $it" } ?: "GGUF",
                            active = uiState.loadedModel != null,
                            onClick = onOpenModels,
                            testTag = "composer_models_pill"
                        )
                        ComposerModePill(
                            icon = Icons.Rounded.Tune,
                            label = "${uiState.config.contextLength / 1024}K ctx",
                            active = false,
                            onClick = onOpenEngine,
                            testTag = "composer_engine_pill"
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onOpenPromptInspector,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("composer_prompt_inspector_button")
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .border(
                                        width = 1.2.dp,
                                        color = MaterialTheme.colorScheme.outline,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Add,
                                    contentDescription = stringResource(R.string.cd_inspect_prompt),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        FilledIconButton(
                            onClick = {
                                if (uiState.isGenerating) onStop() else onSend()
                            },
                            enabled = uiState.isGenerating || uiState.canSendPrompt,
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = when {
                                    uiState.isGenerating -> MaterialTheme.colorScheme.error
                                    uiState.canSendPrompt -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                },
                                contentColor = when {
                                    uiState.isGenerating -> MaterialTheme.colorScheme.onError
                                    uiState.canSendPrompt -> MaterialTheme.colorScheme.onPrimary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .size(38.dp)
                                .testTag("send_or_stop_button")
                        ) {
                            Icon(
                                imageVector = if (uiState.isGenerating) {
                                    Icons.Rounded.Stop
                                } else {
                                    Icons.AutoMirrored.Rounded.Send
                                },
                                contentDescription = if (uiState.isGenerating) {
                                    stringResource(R.string.cd_stop_generation)
                                } else {
                                    stringResource(R.string.cd_send_prompt)
                                },
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerModePill(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val bgColor = if (active) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val borderColor = if (active) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.outline
    }
    val contentColor = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(15.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor
        )
    }
}
