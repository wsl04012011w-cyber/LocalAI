package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.engine.EngineLifecycleState
import com.example.state.AppDestination
import com.example.state.LlamaChatUiState
import com.example.ui.theme.EmeraldStatus

@Composable
fun MinimalTopBar(
    uiState: LlamaChatUiState,
    onOpenDrawer: () -> Unit,
    onNavigateBackToChat: () -> Unit,
    onModelPillClick: () -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (uiState.currentDestination == AppDestination.CHAT) {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("open_sessions_button")
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                    horizontalAlignment = Alignment.Start
                ) {
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(MaterialTheme.colorScheme.onBackground)
                    )
                    Box(
                        modifier = Modifier
                            .width(11.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(MaterialTheme.colorScheme.onBackground)
                    )
                }
            }

            val hasMessages = uiState.activeSession?.messages?.isNotEmpty() == true
            if (uiState.loadedModel != null || hasMessages) {
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onModelPillClick)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag("model_status_pill"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val dotColor = when (uiState.engineState) {
                        EngineLifecycleState.READY -> EmeraldStatus
                        EngineLifecycleState.GENERATING -> MaterialTheme.colorScheme.primary
                        EngineLifecycleState.LOADING_MODEL -> MaterialTheme.colorScheme.tertiary
                        EngineLifecycleState.ERROR -> MaterialTheme.colorScheme.error
                        EngineLifecycleState.UNLOADED -> MaterialTheme.colorScheme.outline
                    }
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(dotColor)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = uiState.loadedModel?.name ?: "Sem modelo",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            IconButton(
                onClick = onNewChat,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("new_chat_button")
            ) {
                Icon(
                    imageVector = Icons.Outlined.AddComment,
                    contentDescription = stringResource(R.string.cd_new_chat),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onNavigateBackToChat,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("back_to_chat_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Voltar ao chat",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = when (uiState.currentDestination) {
                        AppDestination.MODELS -> "Modelos"
                        AppDestination.ENGINE -> "Configurações"
                        AppDestination.CHAT -> ""
                    },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.width(48.dp))
        }
    }
}
