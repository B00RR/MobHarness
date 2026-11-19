package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
fun MobHarnApp(
    chatViewModel: ChatViewModel,
    studioViewModel: StudioViewModel,
    memoryViewModel: MemoryViewModel,
    modelHubViewModel: ModelHubViewModel
) {
    var currentIndex by rememberSaveable { mutableIntStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Subscribe ONLY to incomingSharedPrompt so token streaming in Chat NEVER recomposes the root app
    val incomingSharedPrompt by chatViewModel.incomingSharedPrompt.collectAsStateWithLifecycle()

    LaunchedEffect(incomingSharedPrompt) {
        if (incomingSharedPrompt != null) {
            currentIndex = 0
        }
    }

    val showMessage: (String) -> Unit = remember(scope, snackbarHostState) {
        { msg ->
            scope.launch {
                snackbarHostState.currentSnackbarData?.dismiss()
                snackbarHostState.showSnackbar(msg)
            }
        }
    }

    BackHandler(enabled = currentIndex != 0) {
        currentIndex = 0
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            // TopAppBar handles statusBars insets; NavigationBar handles navigationBars insets; ChatScreen handles imePadding
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface
                    )
                }
            },
            bottomBar = {
                if (!useRail) {
                    Column {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                        )
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            tonalElevation = 0.dp,
                            windowInsets = NavigationBarDefaults.windowInsets
                        ) {
                            DESTINATIONS.forEachIndexed { idx, dest ->
                                val selected = currentIndex == idx
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { currentIndex = idx },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                            contentDescription = stringResource(dest.labelRes)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(dest.labelRes),
                                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                    ),
                                    modifier = Modifier.testTag("nav_item_${dest.route}")
                                )
                            }
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
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ) {
                        DESTINATIONS.forEachIndexed { idx, dest ->
                            val selected = currentIndex == idx
                            NavigationRailItem(
                                selected = selected,
                                onClick = { currentIndex = idx },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
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
                    Crossfade(
                        targetState = currentIndex,
                        animationSpec = tween(durationMillis = 150),
                        label = "tab_crossfade"
                    ) { tab ->
                        when (tab) {
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
}
