package com.example

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.agent.tools.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ApprovalNotificationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun onlyExactSavedKeySurvivesRecreationAndCanBeRevoked() = runTest {
        val prefs = context.getSharedPreferences("test_rules", 0)
        val gate = AgentApprovalCoordinator(prefs)
        val first = async(start = CoroutineStart.UNDISPATCHED) { gate.requestApproval("alarm", "android", "A", "", "key1") }
        val id = gate.pendingApproval.value!!.id
        gate.resolve("old", true, true)
        assertFalse(first.isCompleted)
        gate.resolve(id, true, true)
        assertTrue(first.await())
        val restarted = AgentApprovalCoordinator(prefs)
        assertTrue(restarted.requestApproval("alarm", "android", "A", "", "key1"))
        val other = async(start = CoroutineStart.UNDISPATCHED) { restarted.requestApproval("alarm", "android", "A", "", "key2") }
        restarted.resolve(restarted.pendingApproval.value!!.id, false)
        assertFalse(other.await())
        restarted.clearRemembered()
        assertFalse(restarted.requestApproval("alarm", "android", "A", "", "key1")) // times out in virtual time
    }

    @Test fun backgroundActionTargetsOnlyLiveIdAndNotificationClearsInForeground() = runTest {
        val gate = AgentApprovalCoordinator()
        val notifier = ApprovalNotifications(context, gate)
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val main = shadowOf(Looper.getMainLooper())
        val result = async(start = CoroutineStart.UNDISPATCHED) { gate.requestApproval("type_screen_text", "phone", "sensitive value", "campo attivo") }
        main.idle()
        val notice = mgr.activeNotifications.single().notification
        assertEquals(Notification.VISIBILITY_SECRET, notice.visibility)
        assertEquals(listOf("Approva", "Rifiuta"), notice.actions.map { it.title.toString() })
        assertFalse(notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("sensitive value"))
        val intent = shadowOf(notice.actions.first().actionIntent).savedIntent
        assertEquals(gate.pendingApproval.value!!.id, intent.data?.schemeSpecificPart)
        assertEquals(ApprovalActionReceiver::class.java.name, intent.component?.className)
        notifier.foreground.value = true
        main.idle()
        assertTrue(mgr.activeNotifications.isEmpty())
        gate.resolve(gate.pendingApproval.value!!.id, false)
        assertFalse(result.await())
    }

    @Test fun rememberedRuleDoesNotExtendToDifferentTargetOrLockedDevice() = runTest {
        val prefs = context.getSharedPreferences("test_scope_rules", 0).apply { edit().clear().commit() }
        var unlocked = true
        val gate = AgentApprovalCoordinator(prefs) { unlocked }

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            gate.requestApproval("set_alarm", "android_actions", "Sveglia 07:30", "07:30 (Lavoro)", "alarm_0730_lavoro")
        }
        gate.resolve(gate.pendingApproval.value!!.id, approved = true, remember = true)
        assertTrue(first.await())

        // Exact same key while unlocked auto-approves
        assertTrue(gate.requestApproval("set_alarm", "android_actions", "Sveglia 07:30", "07:30 (Lavoro)", "alarm_0730_lavoro"))

        // Different target/argument key MUST prompt again
        val diffTarget = async(start = CoroutineStart.UNDISPATCHED) {
            gate.requestApproval("set_alarm", "android_actions", "Sveglia 08:00", "08:00 (Altro)", "alarm_0800_altro")
        }
        assertNotNull("Different target must require fresh approval", gate.pendingApproval.value)
        gate.resolve(gate.pendingApproval.value!!.id, approved = false)
        assertFalse(diffTarget.await())

        // Same saved key while device is locked MUST NOT auto-approve
        unlocked = false
        val whileLocked = async(start = CoroutineStart.UNDISPATCHED) {
            gate.requestApproval("set_alarm", "android_actions", "Sveglia 07:30", "07:30 (Lavoro)", "alarm_0730_lavoro")
        }
        assertNotNull("Locked device must not silently auto-approve remembered grant", gate.pendingApproval.value)
        gate.resolve(gate.pendingApproval.value!!.id, approved = false)
        assertFalse(whileLocked.await())
    }
}
