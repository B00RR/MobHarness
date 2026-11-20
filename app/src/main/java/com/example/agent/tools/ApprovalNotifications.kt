package com.example.agent.tools

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.di.MobHarnAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

private const val CHANNEL = "command_approvals"
private const val NOTICE = 0x415050

class ApprovalNotifications(context: Context, gate: AgentApprovalCoordinator) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val foreground = MutableStateFlow(false)

    init {
        combine(gate.pendingApproval, foreground) { request, visible -> request.takeUnless { visible } }
            .onEach { request ->
                if (request == null) manager.cancel(NOTICE) else show(request)
            }.launchIn(CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate))
    }

    private fun show(request: PendingToolApproval) {
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL,
            "Approvazioni dei comandi", NotificationManager.IMPORTANCE_HIGH).apply { lockscreenVisibility = Notification.VISIBILITY_SECRET })
        val message = if (request.toolName == "type_screen_text") "Digitare testo: ${request.argumentsSummary}" else request.description
        val builder = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("MobHarn · Conferma azione")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOnlyAlertOnce(false).setTimeoutAfter(120_000L)
        fun action(code: String, label: String) {
            val intent = Intent(app, ApprovalActionReceiver::class.java).setAction(code)
                .setData(Uri.fromParts("mobharn-approval", request.id, null))
            val pending = PendingIntent.getBroadcast(app, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(NotificationCompat.Action.Builder(0, label, pending)
                .setAuthenticationRequired(code != "deny").build())
        }
        action("allow", "Approva")
        if (request.rememberKey != null) action("save", "Salva e approva")
        action("deny", "Rifiuta")
        try { manager.notify(NOTICE, builder.build()) } catch (_: SecurityException) { /* Permission revoked; chat remains available. */ }
    }
}

class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.data?.scheme != "mobharn-approval") return
        val id = intent.data?.schemeSpecificPart ?: return
        val code = intent.action ?: return
        if (code !in setOf("allow", "save", "deny")) return
        if (code != "deny" && context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false) return
        MobHarnAppContainer.getInstance(context).approvalCoordinator.resolve(id, code != "deny", code == "save")
    }
}
