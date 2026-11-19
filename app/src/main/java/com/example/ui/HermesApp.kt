package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.MemoryScreen
import com.example.ui.screens.ModelHubScreen
import com.example.ui.screens.StudioScreen
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.MemoryViewModel
import com.example.ui.viewmodel.ModelHubViewModel
import com.example.ui.viewmodel.StudioViewModel
import kotlinx.coroutines.launch

private data class NavDestination(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val DESTINATIONS = listOf(
    NavDestination("chat", R.string.nav_chat, Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline),
    NavDestination("studio", R.string.nav_studio, Icons.Filled.Code, Icons.Outlined.Code),
    NavDestination("memory", R.string.nav_memory, Icons.Filled.Psychology, Icons.Outlined.Psychology),
    NavDestination("model", R.string.nav_model, Icons.Filled.Memory, Icons.Outlined.Memory)
)

@Composable
fun HermesApp(
    chatViewModel: ChatViewModel,
    studioViewModel: StudioViewModel,
    memoryViewModel: MemoryViewModel,
    modelHubViewModel: ModelHubViewModel
) {
    var currentIndex by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val chatUiState by chatViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(chatUiState.incomingSharedPrompt) {
        if (chatUiState.incomingSharedPrompt != null) {
            currentIndex = 0
        }
    }

    val showMessage: (String) -> Unit = { msg ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    // Return cleanly to Chat from any secondary tab via system back
    BackHandler(enabled = currentIndex != 0) {
        currentIndex = 0
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    NavigationBar {
                        DESTINATIONS.forEachIndexed { idx, dest ->
                            NavigationBarItem(
                                selected = currentIndex == idx,
                                onClick = { currentIndex = idx },
                                icon = {
                                    Icon(
                                        imageVector = if (currentIndex == idx) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes)) },
                                modifier = Modifier.testTag("nav_item_${dest.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail {
                        DESTINATIONS.forEachIndexed { idx, dest ->
                            NavigationRailItem(
                                selected = currentIndex == idx,
                                onClick = { currentIndex = idx },
                                icon = {
                                    Icon(
                                        imageVector = if (currentIndex == idx) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = stringResource(dest.labelRes)
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes)) },
                                modifier = Modifier.testTag("rail_item_${dest.route}")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    when (currentIndex) {
                        0 -> ChatScreen(
                            viewModel = chatViewModel,
                            onNavigateToModelHub = { currentIndex = 3 },
                            onNavigateToStudio = { currentIndex = 1 },
                            onShowMessage = showMessage
                        )
                        1 -> StudioScreen(
                            viewModel = studioViewModel,
                            onShowMessage = showMessage
                        )
                        2 -> MemoryScreen(
                            viewModel = memoryViewModel,
                            onShowMessage = showMessage
                        )
                        3 -> ModelHubScreen(
                            viewModel = modelHubViewModel,
                            onShowMessage = showMessage
                        )
                    }
                }
            }
        }
    }
}
