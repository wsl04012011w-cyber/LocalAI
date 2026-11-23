package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.ChatMessage
import com.example.domain.model.MessageRole
import com.example.domain.model.MessageStatus
import com.example.ui.theme.JetBrainsMonoFontFamily

private sealed interface ContentSegment {
    data class TextBlock(val text: String) : ContentSegment
    data class CodeBlock(val language: String, val code: String) : ContentSegment
}

@Composable
fun MessageBubble(
    message: ChatMessage,
    isLastAssistantMessage: Boolean,
    isEngineBusy: Boolean,
    onCopy: (String) -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.USER
    val segments = remember(message.content) { parseMarkdownSegments(message.content) }

    val cursorTransition = rememberInfiniteTransition(label = "streamCursor")
    val cursorAlpha by cursorTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
        label = "cursorAlpha"
    )

    if (isUser) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .padding(start = 52.dp)
                    .testTag("user_message_bubble")
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                )
            }
        }
    } else {
        // Borderless editorial assistant layout directly on the dark canvas
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .testTag("assistant_message_bubble")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.llama_local_icon_1795437762783),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = message.modelName ?: "LlamaLocal",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (message.status == MessageStatus.STOPPED) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "• interrompido",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                } else if (message.status == MessageStatus.ERROR) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "• erro",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            if (message.thinkingContent.isNotBlank()) {
                var thinkingExpanded by remember(message.id) {
                    mutableStateOf(message.status == MessageStatus.STREAMING)
                }
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { thinkingExpanded = !thinkingExpanded },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (message.status == MessageStatus.STREAMING) "Raciocínio em andamento" else "Raciocínio",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = if (thinkingExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = if (thinkingExpanded) "Recolher raciocínio" else "Expandir raciocínio",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (thinkingExpanded) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = message.thinkingContent,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (message.status == MessageStatus.QUEUED && message.content.isBlank() && message.thinkingContent.isBlank()) {
                Text(
                    text = "Avaliando contexto…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                segments.forEachIndexed { idx, segment ->
                    when (segment) {
                        is ContentSegment.TextBlock -> {
                            val suffix = if (
                                message.status == MessageStatus.STREAMING &&
                                idx == segments.lastIndex
                            ) " ▍" else ""
                            Text(
                                text = segment.text + suffix,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (message.status == MessageStatus.ERROR) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onBackground
                                },
                                modifier = if (suffix.isNotEmpty()) {
                                    Modifier.alpha(0.95f + (cursorAlpha * 0.05f))
                                } else Modifier
                            )
                        }

                        is ContentSegment.CodeBlock -> {
                            Spacer(modifier = Modifier.height(10.dp))
                            CodeSnippetCard(
                                language = segment.language,
                                code = segment.code,
                                onCopy = { onCopy(segment.code) }
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                    }
                }
            }

            if (message.status != MessageStatus.QUEUED && message.status != MessageStatus.STREAMING) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = buildString {
                            append("${message.tokenCount} tok")
                            if (message.tokensPerSecond > 0f) {
                                append(" • ${message.tokensPerSecond} tok/s")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (message.content.isNotBlank()) {
                            IconButton(
                                onClick = { onCopy(message.content) },
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("copy_message_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = stringResource(R.string.cd_copy_message),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                        if (isLastAssistantMessage && !isEngineBusy) {
                            IconButton(
                                onClick = onRegenerate,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("regenerate_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(R.string.cd_regenerate),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeSnippetCard(
    language: String,
    code: String,
    onCopy: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(14.dp)
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = language.ifBlank { "code" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            IconButton(
                onClick = onCopy,
                modifier = Modifier.minimumInteractiveComponentSize()
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = stringResource(R.string.cd_copy_message),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Text(
            text = code,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFontFamily),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(12.dp)
        )
    }
}

private fun parseMarkdownSegments(raw: String): List<ContentSegment> {
    if (!raw.contains("```")) {
        return listOf(ContentSegment.TextBlock(raw))
    }
    val segments = mutableListOf<ContentSegment>()
    var cursor = 0
    val fenceRegex = Regex("""```([a-zA-Z0-9_+-]*)\n?([\s\S]*?)(```|$)""")
    fenceRegex.findAll(raw).forEach { match ->
        if (match.range.first > cursor) {
            val before = raw.substring(cursor, match.range.first).trim()
            if (before.isNotEmpty()) segments.add(ContentSegment.TextBlock(before))
        }
        val lang = match.groupValues[1]
        val code = match.groupValues[2].trimEnd()
        segments.add(ContentSegment.CodeBlock(language = lang, code = code))
        cursor = match.range.last + 1
    }
    if (cursor < raw.length) {
        val tail = raw.substring(cursor).trim()
        if (tail.isNotEmpty()) segments.add(ContentSegment.TextBlock(tail))
    }
    return if (segments.isEmpty()) listOf(ContentSegment.TextBlock(raw)) else segments
}
