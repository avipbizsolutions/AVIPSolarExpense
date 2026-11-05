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
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Notifications for approvers (and accounts): every ~15 minutes, in the background, the phone asks
 * the office server what is waiting and shows a notification for anything new since the last check.
 */
object ApprovalAlerts {
    private const val WORK = "approval-alerts"
    private const val CHANNEL = "approvals"
    private const val PREFS = "alerts"
    private const val NOTE_ID = 4101

    /** Start (or keep) the background check for people who approve or pay; stop it for everyone else. */
    fun schedule(c: Context) {
        val u = Session.user(c)
        val wm = WorkManager.getInstance(c)
        if (u == null || !(u.canApprove || u.isAccounts)) { wm.cancelUniqueWork(WORK); return }
        val req = PeriodicWorkRequestBuilder<Worker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun stop(c: Context) {
        WorkManager.getInstance(c).cancelUniqueWork(WORK)
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun channel(c: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = c.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Approval के लिए", "Waiting for approval"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = tr("नए Claim और Advance request", "New claims and advance requests")
            })
        }
    }

    private fun items(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }

    /** Checks once; shows one notification listing what is new. */
    fun check(c: Context) {
        val u = Session.user(c) ?: return
        if (!(u.canApprove || u.isAccounts)) return
        Lang.load(c)
        val r = Api.call(c, "pending", JSONObject(), timeoutMs = 45000)
        val claims = items(r.optJSONArray("claims"))
        val advances = items(r.optJSONArray("advances"))
        val toPay = items(r.optJSONArray("toPay"))

        val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getStringSet("seen_" + u.id, emptySet()) ?: emptySet()
        val now = (claims.map { "c:" + it.optString("id") } + advances.map { "a:" + it.optString("id") } + toPay.map { "p:" + it.optString("id") }).toSet()
        // remember only what is still waiting, so the list stays small
        prefs.edit().putStringSet("seen_" + u.id, now).apply()

        val newClaims = claims.filter { "c:" + it.optString("id") !in seen }
        val newAdv = advances.filter { "a:" + it.optString("id") !in seen }
        val newPay = toPay.filter { "p:" + it.optString("id") !in seen }
        if (newClaims.isEmpty() && newAdv.isEmpty() && newPay.isEmpty()) return

        val lines = mutableListOf<String>()
        newClaims.forEach { lines.add("Claim · " + it.optString("member") + " · " + Fmt.rupees(it.optDouble("amount")) + " · " + it.optString("category")) }
        newAdv.forEach { lines.add("Advance · " + it.optString("member") + " · " + Fmt.rupees(it.optDouble("amount")) + " · " + it.optString("purpose")) }
        newPay.forEach { lines.add(tr("Payment बाकी · ", "To pay · ") + it.optString("member") + " · " + Fmt.rupees(it.optDouble("amount"))) }

        val parts = mutableListOf<String>()
        if (newClaims.isNotEmpty()) parts.add(tr("${newClaims.size} नया Claim", "${newClaims.size} new claim" + if (newClaims.size > 1) "s" else ""))
        if (newAdv.isNotEmpty()) parts.add(tr("${newAdv.size} Advance request", "${newAdv.size} advance request" + if (newAdv.size > 1) "s" else ""))
        if (newPay.isNotEmpty()) parts.add(tr("${newPay.size} Advance payment बाकी", "${newPay.size} advance" + (if (newPay.size > 1) "s" else "") + " to pay"))
        val title = parts.joinToString(" · ") + tr(" — Approval के लिए", "")
        val waiting = claims.size + advances.size
        val summary = if (u.canApprove) tr("कुल approval बाकी: $waiting", "Waiting in total: $waiting") else ""

        show(c, title.trim(), lines, summary)
    }

    private fun show(c: Context, title: String, lines: List<String>, summary: String) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        channel(c)
        val open = c.packageManager.getLaunchIntentForPackage(c.packageName)?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
            ?: Intent(c, HomeActivity::class.java)
        val pi = PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.take(6).forEach { style.addLine(it) }
        if (lines.size > 6) style.addLine("+${lines.size - 6} …")
        if (summary.isNotBlank()) style.setSummaryText(summary)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_check)
            .setColor(Ui.BRAND)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull() ?: "")
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try { NotificationManagerCompat.from(c).notify(NOTE_ID, n) } catch (_: SecurityException) {}
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result = try {
            check(applicationContext)
            Result.success()
        } catch (e: ApiException) {
            if (e.network) Result.retry() else Result.success()
        } catch (_: Exception) {
            Result.success()
        }
    }
}
