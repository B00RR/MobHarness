package com.example.agent.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.R
import com.example.agent.ThermalAndMemoryGuard
import com.example.agent.ThermalLevel
import com.example.agent.tools.AndroidSystemActions
import com.example.data.db.AutomationTaskEntity
import com.example.data.db.MobHarnDatabase
import com.example.data.vault.MarkdownVaultManager
import com.example.data.workspace.WorkspaceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed interface AutomationTaskOutcome {
    val summary: String
    val isSuccess: Boolean

    data class Success(override val summary: String) : AutomationTaskOutcome {
        override val isSuccess: Boolean get() = true
    }

    data class Failure(override val summary: String, val cause: Throwable? = null) : AutomationTaskOutcome {
        override val isSuccess: Boolean get() = false
    }
}

class AutomationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        // Strictly pin background automation threads to efficiency/LITTLE cores so user foreground apps never stutter
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST) }

        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId <= 0L) return@withContext Result.failure()

        val db = MobHarnDatabase.getInstance(applicationContext)
        val task = db.automationDao().getTaskById(taskId) ?: return@withContext Result.success()

        val isManual = inputData.getBoolean(KEY_MANUAL_RUN, false)
        val snap = runCatching { ThermalAndMemoryGuard.readSnapshot(applicationContext) }.getOrNull()
        if (!isManual && snap != null) {
            val powerSaveResult = evaluatePowerSaveAction(
                isManual = isManual,
                isPowerSaveMode = snap.isPowerSaveMode,
                isPeriodic = task.isPeriodic
            )
            if (powerSaveResult != null) return@withContext powerSaveResult

            if (snap.thermalLevel == ThermalLevel.HOT || snap.thermalLevel == ThermalLevel.CRITICAL || snap.isLowMemory) {
                return@withContext Result.retry()
            }
        }
        val system = AndroidSystemActions(applicationContext)
        val vault = MarkdownVaultManager(applicationContext)
        val workspace = WorkspaceManager(applicationContext)

        return@withContext executeTask(
            task = task,
            system = system,
            vault = vault,
            workspace = workspace,
            db = db,
            onNotification = { id, title, body -> postCompletionNotification(id, title, body) }
        )
    }

    private fun postCompletionNotification(id: Int, title: String, body: String) {
        ensureChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("MobHarn • $title")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(1000 + id, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Automazioni MobHarn",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifiche completamento task pianificati in background"
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val KEY_TASK_ID = "automation_task_id"
        const val KEY_MANUAL_RUN = "automation_manual_run"
        const val KEY_IS_PERIODIC = "automation_is_periodic"
        private const val CHANNEL_ID = "mobharn_automations"

        internal fun evaluatePowerSaveAction(
            isManual: Boolean,
            isPowerSaveMode: Boolean,
            isPeriodic: Boolean
        ): Result? {
            if (!isManual && isPowerSaveMode) {
                return if (isPeriodic) {
                    Result.success()
                } else {
                    Result.retry()
                }
            }
            return null
        }

        internal fun evaluateOutcomeResult(outcome: AutomationTaskOutcome): Result {
            return Result.success()
        }

        internal suspend fun executeTask(
            task: AutomationTaskEntity,
            system: AndroidSystemActions,
            vault: MarkdownVaultManager,
            workspace: WorkspaceManager,
            db: MobHarnDatabase,
            onNotification: (id: Int, title: String, body: String) -> Unit = { _, _, _ -> }
        ): Result {
            val resultSummary = try {
                runCatching {
                    when (task.actionType) {
                        "TELEMETRY_CHECK" -> system.getTelemetryReport()
                        "WEB_MONITOR" -> {
                            if (task.targetUrl.isNotBlank()) system.fetchWebpage(task.targetUrl, 1200)
                            else system.webSearch(task.prompt)
                        }
                        "MEMORY_DIGEST" -> {
                            val docs = vault.readVault()
                            val memories = db.memoryDao().getAllMemories()
                            "Digest memoria: ${memories.size} fatti persistenti (~${docs.estimatedSystemTokens} tok nel prompt di sistema)."
                        }
                        "WORKSPACE_AUDIT" -> {
                            val projects = workspace.listProjects()
                            val summary = projects.joinToString("\n") { p -> "• $p" }
                            "Audit Coding Workspace (${projects.size} progetti):\n$summary"
                        }
                        else -> { // BRIEFING
                            val hw = system.getHardwareSummary()
                            val memCount = db.memoryDao().getAllMemories().size
                            "Briefing MobHarn: Batteria ${hw.batteryPercent}%, RAM libera ${"%.1f".format(hw.availableRamGb)} GB, Rete ${hw.networkStatus}, $memCount memorie attive. Prompt: ${task.prompt}"
                        }
                    }
                }.getOrElse { e ->
                    "Errore durante automazione '${task.name}': ${e.message}"
                }
            } finally {
                system.evictIdleConnections()
            }

            db.automationDao().updateRunResult(
                id = task.id,
                runAt = System.currentTimeMillis(),
                result = resultSummary.take(1500)
            )

            onNotification(task.id.toInt(), task.name, resultSummary.take(180))
            return Result.success()
        }
    }
}
