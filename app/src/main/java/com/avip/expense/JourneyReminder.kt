package com.avip.expense

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Evening reminder: about 7 PM every day, if a bike journey is still open on this phone,
 * a notification asks the rider to take the end odometer photo and send the claim.
 * Works without internet (the journeys are on the phone).
 */
object JourneyReminder {
    private const val WORK = "journey-reminder"
    private const val CHANNEL = "journeys"
    private const val NOTE_ID = 4201
    private const val HOUR = 19

    fun schedule(c: Context) {
        val now = Calendar.getInstance()
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, HOUR); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
        }
        val req = PeriodicWorkRequestBuilder<Worker>(24, TimeUnit.HOURS)
            .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
            .build()
        // KEEP: the first schedule fixes the time of day; later calls leave it alone
        WorkManager.getInstance(c).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun stop(c: Context) {
        WorkManager.getInstance(c).cancelUniqueWork(WORK)
        try { NotificationManagerCompat.from(c).cancel(NOTE_ID) } catch (_: Exception) {}
    }

    /** Removes the reminder from the notification shade once nothing is open any more. */
    fun clearIfDone(c: Context) {
        if (Journeys.all(c).isEmpty()) try { NotificationManagerCompat.from(c).cancel(NOTE_ID) } catch (_: Exception) {}
    }

    fun check(c: Context) {
        if (Session.user(c) == null) return
        Lang.load(c)
        val open = Journeys.all(c)
        if (open.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Journey याद दिलाना", "Journey reminders"), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = tr("शाम को: जो Journey पूरी नहीं हुई", "In the evening: journeys not finished yet")
                })
            }
        }
        val fmt = SimpleDateFormat("dd MMM, hh:mm a", Locale.ENGLISH)
        val lines = open.map { j ->
            val t = j.optLong("startTime", 0L).takeIf { it > 0 } ?: j.optLong("savedAt")
            tr("शुरू: ", "Started: ") + fmt.format(Date(t)) + (if (j.optString("description").isNotBlank()) " · " + j.optString("description") else "")
        }
        val title = if (open.size == 1) tr("Journey अभी पूरी नहीं हुई", "Your journey is not finished")
            else tr("${open.size} Journey अभी पूरी नहीं हुईं", "${open.size} journeys are not finished")
        val msg = tr("आखिर के odometer की photo लें और Claim भेजें।", "Take the end odometer photo and send the claim.")
        val intent = c.packageManager.getLaunchIntentForPackage(c.packageName)?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
            ?: Intent(c, HomeActivity::class.java)
        val pi = PendingIntent.getActivity(c, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title).addLine(msg)
        lines.take(5).forEach { style.addLine(it) }
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_location)
            .setColor(Ui.BRAND)
            .setContentTitle(title)
            .setContentText(msg)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { NotificationManagerCompat.from(c).notify(NOTE_ID, n) } catch (_: SecurityException) {}
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            try { check(applicationContext) } catch (_: Exception) {}
            return Result.success()
        }
    }
}
