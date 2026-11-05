package com.avip.expense

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Launch screen. Updates are compulsory, exactly like AVIP Solar Sales: if GitHub
 * has a newer release, it is downloaded and installed here and the app does not
 * open on the old version.
 */
@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var retry: View
    private var updateBox: View? = null
    private var leaving = false
    private var pendingRelease: ReleaseInfo? = null

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val installStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (confirm != null) {
                        try {
                            startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
                        } catch (_: Exception) {
                        }
                    }
                    blockForUpdate(tr("Installer नहीं खुल सका।", "The installer could not open."))
                }
                PackageInstaller.STATUS_SUCCESS -> {}
                else -> blockForUpdate(tr("Update install नहीं हुआ।", "The update was not installed."))
            }
        }
    }

    private val unknownSourcesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val r = pendingRelease
            if (r != null && Updater.canInstall(this)) lifecycleScope.launch { downloadAndInstall(r) }
            else if (r != null) blockForUpdate(tr("AVIP Solar Expense को updates install करने की अनुमति दें, फिर Update now दबाएँ।", "Allow AVIP Solar Expense to install updates, then tap Update now."))
            else goNext()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lang.load(this)
        val root = vertical {
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Ui.BRAND)
            setPadding(dp(24), dp(40), dp(24), dp(28))
        }
        root.addView(FrameLayout(this).apply {
            background = rounded(Color.WHITE, dpf(20f))
            elevation = dpf(6f)
            setPadding(dp(24), dp(18), dp(24), dp(18))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.avip_solar_logo)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
        }, lp(MATCH, WRAP))
        root.addView(View(this), lp(MATCH, 0, 1f))
        val brand = vertical {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(text("AVIP Solar Expense", 26f, Color.WHITE, bold = true) { gravity = Gravity.CENTER }, lp(WRAP, WRAP))
            addView(text(tr("हर खर्च का हिसाब, Claim से Payment तक", "Every expense, from claim to payment"), 14f, 0xDDFFFFFF.toInt()) { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(context, t = 6))
            addView(text("by AVIP Business Solutions LLP", 12f, Ui.SUN, bold = true) { gravity = Gravity.CENTER }, lp(WRAP, WRAP).margins(context, t = 4))
        }
        root.addView(brand)
        root.addView(View(this), lp(MATCH, 0, 1f))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.SUN)
            indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.SUN)
            max = 100
        }
        root.addView(progress, lp(dp(200), WRAP))
        status = text(tr("Updates check हो रहे हैं…", "Checking for updates…"), 13f, 0xDDFFFFFF.toInt()) { gravity = Gravity.CENTER }
        root.addView(status, lp(WRAP, WRAP).margins(this, t = 10))
        retry = pillButton("Retry", Ui.SUN, Ui.TEXT) { start() }.apply { visibility = View.GONE }
        root.addView(retry, lp(dp(160), WRAP).margins(this, t = 16))
        root.addView(text("v" + Updater.currentVersionName(this), 11f, 0x99FFFFFF.toInt()), lp(WRAP, WRAP).margins(this, t = 16))
        setContentView(root)

        brand.alpha = 0f
        brand.animate().alpha(1f).setDuration(300).start()

        ContextCompat.registerReceiver(
            this, installStatusReceiver, IntentFilter(Updater.ACTION_INSTALL_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val versionCode = Updater.currentVersionCode(this)
        val lastSeen = prefs.getLong("last_version_code", 0L)
        if (lastSeen in 1 until versionCode) {
            Toast.makeText(this, tr("Version ${Updater.currentVersionName(this)} पर update हो गया", "Updated to version ${Updater.currentVersionName(this)}"), Toast.LENGTH_LONG).show()
        }
        prefs.edit().putLong("last_version_code", versionCode).apply()

        start()
    }

    private fun start() {
        retry.visibility = View.GONE
        status.text = tr("Updates check हो रहे हैं…", "Checking for updates…")
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true

        lifecycleScope.launch {
            val minimum = async { delay(700) }
            val update = async { withTimeoutOrNull(5000L) { withContext(Dispatchers.IO) { Updater.fetchLatest() } } }
            val config = async { withContext(Dispatchers.IO) { Api.refreshConfig(this@SplashActivity) } }
            Outbox.flush(this@SplashActivity)
            Thread { PhotoCache.trim(this@SplashActivity) }.start()

            val latest = update.await()
            val current = Updater.currentVersionCode(this@SplashActivity)
            if (latest != null && latest.versionCode > current) {
                // updates are compulsory: the app does not open on an old version
                pendingRelease = latest
                if (Updater.canInstall(this@SplashActivity)) downloadAndInstall(latest) else askInstallPermission(latest)
                return@launch
            }
            val ready = config.await()
            minimum.await()
            if (!ready) {
                progress.visibility = View.GONE
                status.text = tr("Office server अभी set up नहीं है, या internet नहीं है।", "The office server is not set up yet, or there is no internet.")
                retry.visibility = View.VISIBLE
                return@launch
            }
            goNext()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(installStatusReceiver) } catch (_: Exception) {}
    }

    private suspend fun downloadAndInstall(release: ReleaseInfo) {
        status.text = tr("Version ${release.versionName} download हो रहा है…", "Downloading version ${release.versionName}…")
        progress.isIndeterminate = false
        progress.progress = 0
        val apk = withContext(Dispatchers.IO) {
            Updater.download(this@SplashActivity, release) { pct -> runOnUiThread { progress.progress = pct } }
        }
        if (apk == null) { blockForUpdate(tr("Download नहीं हुआ। Internet check करके फिर कोशिश करें।", "Download failed. Check the internet and try again.")); return }
        status.text = tr("Update install हो रहा है…", "Installing update…")
        try {
            withContext(Dispatchers.IO) { Updater.install(this@SplashActivity, apk) }
        } catch (_: Exception) {
            blockForUpdate(tr("Update install नहीं हो सका।", "The update could not be installed."))
        }
    }

    private fun askInstallPermission(release: ReleaseInfo) {
        AlertDialog.Builder(this)
            .setTitle(tr("नया Version ज़रूरी है", "New version required"))
            .setMessage(tr("Version ${release.versionName} के बिना App नहीं खुलेगा। AVIP Solar Expense को अपने updates install करने की अनुमति दें? यह सिर्फ एक बार पूछा जाएगा।", "The app will not open without version ${release.versionName}. Allow AVIP Solar Expense to install its updates? This is asked only once."))
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ ->
                unknownSourcesLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            }
            .setNegativeButton(tr("अभी नहीं", "Not now")) { _, _ -> blockForUpdate(tr("App चलाने के लिए यह update ज़रूरी है।", "This update is required to use the app.")) }
            .show()
    }

    /** Update available but not installed: the app stays here until it is updated. */
    private fun blockForUpdate(reason: String) {
        val r = pendingRelease ?: run { goNext(); return }
        if (isFinishing) return
        progress.visibility = View.GONE
        status.text = tr("Update ज़रूरी है — Version ${r.versionName}\n$reason", "Update required: version ${r.versionName}\n$reason")
        retry.visibility = View.GONE
        updateBox?.let { (it.parent as? ViewGroup)?.removeView(it) }
        val box = vertical {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(pillButton("Update now", Ui.SUN, Ui.TEXT) {
                if (Updater.canInstall(this@SplashActivity)) lifecycleScope.launch {
                    progress.visibility = View.VISIBLE
                    (updateBox?.parent as? ViewGroup)?.removeView(updateBox)
                    downloadAndInstall(r)
                } else askInstallPermission(r)
            }, lp(dp(200), WRAP))
            addView(text(tr("Website से Download करें", "Download from the website"), 13f, Color.WHITE, bold = true) {
                setPadding(dp(10), dp(12), dp(10), dp(6))
                setOnClickListener {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/avipbizsolutions/AVIPSolarExpense/releases/latest/download/AVIP-Solar-Expense.apk")))
                    } catch (_: Exception) {}
                }
            })
        }
        updateBox = box
        (status.parent as ViewGroup).let { p -> p.addView(box, p.indexOfChild(status) + 1, lp(WRAP, WRAP).margins(this, t = 16)) }
    }

    private fun goNext() {
        if (leaving || isFinishing) return
        leaving = true
        val loggedIn = Session.token(this).isNotBlank() && Session.user(this) != null && !Session.mustSetPin(this)
        startActivity(Intent(this, if (loggedIn) HomeActivity::class.java else LoginActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
