package com.example.di

import android.content.Context
import com.example.agent.LiteRtRuntimeManager
import com.example.agent.SystemPromptAssembler
import com.example.agent.automation.AutomationScheduler
import com.example.agent.sandbox.CodeSandboxRunner
import com.example.agent.tools.AgentApprovalCoordinator
import com.example.agent.tools.AndroidSystemActions
import com.example.agent.tools.MobHarnToolkitRegistry
import com.example.agent.tools.ToolExecutionMonitor
import com.example.data.MobHarnRepository
import com.example.data.db.MobHarnDatabase
import com.example.data.prefs.PreferencesRepository
import com.example.data.vault.MarkdownVaultManager
import com.example.data.workspace.WorkspaceManager

/**
 * Process-wide lazy dependency container for MobHarn.
 * Ensures Activity recreations never duplicate LiteRT-LM engines, executors, or HTTP pools,
 * and defers allocating heavy subsystems until first actual use.
 */
class MobHarnAppContainer private constructor(context: Context) {
    private val appContext = context.applicationContext

    val database: MobHarnDatabase by lazy { MobHarnDatabase.getInstance(appContext) }
    val vaultManager: MarkdownVaultManager by lazy { MarkdownVaultManager(appContext) }
    val workspaceManager: WorkspaceManager by lazy { WorkspaceManager(appContext) }
    val preferencesRepository: PreferencesRepository by lazy { PreferencesRepository(appContext) }

    val repository: MobHarnRepository by lazy {
        MobHarnRepository(
            context = appContext,
            db = database,
            vault = vaultManager,
            workspace = workspaceManager,
            prefs = preferencesRepository
        )
    }

    val systemActions: AndroidSystemActions by lazy { AndroidSystemActions(appContext) }
    val codeSandbox: CodeSandboxRunner by lazy { CodeSandboxRunner(appContext) }
    val automationScheduler: AutomationScheduler by lazy { AutomationScheduler(appContext) }
    val approvalCoordinator: AgentApprovalCoordinator by lazy {
        AgentApprovalCoordinator(appContext.getSharedPreferences("approval_rules", Context.MODE_PRIVATE)) {
            appContext.getSystemService(android.app.KeyguardManager::class.java)?.isDeviceLocked == false
        }
    }

    val approvalNotifications by lazy { com.example.agent.tools.ApprovalNotifications(appContext, approvalCoordinator) }

    val toolMonitor: ToolExecutionMonitor by lazy {
        ToolExecutionMonitor(
            repository = repository,
            approvalCoordinator = approvalCoordinator
        )
    }

    val toolkitRegistry: MobHarnToolkitRegistry by lazy {
        MobHarnToolkitRegistry(
            repository = repository,
            system = systemActions,
            sandbox = codeSandbox,
            scheduler = automationScheduler,
            monitor = toolMonitor
        )
    }

    val promptAssembler: SystemPromptAssembler by lazy { SystemPromptAssembler(repository) }

    val runtimeManager: LiteRtRuntimeManager by lazy {
        LiteRtRuntimeManager(
            context = appContext,
            toolkitRegistry = toolkitRegistry
        )
    }

    companion object {
        @Volatile
        private var INSTANCE: MobHarnAppContainer? = null

        fun getInstance(context: Context): MobHarnAppContainer =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: MobHarnAppContainer(context.applicationContext).also { INSTANCE = it }
            }
    }
}
