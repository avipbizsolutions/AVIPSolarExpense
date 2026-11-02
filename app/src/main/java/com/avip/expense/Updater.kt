package com.avip.expense

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val size: Long,
)

/**
 * Self-update from the app's public GitHub Releases (free, no server needed).
 * Release tags look like "v1.0.108"; the last number is the versionCode.
 */
object Updater {
    private const val LATEST_RELEASE =
        "https://api.github.com/repos/avipbizsolutions/AVIPSolarExpense/releases/latest"
    const val ACTION_INSTALL_STATUS = "com.avip.expense.INSTALL_STATUS"

    fun currentVersionCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Returns the latest published release, or null if offline / not found. */
    fun fetchLatest(): ReleaseInfo? = try {
        val conn = URL(LATEST_RELEASE).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "AVIP-Solar-Expense")
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        try {
            if (conn.responseCode != 200) null else {
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tag = json.getString("tag_name")
                val code = tag.substringAfterLast('.').toLongOrNull()
                val assets = json.getJSONArray("assets")
                var url: String? = null
                var size = 0L
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) {
                        url = a.getString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
                if (code == null || url == null) null
                else ReleaseInfo(code, tag.removePrefix("v"), url, size)
            }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    /** Downloads the APK into the app cache. Reuses an earlier complete download. */
    fun download(context: Context, info: ReleaseInfo, onProgress: (Int) -> Unit): File? {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "update-${info.versionCode}.apk")
        if (target.exists() && info.size > 0 && target.length() == info.size) return target
        dir.listFiles()?.forEach { it.delete() } // drop older downloads
        val part = File(dir, target.name + ".part")
        return try {
            var url = URL(info.apkUrl)
            var conn: HttpURLConnection
            var redirects = 0
            // GitHub redirects release downloads to another host; follow manually.
            while (true) {
                conn = url.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "AVIP-Solar-Expense")
                conn.connectTimeout = 10000
                conn.readTimeout = 20000
                val code = conn.responseCode
                if (code in 300..399 && redirects < 5) {
                    val next = conn.getHeaderField("Location") ?: return null
                    conn.disconnect()
                    url = URL(url, next)
                    redirects++
                    continue
                }
                if (code != 200) {
                    conn.disconnect()
                    return null
                }
                break
            }
            val total = if (info.size > 0) info.size else conn.contentLengthLong
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            conn.disconnect()
            if (info.size > 0 && part.length() != info.size) {
                part.delete()
                null
            } else {
                part.renameTo(target)
                target
            }
        } catch (_: Exception) {
            part.delete()
            null
        }
    }

    /**
     * Installs the APK over the running app. On Android 12+ it asks Android to
     * skip the confirmation; if Android still wants one, INSTALL_STATUS reports
     * PENDING_USER_ACTION and the caller shows the system "Update" prompt.
     */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val statusIntent = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, statusIntent, flags)
            session.commit(pending.intentSender)
        }
    }
}
