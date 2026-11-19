package com.example.agent

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import java.io.File
import java.io.FileOutputStream

enum class ThermalLevel(val labelIt: String) {
    COOL("Fresco"),
    WARM("Tiepido"),
    HOT("Caldo"),
    CRITICAL("Surriscaldato")
}

data class ThermalAndMemorySnapshot(
    val thermalLevel: ThermalLevel,
    val batteryTempC: Float,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val isPowerSaveMode: Boolean,
    val thermalHeadroom: Float?,
    val appHeapMb: Int,
    val nativeHeapMb: Int,
    val systemFreeRamGb: Double,
    val isLowMemory: Boolean
) {
    val isBatteryConstrained: Boolean
        get() = isPowerSaveMode || (!isCharging && batteryPercent in 1..20)
}

data class ThermalPacingPolicy(
    val threadNicePriority: Int,
    val maxOutputTokens: Int,
    val interChunkDelayMs: Long,
    val allowThinking: Boolean,
    val thinkingBudgetTokens: Int,
    val pauseForOverheat: Boolean,
    val statusNote: String? = null
)

object ThermalAndMemoryGuard {

    fun readSnapshot(context: Context): ThermalAndMemorySnapshot {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batteryTempC = (batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val levelRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scaleRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPercent = if (levelRaw >= 0 && scaleRaw > 0) (levelRaw * 100 / scaleRaw) else 100
        val statusRaw = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = statusRaw == BatteryManager.BATTERY_STATUS_CHARGING ||
            statusRaw == BatteryManager.BATTERY_STATUS_FULL
        val isPowerSaveMode = runCatching { pm.isPowerSaveMode }.getOrDefault(false)

        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { pm.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }

        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { pm.getThermalHeadroom(5) }.getOrNull()?.takeIf { !it.isNaN() && it >= 0f }
        } else {
            null
        }

        val level = when {
            thermalStatus >= PowerManager.THERMAL_STATUS_CRITICAL || batteryTempC >= 44.5f -> ThermalLevel.CRITICAL
            thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE || batteryTempC >= 39.5f || (headroom != null && headroom >= 0.88f) -> ThermalLevel.HOT
            thermalStatus >= PowerManager.THERMAL_STATUS_LIGHT || batteryTempC >= 36.5f || (headroom != null && headroom >= 0.72f) -> ThermalLevel.WARM
            else -> ThermalLevel.COOL
        }

        val rt = Runtime.getRuntime()
        val appHeapMb = ((rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L)).toInt()
        val nativeHeapMb = (Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)).toInt()

