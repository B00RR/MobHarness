package com.example.agent.automation

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.R
import com.example.agent.tools.AndroidSystemActions
import com.example.data.db.HermesDatabase
import com.example.data.vault.MarkdownVaultManager
import com.example.data.workspace.WorkspaceManager

class AutomationWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        if (taskId <= 0L) return Result.failure()

        val db = HermesDatabase.getInstance(applicationContext)
        val task = db.automationDao().getTaskById(taskId) ?: return Result.success()
        val system = AndroidSystemActions(applicationContext)
        val vault = MarkdownVaultManager(applicationContext)
        val workspace = WorkspaceManager(applicationContext)

        val resultSummary = runCatching {
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
                    val summary = projects.joinToString("\n") { p ->
                        "• $p"
                    }
                    "Audit Coding Workspace (${projects.size} progetti):\n$summary"
                }
                else -> { // BRIEFING
                    val hw = system.getHardwareSummary()
                    val memCount = db.memoryDao().getAllMemories().size
                    "Briefing Hermes: Batteria ${hw.batteryPercent}%, RAM libera ${"%.1f".format(hw.availableRamGb)} GB, Rete ${hw.networkStatus}, $memCount memorie attive. Prompt: ${task.prompt}"
                }
            }
        }.getOrElse { e ->
            "Errore durante automazione '${task.name}': ${e.message}"
        }

        db.automationDao().updateRunResult(
            id = task.id,
            runAt = System.currentTimeMillis(),
            result = resultSummary.take(1500)
        )

        postCompletionNotification(task.id.toInt(), task.name, resultSummary.take(180))
        return Result.success()
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
            .setContentTitle("Hermes Edge • $title")
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
                "Automazioni Hermes Edge",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifiche completamento task pianificati in background"
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val KEY_TASK_ID = "automation_task_id"
        private const val CHANNEL_ID = "hermes_automations"
    }
}
