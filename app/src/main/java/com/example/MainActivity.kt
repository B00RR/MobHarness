package com.example

import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import coil.Coil
import com.example.agent.ThermalAndMemoryGuard
import com.example.data.prefs.AppPreferences
import com.example.di.MobHarnAppContainer
import com.example.ui.MobHarnApp
import com.example.ui.theme.MobHarnTheme
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.MemoryViewModel
import com.example.ui.viewmodel.ModelHubViewModel
import com.example.ui.viewmodel.StudioViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var container: MobHarnAppContainer
    private lateinit var chatVm: ChatViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Use process-scoped singleton container so rotation/config changes never duplicate engines
        container = MobHarnAppContainer.getInstance(applicationContext)

        // Clean up temporary chat/camera image files older than 24h in background
        lifecycleScope.launch(Dispatchers.IO) {
            ThermalAndMemoryGuard.pruneStaleImageCaches(applicationContext)
        }

        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return when {
                    modelClass.isAssignableFrom(ChatViewModel::class.java) ->
                        ChatViewModel(applicationContext, container) as T
                    modelClass.isAssignableFrom(StudioViewModel::class.java) ->
                        StudioViewModel(applicationContext, container) as T
                    modelClass.isAssignableFrom(MemoryViewModel::class.java) ->
                        MemoryViewModel(applicationContext, container) as T
                    modelClass.isAssignableFrom(ModelHubViewModel::class.java) ->
                        ModelHubViewModel(applicationContext, container) as T
                    else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }

        chatVm = ViewModelProvider(this, factory)[ChatViewModel::class.java]
        val studioVm = ViewModelProvider(this, factory)[StudioViewModel::class.java]
        val memoryVm = ViewModelProvider(this, factory)[MemoryViewModel::class.java]
        val modelHubVm = ViewModelProvider(this, factory)[ModelHubViewModel::class.java]

        handleShareIntent(intent)

        setContent {
            val prefs by container.preferencesRepository.preferences
                .collectAsStateWithLifecycle(initialValue = AppPreferences())

            MobHarnTheme(
                themeMode = prefs.themeMode,
                dynamicColor = prefs.useDynamicColor
            ) {
                MobHarnApp(
                    chatViewModel = chatVm,
                    studioViewModel = studioVm,
                    memoryViewModel = memoryVm,
                    modelHubViewModel = modelHubVm
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (::container.isInitialized) {
            container.runtimeManager.onAppForegrounded()
        }
    }

    override fun onStop() {
        super.onStop()
        if (::container.isInitialized && !isChangingConfigurations) {
            container.systemActions.evictIdleConnections()
            lifecycleScope.launch {
                val autoUnload = runCatching {
                    container.preferencesRepository.preferences.first().autoUnloadInBackground
                }.getOrDefault(true)
                container.runtimeManager.onAppBackgrounded(autoUnloadEnabled = autoUnload)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (!::container.isInitialized) return
        // Immediately release decoded Coil bitmap memory cache under any UI hidden / memory pressure
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            runCatching { Coil.imageLoader(applicationContext).memoryCache?.clear() }
            container.runtimeManager.trimConversationCacheAsync()
        }
        // Under serious OS memory pressure, unload LiteRT-LM engine (will transparently reload on next prompt)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
        ) {
            container.runtimeManager.autoUnloadForMemoryAsync()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val sharedImageUri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        if (sharedText != null || sharedImageUri != null) {
            chatVm.receiveSharedIntent(sharedText, sharedImageUri)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && !isChangingConfigurations && ::container.isInitialized) {
            container.runtimeManager.trimConversationCacheAsync()
        }
    }
}
