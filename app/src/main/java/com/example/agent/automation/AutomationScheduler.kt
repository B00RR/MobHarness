package com.example.agent.automation

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
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

        if (task.isPeriodic) {
            val interval = task.intervalMinutes.coerceAtLeast(15L)
            val request = PeriodicWorkRequestBuilder<AutomationWorker>(interval, TimeUnit.MINUTES)
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
