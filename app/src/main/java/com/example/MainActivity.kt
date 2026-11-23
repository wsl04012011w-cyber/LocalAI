package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.AppDiagnosticsLogger
import com.example.state.AppDestination
import com.example.state.LlamaChatAction
import com.example.state.LlamaChatViewModel
import com.example.ui.components.MinimalTopBar
import com.example.ui.components.PromptInspectorSheet
import com.example.ui.components.SessionDrawerContent
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.EngineSettingsScreen
import com.example.ui.screens.ModelsScreen
import com.example.ui.theme.LlamaLocalTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        AppDiagnosticsLogger.initialize(applicationContext)
        AppDiagnosticsLogger.info("APP", "MainActivity criada; Android SDK=${android.os.Build.VERSION.SDK_INT}")
        setContent {
            LlamaLocalTheme(darkTheme = true) {
                LlamaLocalApp()
            }
        }
    }
}

@Composable
fun LlamaLocalApp(
    viewModel: LlamaChatViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(uiState.statusBannerMessage) {
        val message = uiState.statusBannerMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.onAction(LlamaChatAction.DismissStatusBanner)
        }
    }

    BackHandler(
        enabled = drawerState.isOpen ||
            uiState.isPromptInspectorOpen ||
            uiState.currentDestination != AppDestination.CHAT
    ) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            uiState.isPromptInspectorOpen -> viewModel.onAction(LlamaChatAction.SetPromptInspectorOpen(false))
            uiState.currentDestination != AppDestination.CHAT -> {
                viewModel.onAction(LlamaChatAction.SelectDestination(AppDestination.CHAT))
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawerContent(
                sessions = uiState.sessions,
                activeSessionId = uiState.activeSessionId,
                currentDestination = uiState.currentDestination,
                isBusy = uiState.isGenerating || uiState.isLoadingModel,
                onNavigate = { dest ->
                    viewModel.onAction(LlamaChatAction.SelectDestination(dest))
                    scope.launch { drawerState.close() }
                },
                onCreateSession = {
                    viewModel.onAction(LlamaChatAction.CreateNewSession)
                    scope.launch { drawerState.close() }
                },
                onSelectSession = { id ->
                    viewModel.onAction(LlamaChatAction.SelectSession(id))
                    scope.launch { drawerState.close() }
                },
                onDeleteSession = { id ->
                    viewModel.onAction(LlamaChatAction.DeleteSession(id))
                }
            )
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                MinimalTopBar(
                    uiState = uiState,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onNavigateBackToChat = {
                        viewModel.onAction(LlamaChatAction.SelectDestination(AppDestination.CHAT))
                    },
                    onModelPillClick = {
                        viewModel.onAction(LlamaChatAction.SelectDestination(AppDestination.MODELS))
                    },
                    onOpenPromptInspector = {
                        viewModel.onAction(LlamaChatAction.SetPromptInspectorOpen(true))
                    },
                    onNewChat = {
                        viewModel.onAction(LlamaChatAction.CreateNewSession)
                    }
                )

                Box(modifier = Modifier.weight(1f)) {
                    when (uiState.currentDestination) {
                        AppDestination.CHAT -> ChatScreen(
                            uiState = uiState,
                            onAction = viewModel::onAction
                        )
                        AppDestination.MODELS -> ModelsScreen(
                            uiState = uiState,
                            onAction = viewModel::onAction
                        )
                        AppDestination.ENGINE -> EngineSettingsScreen(
                            uiState = uiState,
                            onAction = viewModel::onAction
                        )
                    }
                }
            }
        }
    }

    if (uiState.isPromptInspectorOpen) {
        PromptInspectorSheet(
            uiState = uiState,
            formattedPrompt = viewModel.buildFormattedPromptPreview(),
            onCopyPrompt = { text -> clipboard.setText(AnnotatedString(text)) },
            onDismiss = { viewModel.onAction(LlamaChatAction.SetPromptInspectorOpen(false)) }
        )
    }
}
