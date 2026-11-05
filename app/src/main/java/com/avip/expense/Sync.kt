package com.avip.expense

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Actions done without internet (advance request, approve / reject, mark paid, pay advance, give advance),
 * kept on the phone in order and sent when the internet is back. The office server ignores repeats,
 * so sending again after a lost reply is safe.
 */
object Actions {
    private const val PREFS = "actions"

    fun all(c: Context): List<JSONObject> {
        val me = Session.user(c)?.id ?: return emptyList()
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.values
            .mapNotNull { v -> try { JSONObject(v as String) } catch (_: Exception) { null } }
            .filter { it.optString("owner") == me }
            .sortedBy { it.optLong("savedAt") }
    }

    /** @param refs ids of the claims / requests this action is about (to hide or mark them in lists). */
    fun add(c: Context, kind: String, body: JSONObject, label: String, refs: List<String>) {
        val id = "ACT-" + System.currentTimeMillis() + "-" + (100..999).random()
        val o = JSONObject().put("id", id).put("kind", kind).put("body", body).put("label", label)
            .put("refs", org.json.JSONArray(refs)).put("savedAt", System.currentTimeMillis()).put("owner", Session.user(c)?.id ?: "")
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(id, o.toString()).commit()
        Sync.schedule(c)
    }

    fun remove(c: Context, id: String) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).commit() }

    /** Claim / request ids with an action still waiting to be sent. */
    fun pendingRefs(c: Context): Set<String> = all(c).filter { it.optString("error").isBlank() }
        .flatMap { o -> (0 until (o.optJSONArray("refs")?.length() ?: 0)).map { o.getJSONArray("refs").getString(it) } }.toSet()

    fun failed(c: Context) = all(c).filter { it.optString("error").isNotBlank() }

    /** Sends what is waiting, in order; stops at the first network problem. Returns how many went. */
    fun sendAll(c: Context): Int {
        if (Session.token(c).isBlank()) return 0
        val app = c.applicationContext
        var sent = 0
        synchronized(this) {
            for (o in all(app)) {
                if (o.optString("error").isNotBlank()) continue
                try {
                    val body = JSONObject(o.getJSONObject("body").toString())
                    val r = Api.call(app, o.getString("kind"), body, timeoutMs = 90000)
                    remove(app, o.getString("id")); sent++
                    // the office refused some items (e.g. already decided by someone else): keep a note
                    val skipped = r.optJSONArray("skipped")
                    if (skipped != null && skipped.length() > 0 && r.optInt("done", 1) == 0) {
                        o.put("error", (0 until skipped.length()).joinToString("; ") { skipped.optString(it) })
                        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(o.getString("id"), o.toString()).commit()
                    }
                } catch (e: ApiException) {
                    if (e.network || e.sessionExpired) break
                    o.put("error", e.message ?: "Could not send")
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(o.getString("id"), o.toString()).commit()
                } catch (e: Exception) {
                    o.put("error", e.message ?: "Could not send")
                    app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(o.getString("id"), o.toString()).commit()
                }
            }
        }
        return sent
    }
}

/** Sends everything waiting on the phone (claims, then actions) as soon as there is internet, even with the app closed. */
object Sync {
    private const val WORK = "sync-outbox"

    fun pending(c: Context) = Outbox.all(c).any { it.optString("error").isBlank() } || Actions.all(c).any { it.optString("error").isBlank() }

    fun schedule(c: Context) {
        val req = OneTimeWorkRequestBuilder<Worker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(c).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, req)
    }

    /** Claims first (actions may be about them), then actions. On a background thread. */
    fun now(c: Context): Int = Outbox.sendAll(c) + Actions.sendAll(c)

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            return try {
                now(applicationContext)
                if (pending(applicationContext)) Result.retry() else Result.success()
            } catch (_: Exception) {
                Result.retry()
            }
        }
    }
}

/**
 * Sends to the office now; without internet the action is saved on the phone and sent later.
 * [done] gets the server reply, or {queued: true, done: n} when saved for later.
 */
fun AppCompatActivity.sendOrQueue(message: String, kind: String, body: JSONObject, label: String, refs: List<String>, done: (JSONObject) -> Unit) {
    busy(message, { Api.call(this, kind, JSONObject(body.toString())) }, onError = { e ->
        if (e is ApiException && e.network) {
            Actions.add(this, kind, body, label, refs)
            toast(tr("Internet नहीं है — phone पर save हुआ, internet आते ही अपने आप भेजा जाएगा।", "No internet: saved on the phone, it will be sent automatically when the internet is back."))
            done(JSONObject().put("queued", true).put("done", refs.size.coerceAtLeast(1)))
            true
        } else false
    }) { done(it) }
}
