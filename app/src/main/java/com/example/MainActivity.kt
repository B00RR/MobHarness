package com.example

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
import com.example.data.prefs.AppPreferences
import com.example.di.HermesAppContainer
import com.example.ui.HermesApp
import com.example.ui.theme.HermesEdgeTheme
import com.example.ui.viewmodel.ChatViewModel
import com.example.ui.viewmodel.MemoryViewModel
import com.example.ui.viewmodel.ModelHubViewModel
import com.example.ui.viewmodel.StudioViewModel

class MainActivity : ComponentActivity() {

    private lateinit var container: HermesAppContainer
    private lateinit var chatVm: ChatViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        container = HermesAppContainer(applicationContext)

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
                        ModelHubViewModel(container) as T
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

            HermesEdgeTheme(
                themeMode = prefs.themeMode,
                dynamicColor = prefs.useDynamicColor
            ) {
                HermesApp(
                    chatViewModel = chatVm,
                    studioViewModel = studioVm,
                    memoryViewModel = memoryVm,
                    modelHubViewModel = modelHubVm
                )
            }
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
        if (isFinishing && ::container.isInitialized) {
            container.runtimeManager.close()
        }
    }
}
