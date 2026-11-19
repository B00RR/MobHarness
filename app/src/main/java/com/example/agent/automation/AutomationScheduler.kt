package com.example.agent.automation

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.data.db.AutomationTaskEntity
import java.util.concurrent.TimeUnit

class AutomationScheduler(context: Context) {

    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun schedule(task: AutomationTaskEntity) {
        if (!task.enabled) {
            cancel(task)
            return
        }
        val input = Data.Builder()
            .putLong(AutomationWorker.KEY_TASK_ID, task.id)
            .build()

        // Battery-friendly OS constraints: never drain low battery, and only wake radio for web tasks when connected
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .apply {
                if (task.actionType == "WEB_MONITOR") {
                    setRequiredNetworkType(NetworkType.CONNECTED)
                }
            }
            .build()

        if (task.isPeriodic) {
            val interval = task.intervalMinutes.coerceAtLeast(15L)
            // Flex interval lets Android batch wakeups in Doze maintenance windows
            val flexInterval = (interval / 4L).coerceAtLeast(5L)
            val request = PeriodicWorkRequestBuilder<AutomationWorker>(
                interval, TimeUnit.MINUTES,
                flexInterval, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInputData(input)
                .addTag(task.workTag)
                .build()
            workManager.enqueueUniquePeriodicWork(
                task.workTag,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        } else {
            val delayMin = task.intervalMinutes.coerceAtLeast(1L)
            val request = OneTimeWorkRequestBuilder<AutomationWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMin, TimeUnit.MINUTES)
                .setInputData(input)
                .addTag(task.workTag)
                .build()
            workManager.enqueueUniqueWork(
                task.workTag,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    fun runNow(task: AutomationTaskEntity) {
        val input = Data.Builder()
            .putLong(AutomationWorker.KEY_TASK_ID, task.id)
            .putBoolean(AutomationWorker.KEY_MANUAL_RUN, true)
            .build()
        val request = OneTimeWorkRequestBuilder<AutomationWorker>()
            .setInputData(input)
            .addTag("${task.workTag}_immediate")
            .build()
        workManager.enqueue(request)
    }

    fun cancel(task: AutomationTaskEntity) {
        workManager.cancelUniqueWork(task.workTag)
    }
}