        return ThermalAndMemorySnapshot(
            thermalLevel = level,
            batteryTempC = batteryTempC,
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            isPowerSaveMode = isPowerSaveMode,
            thermalHeadroom = headroom,
            appHeapMb = appHeapMb,
            nativeHeapMb = nativeHeapMb,
            systemFreeRamGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0),
            isLowMemory = memInfo.lowMemory
        )
    }

    fun computePacingPolicy(
        snapshot: ThermalAndMemorySnapshot,
        ecoModeEnabled: Boolean,
        userRequestedThinking: Boolean
    ): ThermalPacingPolicy {
        // Hard overheat protection always takes precedence
        if (snapshot.thermalLevel == ThermalLevel.CRITICAL) {
            return ThermalPacingPolicy(
                threadNicePriority = Process.THREAD_PRIORITY_BACKGROUND,
                maxOutputTokens = 256,
                interChunkDelayMs = 45L,
                allowThinking = false,
                thinkingBudgetTokens = 0,
                pauseForOverheat = true,
                statusNote = "Dispositivo molto caldo (${snapshot.batteryTempC}°C): pausa termica."
            )
        }

        // System Power Save Mode or low battery (<=20% unplugged) enforces ultra-low-power pacing
        if (snapshot.isBatteryConstrained) {
            val isHot = snapshot.thermalLevel == ThermalLevel.HOT
            return ThermalPacingPolicy(
                threadNicePriority = Process.THREAD_PRIORITY_BACKGROUND,
                maxOutputTokens = if (isHot) 320 else 384,
                interChunkDelayMs = if (isHot) 30L else 14L,
                allowThinking = false,
                thinkingBudgetTokens = 0,
                pauseForOverheat = false,
                statusNote = "Risparmio batteria attivo (${snapshot.batteryPercent}%)"
            )
        }

        return when (snapshot.thermalLevel) {
            ThermalLevel.HOT -> ThermalPacingPolicy(
                threadNicePriority = Process.THREAD_PRIORITY_BACKGROUND,
                maxOutputTokens = if (ecoModeEnabled) 384 else 512,
                interChunkDelayMs = if (ecoModeEnabled) 28L else 16L,
                allowThinking = false,
                thinkingBudgetTokens = 0,
                pauseForOverheat = false,
                statusNote = "Raffreddamento attivo (${snapshot.batteryTempC}°C)"
            )
            ThermalLevel.WARM -> ThermalPacingPolicy(
                threadNicePriority = if (ecoModeEnabled) Process.THREAD_PRIORITY_LESS_FAVORABLE else Process.THREAD_PRIORITY_DEFAULT,
                maxOutputTokens = if (ecoModeEnabled) 768 else 1024,
                interChunkDelayMs = if (ecoModeEnabled) 10L else 0L,
                allowThinking = userRequestedThinking,
                thinkingBudgetTokens = if (userRequestedThinking) (if (ecoModeEnabled) 384 else 512) else 0,
                pauseForOverheat = false
            )
            ThermalLevel.COOL -> ThermalPacingPolicy(
                threadNicePriority = if (ecoModeEnabled) Process.THREAD_PRIORITY_LESS_FAVORABLE else Process.THREAD_PRIORITY_DEFAULT,
                maxOutputTokens = if (ecoModeEnabled) 1024 else 1536,
                interChunkDelayMs = if (ecoModeEnabled) 4L else 0L,
                allowThinking = userRequestedThinking,
                thinkingBudgetTokens = if (userRequestedThinking) (if (ecoModeEnabled) 512 else 768) else 0,
                pauseForOverheat = false
            )
            ThermalLevel.CRITICAL -> error("Handled above")
        }
    }

    /**
     * Decodes a large gallery URI using subsampling (inSampleSize) so multi-megapixel
     * photos are never expanded full-size in RAM, saving a compact JPEG <= maxDimensionPx.
     */
    fun decodeAndSaveCompressedImage(
        context: Context,
        sourceUri: Uri,
        destFile: File,
        maxDimensionPx: Int = 1024,
        jpegQuality: Int = 82
    ): Boolean {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            BitmapFactory.decodeStream(input, null, boundsOpts)
        } ?: return false

        val srcW = boundsOpts.outWidth
        val srcH = boundsOpts.outHeight
        if (srcW <= 0 || srcH <= 0) return false

        var sampleSize = 1
        while (srcW / (sampleSize * 2) >= maxDimensionPx && srcH / (sampleSize * 2) >= maxDimensionPx) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = context.contentResolver.openInputStream(sourceUri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOpts)
        } ?: return false

        return try {
            val longest = maxOf(decoded.width, decoded.height)
            val finalBmp = if (longest > maxDimensionPx) {
                val scale = maxDimensionPx.toFloat() / longest.toFloat()
                val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
                val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, targetW, targetH, true).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }
            try {
                FileOutputStream(destFile).use { out ->
                    finalBmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                    out.flush()
                }
                true
            } finally {
                if (!finalBmp.isRecycled) finalBmp.recycle()
            }
        } catch (_: Throwable) {
            if (!decoded.isRecycled) decoded.recycle()
            false
        }
    }

    fun compressImageFileInPlace(file: File, maxDimensionPx: Int = 1024, jpegQuality: Int = 82) {
        if (!file.exists()) return
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        val srcW = boundsOpts.outWidth
        val srcH = boundsOpts.outHeight
        if (srcW <= maxDimensionPx && srcH <= maxDimensionPx) return

        var sampleSize = 1
        while (srcW / (sampleSize * 2) >= maxDimensionPx && srcH / (sampleSize * 2) >= maxDimensionPx) {
            sampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return
        try {
            val longest = maxOf(decoded.width, decoded.height)
            val finalBmp = if (longest > maxDimensionPx) {
                val scale = maxDimensionPx.toFloat() / longest.toFloat()
                Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * scale).toInt().coerceAtLeast(1),
                    (decoded.height * scale).toInt().coerceAtLeast(1),
                    true
                ).also {
                    if (it !== decoded) decoded.recycle()
                }
            } else {
                decoded
            }
            try {
                FileOutputStream(file).use { out ->
                    finalBmp.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
                    out.flush()
                }
            } finally {
                if (!finalBmp.isRecycled) finalBmp.recycle()
            }
        } catch (_: Throwable) {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    fun pruneStaleImageCaches(context: Context, maxAgeMs: Long = 24L * 60L * 60L * 1000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        listOf("chat_images", "camera").forEach { dirName ->
            val dir = File(context.cacheDir, dirName)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && file.lastModified() < cutoff) {
                        runCatching { file.delete() }
                    }
                }
            }
        }
    }
}
