package com.avip.expense

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Measures how far the phone travels during a bike journey (Start journey → Send claim).
 * Runs as a foreground service with a visible notification, so the rider always knows it is on.
 * Only the total distance is kept (no route, no list of places); it goes with the claim and is
 * shown to approvers, accounts and founders next to the odometer distance.
 */
class JourneyTracker : Service(), LocationListener {

    companion object {
        private const val PREFS = "journey_track"
        private const val ACTIVE = "active"
        private const val CHANNEL = "tracking"
        private const val NOTE_ID = 4301
        /** A fix worse than this many metres is not used. */
        private const val MAX_ACCURACY = 35f
        /** Faster than this between two fixes is a GPS jump, not a bike (m/s ≈ 160 km/h). */
        private const val MAX_SPEED = 45.0

        private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun hasPermission(c: Context) =
            ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Starts measuring for this journey (call while the app is on screen). */
        fun start(c: Context, journeyId: String) {
            val p = prefs(c)
            if (!p.contains("j_$journeyId")) p.edit().putString("j_$journeyId", JSONObject().put("m", 0.0).put("n", 0).put("from", System.currentTimeMillis()).toString()).apply()
            p.edit().putString(ACTIVE, journeyId).apply()
            launch(c)
        }

        /** Keeps the measuring alive while a journey is open (after a phone restart, or if the system stopped it). */
        fun ensure(c: Context) {
            val id = prefs(c).getString(ACTIVE, "") ?: ""
            if (id.isBlank()) return
            if (Journeys.get(c, id) == null) { stop(c); return }
            launch(c)
        }

        private fun launch(c: Context) {
            if (!hasPermission(c)) return
            try { ContextCompat.startForegroundService(c, Intent(c, JourneyTracker::class.java)) } catch (_: Exception) {}
        }

        fun stop(c: Context) {
            prefs(c).edit().remove(ACTIVE).apply()
            try { c.stopService(Intent(c, JourneyTracker::class.java)) } catch (_: Exception) {}
        }

        /** Distance measured for a journey in km, or null when GPS gave too little to say. */
        fun km(c: Context, journeyId: String): Double? {
            val o = try { JSONObject(prefs(c).getString("j_$journeyId", "") ?: "") } catch (_: Exception) { return null }
            if (o.optInt("n") < 2) return null
            return Math.round(o.optDouble("m", 0.0) / 100.0) / 10.0
        }

        fun clear(c: Context, journeyId: String) {
            prefs(c).edit().remove("j_$journeyId").apply()
            if (prefs(c).getString(ACTIVE, "") == journeyId) stop(c)
        }
    }

    private var id = ""
    private var listening = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        id = prefs(this).getString(ACTIVE, "") ?: ""
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTE_ID, notification())
        } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        if (id.isBlank() || !hasPermission(this)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        if (!listening) {
            try {
                val lm = getSystemService(LOCATION_SERVICE) as LocationManager
                if (lm.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4000L, 8f, this, mainLooper)
                    listening = true
                }
            } catch (_: SecurityException) {} catch (_: Exception) {}
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try { (getSystemService(LOCATION_SERVICE) as LocationManager).removeUpdates(this) } catch (_: Exception) {}
        listening = false
        super.onDestroy()
    }

    override fun onLocationChanged(loc: Location) {
        if (id.isBlank()) return
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY) return
        val p = prefs(this)
        val o = try { JSONObject(p.getString("j_$id", "") ?: "") } catch (_: Exception) { JSONObject().put("m", 0.0).put("n", 0) }
        val now = System.currentTimeMillis()
        if (o.has("lat")) {
            val d = FloatArray(1)
            Location.distanceBetween(o.optDouble("lat"), o.optDouble("lng"), loc.latitude, loc.longitude, d)
            val metres = d[0].toDouble()
            // standing still: GPS wanders a few metres; count a move only when it is clearly more than the error
            if (metres < maxOf(15.0, (if (loc.hasAccuracy()) loc.accuracy else 0f).toDouble())) return
            val secs = maxOf(1.0, (now - o.optLong("t", now)) / 1000.0)
            if (metres / secs > MAX_SPEED) return
            o.put("m", o.optDouble("m", 0.0) + metres)
        }
        o.put("lat", loc.latitude).put("lng", loc.longitude).put("t", now).put("n", o.optInt("n") + 1)
        p.edit().putString("j_$id", o.toString()).apply()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun notification(): Notification {
        Lang.load(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("Journey की दूरी", "Journey distance"), NotificationManager.IMPORTANCE_LOW).apply {
                    description = tr("Journey चलते समय GPS से दूरी नापी जाती है", "While a journey is running, the distance is measured by GPS")
                })
            }
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
            ?: Intent(this, HomeActivity::class.java)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_location)
            .setColor(Ui.BRAND)
            .setContentTitle(tr("Journey चल रही है", "Journey running"))
            .setContentText(tr("GPS से दूरी नापी जा रही है। लौटकर app में Journey पूरी करें।", "Distance is being measured by GPS. Finish the journey in the app when you are back."))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(PendingIntent.getActivity(this, 2, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
    }
}
