package com.example.di

import android.content.Context
import com.example.agent.LiteRtRuntimeManager
import com.example.agent.SystemPromptAssembler
import com.example.agent.automation.AutomationScheduler
import com.example.agent.sandbox.CodeSandboxRunner
import com.example.agent.tools.AgentApprovalCoordinator
import com.example.agent.tools.AndroidSystemActions
import com.example.agent.tools.HermesToolkitRegistry
import com.example.agent.tools.ToolExecutionMonitor
import com.example.data.HermesRepository
import com.example.data.db.HermesDatabase
import com.example.data.prefs.PreferencesRepository
import com.example.data.vault.MarkdownVaultManager
import com.example.data.workspace.WorkspaceManager

class HermesAppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: HermesDatabase = HermesDatabase.getInstance(appContext)
    val vaultManager: MarkdownVaultManager = MarkdownVaultManager(appContext)
    val workspaceManager: WorkspaceManager = WorkspaceManager(appContext)
    val preferencesRepository: PreferencesRepository = PreferencesRepository(appContext)

    val repository: HermesRepository = HermesRepository(
        context = appContext,
        db = database,
        vault = vaultManager,
        workspace = workspaceManager,
        prefs = preferencesRepository
    )

    val systemActions: AndroidSystemActions = AndroidSystemActions(appContext)
    val codeSandbox: CodeSandboxRunner = CodeSandboxRunner(appContext)
    val automationScheduler: AutomationScheduler = AutomationScheduler(appContext)
    val approvalCoordinator: AgentApprovalCoordinator = AgentApprovalCoordinator()

    val toolMonitor: ToolExecutionMonitor = ToolExecutionMonitor(
        repository = repository,
        approvalCoordinator = approvalCoordinator
    )

    val toolkitRegistry: HermesToolkitRegistry = HermesToolkitRegistry(
        repository = repository,
        system = systemActions,
        sandbox = codeSandbox,
        scheduler = automationScheduler,
        monitor = toolMonitor
    )

    val promptAssembler: SystemPromptAssembler = SystemPromptAssembler(repository)

    val runtimeManager: LiteRtRuntimeManager = LiteRtRuntimeManager(
        context = appContext,
        toolkitRegistry = toolkitRegistry
    )
}
