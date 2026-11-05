package com.avip.expense

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** A readable error from the office sheet. [sessionExpired] = log in again. */
class ApiException(message: String, val sessionExpired: Boolean = false, val network: Boolean = false) : Exception(message)

/**
 * The person logged in. Name, team and role come from the AVIP Team Directory
 * (the same login as AVIP Solar Sales and AVIP Solar Installation).
 */
data class User(
    val id: String, val name: String, val mobile: String, val team: String,
    val designation: String, val role: String,
) {
    val canApprove get() = role == Role.APPROVER || role == Role.ADMIN
    val isAccounts get() = role == Role.ACCOUNTS || role == Role.ADMIN
    val seesTeam get() = canApprove || isAccounts
    val isAdmin get() = role == Role.ADMIN

    /**
     * Managers (Approver) decide claims of Members and Accounts up to the limit in the sheet.
     * Above it, and every claim of a manager, only Admin (Atul / Rahul).
     */
    fun canDecide(c: Claim, limit: Double) = canApprove && (isAdmin || (!c.adminOnly && c.amount <= limit))

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("mobile", mobile)
        .put("team", team).put("designation", designation).put("role", role)

    companion object {
        fun from(o: JSONObject) = User(
            o.optString("id"), o.optString("name"), o.optString("mobile"),
            o.optString("team"), o.optString("designation"), o.optString("role"),
        )
    }
}

object Role {
    const val MEMBER = "Member"
    const val APPROVER = "Approver"
    const val ACCOUNTS = "Accounts"
    const val ADMIN = "Admin"

    fun label(role: String) = when (role) {
        APPROVER -> "Approver"
        ACCOUNTS -> "Accounts"
        ADMIN -> "Admin"
        else -> "Team Member"
    }
}

/** Who is logged in on this phone. */
object Session {
    private const val PREFS = "session"

    fun token(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("token", "") ?: ""

    fun user(c: Context): User? {
        val s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("user", "") ?: ""
        return if (s.isBlank()) null else try { User.from(JSONObject(s)) } catch (_: Exception) { null }
    }

    fun mustSetPin(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("mustSetPin", false)

    fun save(c: Context, token: String, user: User, mustSetPin: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("token", token).putString("user", user.toJson().toString())
            .putBoolean("mustSetPin", mustSetPin).apply()
    }

    /** Keeps the token, refreshes name / role after the office changes them. */
    fun updateUser(c: Context, user: User) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("user", user.toJson().toString()).apply()
    }

    /** Logs out. Cached lists go too; unsent claims stay for their owner's next login. */
    fun clear(c: Context) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        c.getSharedPreferences("store", Context.MODE_PRIVATE).edit().clear().apply()
    }
}

/**
 * Calls the Apps Script web app behind the expense Google Sheet.
 * Its address comes from config.json in the app's GitHub repo, so it can be
 * changed without a new app version.
 */
object Api {
    /** Must match SHARED_KEY in apps-script/Code.gs. */
    private const val SHARED_KEY = "avip-expense-4Rt8"

    /** Key for signing GPS-camera stamps (PhotoStamp); the office server checks with the same key. */
    internal fun stampKey() = SHARED_KEY
    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/avipbizsolutions/AVIPSolarExpense/main/config.json"
    private const val PREFS = "config"

    fun endpoint(c: Context): String =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("endpoint", "") ?: ""

    /** Reads config.json; keeps the last good address when offline. Returns true when an address is known. */
    fun refreshConfig(c: Context): Boolean {
        try {
            val conn = URL(CONFIG_URL + "?t=" + System.currentTimeMillis() / 60000).openConnection() as HttpURLConnection
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            try {
                if (conn.responseCode == 200) {
                    val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                    val ep = o.optString("endpoint").trim()
                    if (ep.startsWith("https://")) {
                        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("endpoint", ep).apply()
                    }
                }
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
        }
        return endpoint(c).startsWith("https://")
    }

    /** Blocking call; run it off the main thread. Throws [ApiException] with a message for the user. */
    fun call(c: Context, kind: String, body: JSONObject = JSONObject(), timeoutMs: Int = 60000): JSONObject {
        val ep = endpoint(c)
        if (!ep.startsWith("https://")) throw ApiException("Office server is not set up yet. Please tell the office.")
        body.put("key", SHARED_KEY).put("kind", kind)
        if (!body.has("token")) body.put("token", Session.token(c))
        val text = try {
            val conn = URL(ep).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 15000
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                stream?.bufferedReader()?.use { it.readText() } ?: ""
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            throw ApiException("No internet connection. Please try again.", network = true)
        }
        val o = try { JSONObject(text) } catch (_: Exception) {
            throw ApiException("Office server did not answer properly. Try again in a minute.", network = true)
        }
        if (!o.optBoolean("ok")) {
            val msg = o.optString("error").ifBlank { "Something went wrong." }
            if (msg.startsWith("SESSION:")) throw ApiException(msg.removePrefix("SESSION:").trim(), sessionExpired = true)
            if (msg == "unknown kind") throw ApiException("Office server needs updating (new Apps Script version).")
            throw ApiException(msg)
        }
        return o
    }
}
